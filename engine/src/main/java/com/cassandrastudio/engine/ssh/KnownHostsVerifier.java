package com.cassandrastudio.engine.ssh;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.sshd.client.config.hosts.KnownHostEntry;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks SSH host keys against a known_hosts file (CON-7): plain, wildcard, {@code [host]:port}
 * and hashed entries, {@code @revoked} markers. With strict checking off an unknown or changed key
 * is accepted with a warning in the log. Studio never writes to known_hosts.
 */
final class KnownHostsVerifier implements ServerKeyVerifier {
    private static final Logger LOG = LoggerFactory.getLogger(KnownHostsVerifier.class);

    private record Parsed(FileTime modified, long size, List<KnownHostEntry> entries) {}

    private final Map<Path, Parsed> cache = new ConcurrentHashMap<>();

    @Override
    public boolean verifyServerKey(ClientSession session, SocketAddress remote, PublicKey serverKey) {
        AttributeRepository ctx = session.getConnectionContext();
        HostKeyCheck check = ctx == null ? null : ctx.getAttribute(HostKeyCheck.KEY);
        if (check == null) return false; // every Studio connection carries a policy
        String reason = rejection(check, serverKey);
        if (reason != null) check.reject(reason);
        return reason == null;
    }

    /** Null when the key is acceptable under {@code check}, else a user-readable reason. */
    String rejection(HostKeyCheck check, PublicKey serverKey) {
        String name = check.displayName();
        String fingerprint = KeyUtils.getFingerPrint(serverKey);
        List<KnownHostEntry> entries;
        try {
            entries = entries(check.knownHosts);
        } catch (IOException e) {
            if (check.strict) return "cannot read known_hosts file " + check.knownHosts + ": " + e.getMessage();
            entries = List.of();
        }
        boolean hostKnown = false;
        for (KnownHostEntry e : entries) {
            if (!e.isHostMatch(check.host, check.port) || "cert-authority".equals(e.getMarker())) continue;
            PublicKey known = resolve(e);
            if (known == null) continue;
            boolean same = KeyUtils.compareKeys(known, serverKey);
            if ("revoked".equals(e.getMarker())) {
                if (same) return "host key for " + name + " is marked @revoked in " + check.knownHosts;
                continue;
            }
            if (same) return null;
            hostKnown = true;
        }
        if (!check.strict) {
            LOG.warn("Host key checking is off: accepting {} host key {} for {}", hostKnown ? "CHANGED" : "unknown",
                    fingerprint, name);
            return null;
        }
        return hostKnown
                ? "host key for " + name + " does not match " + check.knownHosts + " (got " + fingerprint
                        + "; the key changed or someone is intercepting the connection)"
                : "host key for " + name + " not in known_hosts " + check.knownHosts + " (" + fingerprint
                        + "); add it with ssh-keyscan, or turn off strict host key checking";
    }

    private static PublicKey resolve(KnownHostEntry e) {
        try {
            return e.getKeyEntry() == null ? null
                    : e.getKeyEntry().resolvePublicKey(null, Map.of(), PublicKeyEntryResolver.IGNORING);
        } catch (Exception ex) {
            return null; // key type this client does not know: cannot match
        }
    }

    private List<KnownHostEntry> entries(Path file) throws IOException {
        FileTime modified;
        long size;
        try {
            modified = Files.getLastModifiedTime(file);
            size = Files.size(file);
        } catch (NoSuchFileException e) {
            return List.of();
        }
        Parsed p = cache.get(file);
        if (p != null && p.modified.equals(modified) && p.size == size) return p.entries;
        List<KnownHostEntry> parsed = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#")) continue;
            try {
                KnownHostEntry e = KnownHostEntry.parseKnownHostEntry(l);
                if (e != null) parsed.add(e);
            } catch (RuntimeException ignored) {
                // one malformed line must not hide the others
            }
        }
        cache.put(file, new Parsed(modified, size, List.copyOf(parsed)));
        return parsed;
    }
}
