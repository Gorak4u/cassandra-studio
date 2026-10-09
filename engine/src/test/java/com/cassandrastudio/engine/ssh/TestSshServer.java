package com.cassandrastudio.engine.ssh;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;

/**
 * In-process SSH server for tests: user "studio", password "secret" or the client key from
 * {@link #clientKey}, TCP forwarding allowed, so jump-host and tunnel paths run without Docker.
 */
public final class TestSshServer implements AutoCloseable {
    public static final String USER = "studio";
    public static final String PASSWORD = "secret";

    private final SshServer server;
    private final KeyPair hostKey;
    private final KeyPair clientKey;

    public TestSshServer() throws IOException, GeneralSecurityException {
        hostKey = ed25519();
        clientKey = ed25519();
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(KeyPairProvider.wrap(hostKey));
        server.setPasswordAuthenticator((user, pw, session) -> USER.equals(user) && PASSWORD.equals(pw));
        server.setPublickeyAuthenticator((user, key, session) ->
                USER.equals(user) && KeyUtils.compareKeys(key, clientKey.getPublic()));
        server.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        server.start();
    }

    public static KeyPair ed25519() throws GeneralSecurityException {
        return KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
    }

    public int port() {
        return server.getPort();
    }

    public PublicKey hostKey() {
        return hostKey.getPublic();
    }

    public KeyPair clientKey() {
        return clientKey;
    }

    /** A known_hosts line for this server as reached at 127.0.0.1:port. */
    public String knownHostsLine() {
        return "[127.0.0.1]:" + port() + " " + PublicKeyEntry.toString(hostKey());
    }

    public Path writeKnownHosts(Path dir) throws IOException {
        Path p = dir.resolve("known_hosts");
        Files.writeString(p, "# test\n" + knownHostsLine() + "\n");
        return p;
    }

    /** Writes a private key in OpenSSH format, unencrypted. */
    public static Path writeKey(KeyPair key, Path file) throws IOException, GeneralSecurityException {
        try (OutputStream out = Files.newOutputStream(file)) {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(key, "test", null, out);
        }
        return file;
    }

    @Override
    public void close() throws IOException {
        server.stop(true);
    }
}
