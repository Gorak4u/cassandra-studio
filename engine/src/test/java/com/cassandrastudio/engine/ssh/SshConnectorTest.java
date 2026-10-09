package com.cassandrastudio.engine.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cassandrastudio.engine.model.ConnectionConfig.SecretKeys;
import com.cassandrastudio.engine.model.ConnectionConfig.Ssh;
import com.cassandrastudio.engine.model.ConnectionConfig.SshAuth;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.apache.sshd.agent.common.AbstractAgentClient;
import org.apache.sshd.agent.local.AgentImpl;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/** Agent, encrypted key and password authentication, and forwarding, against an in-process sshd. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SshConnectorTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @TempDir
    static Path dir;
    private TestSshServer sshd;
    private Path knownHosts;

    @BeforeAll
    void setUp() throws Exception {
        sshd = new TestSshServer();
        knownHosts = sshd.writeKnownHosts(dir);
    }

    @AfterAll
    void tearDown() throws IOException {
        sshd.close();
    }

    private Ssh ssh(SshAuth auth, String keyPath) {
        return new Ssh(TestSshServer.USER, sshd.port(), auth, keyPath, null, null, null, true, knownHosts.toString());
    }

    /** A minimal ssh-agent on a Unix socket, backed by MINA's in-memory agent. */
    private static Thread fakeAgent(Path socket, AgentImpl keys) throws IOException {
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        Thread t = new Thread(() -> {
            try (server) {
                while (true) {
                    SocketChannel ch = server.accept();
                    AbstractAgentClient client = new AbstractAgentClient(keys) {
                        @Override
                        protected void reply(Buffer buf) throws IOException {
                            ch.write(ByteBuffer.wrap(buf.array(), buf.rpos(), buf.available()));
                        }
                    };
                    Thread serve = new Thread(() -> {
                        ByteBuffer in = ByteBuffer.allocate(16384);
                        try (ch) {
                            while (ch.read(in.clear()) > 0) {
                                client.messageReceived(new ByteArrayBuffer(in.array(), 0, in.position()));
                            }
                        } catch (IOException ignored) {
                            // client went away
                        }
                    });
                    serve.setDaemon(true);
                    serve.start();
                }
            } catch (IOException ignored) {
                // closed
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Test
    void agentAuthentication() throws Exception {
        Path sock = dir.resolve("agent.sock");
        AgentImpl keys = new AgentImpl();
        keys.addIdentity(sshd.clientKey(), "test");
        fakeAgent(sock, keys);
        try (SshConnector c = new SshConnector(sock::toString);
             SshConnection s = c.connect(ssh(SshAuth.AGENT, null), Map.of(), "127.0.0.1", TIMEOUT)) {
            assertThat(s.isOpen()).isTrue();
        }
        AgentImpl empty = new AgentImpl();
        Path sock2 = dir.resolve("empty.sock");
        fakeAgent(sock2, empty);
        try (SshConnector c = new SshConnector(sock2::toString)) {
            assertThatThrownBy(() -> c.connect(ssh(SshAuth.AGENT, null), Map.of(), "127.0.0.1", TIMEOUT))
                    .hasMessage("ssh-agent has no keys: ssh-add your key first");
        }
        try (SshConnector c = new SshConnector(() -> {
            throw new IOException("no ssh-agent: SSH_AUTH_SOCK is not set");
        })) {
            assertThatThrownBy(() -> c.connect(ssh(SshAuth.AGENT, null), Map.of(), "127.0.0.1", TIMEOUT))
                    .hasMessageStartingWith("no ssh-agent");
        }
    }

    @Test
    void encryptedKeyNeedsTheRightPassphrase() throws Exception {
        Path key = dir.resolve("id_encrypted");
        OpenSSHKeyEncryptionContext enc = new OpenSSHKeyEncryptionContext();
        enc.setPassword("open sesame");
        enc.setCipherName("AES");
        enc.setCipherMode("CTR");
        enc.setCipherType("256");
        try (OutputStream out = Files.newOutputStream(key)) {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(sshd.clientKey(), "enc", enc, out);
        }
        try (SshConnector c = new SshConnector()) {
            Ssh cfg = ssh(SshAuth.KEY, key.toString());
            try (SshConnection s = c.connect(cfg, Map.of(SecretKeys.SSH_PASSPHRASE, "open sesame"), "127.0.0.1", TIMEOUT)) {
                assertThat(s.isOpen()).isTrue();
            }
            assertThatThrownBy(() -> c.connect(cfg, Map.of(SecretKeys.SSH_PASSPHRASE, "wrong"), "127.0.0.1", TIMEOUT))
                    .hasMessage("cannot load SSH key file " + key + " (wrong passphrase?)");
            assertThatThrownBy(() -> c.connect(cfg, Map.of(), "127.0.0.1", TIMEOUT))
                    .hasMessageContaining("set its passphrase");
            assertThatThrownBy(() -> c.connect(ssh(SshAuth.KEY, dir.resolve("missing").toString()), Map.of(),
                    "127.0.0.1", TIMEOUT)).hasMessageStartingWith("cannot read SSH key file");
        }
    }

    /** Accepts connections until closed; answers each byte b with b + 1. */
    private static void incrementServer(ServerSocket server) {
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    Thread conn = new Thread(() -> {
                        try (s; InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream()) {
                            for (int b; (b = in.read()) >= 0; ) out.write(b + 1);
                        } catch (IOException ignored) {
                            // peer gone
                        }
                    });
                    conn.setDaemon(true);
                    conn.start();
                } catch (IOException ignored) {
                    // closed
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    @Test
    void passwordAuthAndForwarding() throws Exception {
        try (ServerSocket target = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
             SshConnector c = new SshConnector()) {
            incrementServer(target);
            try (SshConnection s = c.connect(ssh(SshAuth.PASSWORD, null),
                    Map.of(SecretKeys.SSH_PASSWORD, TestSshServer.PASSWORD), "127.0.0.1", TIMEOUT)) {
                assertThat(s.probe("127.0.0.1", target.getLocalPort(), TIMEOUT)).isNull();
                int local = s.forwardLocalPort("127.0.0.1", target.getLocalPort());
                try (Socket via = new Socket(InetAddress.getLoopbackAddress(), local)) {
                    via.setSoTimeout(5000);
                    via.getOutputStream().write(41);
                    assertThat(via.getInputStream().read()).isEqualTo(42);
                }
            }
            assertThatThrownBy(() -> c.connect(ssh(SshAuth.PASSWORD, null), Map.of(), "127.0.0.1", TIMEOUT))
                    .hasMessage("SSH password is not set for this connection");
            assertThatThrownBy(() -> c.connect(ssh(SshAuth.PASSWORD, null), Map.of(SecretKeys.SSH_PASSWORD, "x"),
                    "no-such-host.invalid", TIMEOUT)).hasMessage("unknown host no-such-host.invalid");
        }
    }

    @Test
    void execReturnsOutputAndReportsFailures() {
        try (SshConnector c = new SshConnector();
             SshConnection s = c.connect(ssh(SshAuth.PASSWORD, null),
                     Map.of(SecretKeys.SSH_PASSWORD, TestSshServer.PASSWORD), "127.0.0.1", TIMEOUT)) {
            assertThat(s.exec("echo hello", TIMEOUT)).isEqualTo("hello\n");
            assertThatThrownBy(() -> s.exec("ls /no/such/dir", TIMEOUT))
                    .isInstanceOf(SshAccessException.class)
                    .hasMessageContaining("exited with")
                    .hasMessageContaining("/no/such/dir");
            assertThatThrownBy(() -> s.exec("sleep 5", Duration.ofMillis(300)))
                    .isInstanceOf(SshAccessException.class)
                    .hasMessageContaining("did not finish");
        }
    }
}
