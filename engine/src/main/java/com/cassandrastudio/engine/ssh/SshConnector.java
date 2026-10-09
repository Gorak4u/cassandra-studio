package com.cassandrastudio.engine.ssh;

import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.SecretKeys;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.AttributeRepository.AttributeKey;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.core.CoreModuleProperties;

/**
 * Opens SSH sessions to nodes as configured per connection (CON-7): user and port, ssh-agent,
 * key file (optional passphrase) or password, optional jump host with the same credentials,
 * known_hosts checking. One MINA client, started on first use, serves every connection.
 * Never reads ~/.ssh/config or default identities: only what the connection says.
 */
public final class SshConnector implements AutoCloseable {
    /** Finds the running ssh-agent (SSH_AUTH_SOCK, or the Windows OpenSSH pipe). */
    @FunctionalInterface
    interface AgentLocator {
        String locate() throws IOException;
    }

    private final KnownHostsVerifier verifier = new KnownHostsVerifier();
    private final AgentLocator agentLocator;
    private final Map<String, List<KeyPair>> keyCache = new ConcurrentHashMap<>();
    private SshClient client;
    private boolean closed;

    public SshConnector() {
        this(AgentConnection::locate);
    }

    SshConnector(AgentLocator agentLocator) {
        this.agentLocator = agentLocator;
    }

    /** Credentials resolved before connecting, so a missing secret fails fast and clearly. */
    private record Credentials(String agent, String password, List<KeyPair> keys) {}

    /**
     * An authenticated session to {@code host}, through the jump host when one is configured.
     * Everything (jump host, node, auth) must finish within {@code timeout}.
     */
    public SshConnection connect(ConnectionConfig.Ssh ssh, Map<String, String> secrets, String host, Duration timeout) {
        if (ssh.username() == null || ssh.username().isBlank()) {
            throw new SshAccessException("SSH user name is not set for this connection");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        Credentials creds = credentials(ssh, secrets);
        SshClient c = client();
        ClientSession jump = null;
        String jumpName = null;
        try {
            SocketAddress dial;
            if (ssh.jumpHost() != null && !ssh.jumpHost().isBlank()) { // the jump host resolves the node's name
                String jumpUser = ssh.jumpUser() == null || ssh.jumpUser().isBlank() ? ssh.username() : ssh.jumpUser();
                jumpName = ssh.jumpHost() + ":" + ssh.jumpPort();
                jump = open(c, jumpUser, ssh.jumpHost(), ssh.jumpPort(), resolve(ssh.jumpHost(), ssh.jumpPort()),
                        ssh, creds, deadline, timeout);
                String unreachable = SshConnection.probe(jump, host, ssh.port(), remaining(deadline, timeout, host));
                if (unreachable != null) {
                    throw new SshAccessException("jump host " + jumpName + " cannot reach " + host + ":" + ssh.port()
                            + ": " + unreachable);
                }
                int local = jump.startLocalPortForwarding(new SshdSocketAddress("127.0.0.1", 0),
                        new SshdSocketAddress(host, ssh.port())).getPort();
                dial = new InetSocketAddress("127.0.0.1", local);
            } else {
                dial = resolve(host, ssh.port());
            }
            ClientSession node = open(c, ssh.username(), host, ssh.port(), dial, ssh, creds, deadline, timeout);
            return new SshConnection(node, jump, jumpName);
        } catch (IOException | RuntimeException e) {
            if (jump != null) jump.close(true);
            if (e instanceof SshAccessException s) throw s;
            throw new SshAccessException("SSH to " + host + ":" + ssh.port() + " failed: " + e.getMessage(), e);
        }
    }

    private static SocketAddress resolve(String host, int port) {
        InetSocketAddress a = new InetSocketAddress(host, port);
        if (a.isUnresolved()) throw new SshAccessException("unknown host " + host);
        return a;
    }

    private ClientSession open(SshClient c, String user, String host, int port, SocketAddress dial,
                               ConnectionConfig.Ssh ssh, Credentials creds, long deadline, Duration timeout) {
        String who = user + "@" + host + (port == 22 ? "" : ":" + port);
        HostKeyCheck check = new HostKeyCheck(host, port, ssh.strictHostKeyChecking(), knownHosts(ssh));
        Map<AttributeKey<?>, Object> attrs = new HashMap<>();
        attrs.put(HostKeyCheck.KEY, check);
        if (creds.agent() != null) attrs.put(AgentFactory.AGENT_LOCATION, creds.agent());
        ClientSession s;
        try {
            ConnectFuture f = c.connect(user, dial, AttributeRepository.ofAttributesMap(attrs), null);
            f.verify(remaining(deadline, timeout, host));
            s = f.getSession();
        } catch (IOException e) {
            if (System.nanoTime() >= deadline) throw timedOut(timeout, "SSH to " + host + ":" + port, e);
            if (has(e, ConnectException.class)) throw new SshAccessException("SSH connection to " + host + ":" + port + " refused", e);
            if (has(e, NoRouteToHostException.class)) throw new SshAccessException("no route to host " + host, e);
            throw new SshAccessException("SSH to " + host + ":" + port + " failed: " + e.getMessage(), e);
        }
        try {
            if (creds.password() != null) s.addPasswordIdentity(creds.password());
            for (KeyPair k : creds.keys()) s.addPublicKeyIdentity(k);
            long left = deadline - System.nanoTime();
            if (left <= 0) throw timedOut(timeout, "SSH login " + who, null);
            s.auth().verify(Duration.ofNanos(left));
            return s;
        } catch (SshAccessException e) {
            s.close(true);
            throw e;
        } catch (IOException | RuntimeException e) {
            s.close(true);
            if (check.rejection() != null) throw new SshAccessException(check.rejection(), e);
            if (System.nanoTime() >= deadline) throw timedOut(timeout, "SSH login " + who, e);
            throw new SshAccessException("SSH auth failed for user " + who + " (" + ssh.auth().name().toLowerCase()
                    + " authentication)", e);
        }
    }

    private static Duration remaining(long deadline, Duration timeout, String host) {
        long left = deadline - System.nanoTime();
        if (left <= 0) throw timedOut(timeout, "SSH to " + host, null);
        return Duration.ofNanos(left);
    }

    static SshAccessException timedOut(Duration timeout, String what, Throwable cause) {
        return new SshAccessException(what + " timed out after " + seconds(timeout), cause);
    }

    static String seconds(Duration d) {
        return d.toMillis() % 1000 == 0 ? d.toSeconds() + " s" : String.format("%.1f s", d.toMillis() / 1000.0);
    }

    static boolean has(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) return true;
        }
        return false;
    }

    private static Path knownHosts(ConnectionConfig.Ssh ssh) {
        String p = ssh.knownHostsPath();
        return p == null || p.isBlank() ? Path.of(System.getProperty("user.home"), ".ssh", "known_hosts") : expand(p);
    }

    static Path expand(String path) {
        if (path.equals("~")) return Path.of(System.getProperty("user.home"));
        if (path.startsWith("~/") || path.startsWith("~\\")) return Path.of(System.getProperty("user.home"), path.substring(2));
        return Path.of(path);
    }

    private Credentials credentials(ConnectionConfig.Ssh ssh, Map<String, String> secrets) {
        return switch (ssh.auth()) {
            case PASSWORD -> {
                String pw = secrets.get(SecretKeys.SSH_PASSWORD);
                if (pw == null || pw.isEmpty()) throw new SshAccessException("SSH password is not set for this connection");
                yield new Credentials(null, pw, List.of());
            }
            case KEY -> new Credentials(null, null, keys(ssh.keyPath(), secrets.get(SecretKeys.SSH_PASSPHRASE)));
            case AGENT -> new Credentials(agent(), null, List.of());
        };
    }

    private String agent() {
        try {
            String where = agentLocator.locate();
            try (AgentConnection a = AgentConnection.open(where)) {
                if (!a.getIdentities().iterator().hasNext()) {
                    throw new SshAccessException("ssh-agent has no keys: ssh-add your key first");
                }
            }
            return where;
        } catch (IOException e) {
            throw new SshAccessException(e.getMessage(), e);
        }
    }

    /** Key pairs from a private key file (OpenSSH or PEM; RSA, ECDSA, Ed25519), cached until the file changes. */
    List<KeyPair> keys(String keyPath, String passphrase) {
        if (keyPath == null || keyPath.isBlank()) throw new SshAccessException("SSH key file is not set for this connection");
        Path file = expand(keyPath);
        String cacheKey;
        try {
            cacheKey = file.toAbsolutePath() + "|" + Files.getLastModifiedTime(file).toMillis() + "|" + Files.size(file)
                    + "|" + sha256(passphrase);
        } catch (IOException e) {
            throw new SshAccessException("cannot read SSH key file " + file, e);
        }
        List<KeyPair> cached = keyCache.get(cacheKey);
        if (cached != null) return cached;
        List<KeyPair> loaded = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file)) {
            Iterable<KeyPair> it = SecurityUtils.loadKeyPairIdentities(null, NamedResource.ofName(file.toString()), in,
                    FilePasswordProvider.of(passphrase));
            if (it != null) it.forEach(loaded::add);
        } catch (Exception e) {
            String why = passphrase == null || passphrase.isEmpty()
                    ? " (if it is encrypted, set its passphrase)" : " (wrong passphrase?)";
            throw new SshAccessException("cannot load SSH key file " + file + why, e);
        }
        if (loaded.isEmpty()) throw new SshAccessException("no private key found in " + file);
        keyCache.keySet().removeIf(k -> k.startsWith(file.toAbsolutePath() + "|"));
        keyCache.put(cacheKey, List.copyOf(loaded));
        return loaded;
    }

    private static String sha256(String s) {
        if (s == null) return "";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private synchronized SshClient client() {
        if (closed) throw new SshAccessException("SSH is shut down");
        if (client == null) {
            SshClient c = SshClient.setUpDefaultClient();
            c.setServerKeyVerifier(verifier);
            c.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
            c.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
            c.setAgentFactory(new AgentFactory());
            // Notice dead peers (and keep NAT/firewall state) on long-lived tunnels.
            CoreModuleProperties.HEARTBEAT_INTERVAL.set(c, Duration.ofSeconds(30));
            CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.set(c, 3);
            c.start();
            client = c;
        }
        return client;
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (client != null) {
            client.stop();
            client = null;
        }
    }
}
