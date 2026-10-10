package com.cassandrastudio.engine.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cassandrastudio.engine.model.ConnectionConfig.ProxyType;
import com.cassandrastudio.engine.model.ConnectionConfig.SecretKeys;
import com.cassandrastudio.engine.model.ConnectionConfig.Ssh;
import com.cassandrastudio.engine.model.ConnectionConfig.SshAuth;
import com.cassandrastudio.engine.model.ConnectionConfig.SshProxy;
import com.cassandrastudio.engine.ssh.SshAccessException;
import com.cassandrastudio.engine.ssh.SshConnection;
import com.cassandrastudio.engine.ssh.SshConnector;
import com.cassandrastudio.engine.ssh.TestSshServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** HTTP CONNECT and SOCKS5 tunnels against an in-process proxy, and SSH (MINA) through them. */
class ProxyTunnelTest {
    private static final Duration T = Duration.ofSeconds(5);
    private ServerSocket echo;

    @BeforeEach
    void echoServer() throws IOException {
        echo = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            while (!echo.isClosed()) {
                try {
                    Socket s = echo.accept();
                    Thread.ofVirtual().start(() -> {
                        try (s) {
                            s.getInputStream().transferTo(s.getOutputStream());
                        } catch (IOException ignored) {
                            // closed
                        }
                    });
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    @AfterEach
    void close() throws IOException {
        echo.close();
    }

    private static String roundTrip(Socket s, String text) throws IOException {
        s.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
        s.getOutputStream().flush();
        return new String(s.getInputStream().readNBytes(text.length()), StandardCharsets.UTF_8);
    }

    @Test
    void httpConnectWithAndWithoutCredentials() throws Exception {
        try (TestProxy proxy = new TestProxy("alice", "s3cret", h -> h.equals("echo.far.side") ? "127.0.0.1" : h)) {
            ProxyEndpoint ok = new ProxyEndpoint(ProxyType.HTTP, "127.0.0.1", proxy.port(), "alice", "s3cret");
            try (Socket s = ProxyTunnel.open(ok, "echo.far.side", echo.getLocalPort(), T)) {
                assertThat(roundTrip(s, "hello through CONNECT")).isEqualTo("hello through CONNECT");
            }
            assertThat(proxy.tunnels).containsExactly("echo.far.side:" + echo.getLocalPort());

            ProxyEndpoint wrong = new ProxyEndpoint(ProxyType.HTTP, "127.0.0.1", proxy.port(), "alice", "nope");
            assertThatThrownBy(() -> ProxyTunnel.open(wrong, "127.0.0.1", echo.getLocalPort(), T))
                    .hasMessageContaining("requires authentication").hasMessageContaining("alice").hasMessageNotContaining("nope");
            ProxyEndpoint none = new ProxyEndpoint(ProxyType.HTTP, "127.0.0.1", proxy.port(), null, null);
            assertThatThrownBy(() -> ProxyTunnel.open(none, "127.0.0.1", echo.getLocalPort(), T))
                    .hasMessageContaining("set the proxy user and password");
        }
    }

    @Test
    void socks5WithAndWithoutCredentials() throws Exception {
        try (TestProxy open = new TestProxy(null, null, h -> h.equals("echo.far.side") ? "127.0.0.1" : h)) {
            ProxyEndpoint p = new ProxyEndpoint(ProxyType.SOCKS5, "127.0.0.1", open.port(), null, null);
            try (Socket s = ProxyTunnel.open(p, "echo.far.side", echo.getLocalPort(), T)) {
                assertThat(roundTrip(s, "socks!")).isEqualTo("socks!");
            }
        }
        try (TestProxy auth = new TestProxy("bob", "pw", h -> h)) {
            ProxyEndpoint p = new ProxyEndpoint(ProxyType.SOCKS5, "127.0.0.1", auth.port(), "bob", "pw");
            try (Socket s = ProxyTunnel.open(p, "127.0.0.1", echo.getLocalPort(), T)) {
                assertThat(roundTrip(s, "auth socks")).isEqualTo("auth socks");
            }
            ProxyEndpoint bad = new ProxyEndpoint(ProxyType.SOCKS5, "127.0.0.1", auth.port(), "bob", "x");
            assertThatThrownBy(() -> ProxyTunnel.open(bad, "127.0.0.1", echo.getLocalPort(), T)).hasMessageContaining("rejected user bob");
            ProxyEndpoint anon = new ProxyEndpoint(ProxyType.SOCKS5, "127.0.0.1", auth.port(), null, null);
            assertThatThrownBy(() -> ProxyTunnel.open(anon, "127.0.0.1", echo.getLocalPort(), T)).hasMessageContaining("user and password");
        }
    }

    @Test
    void clearErrorsForDeadProxyAndUnreachableTarget() throws Exception {
        int dead;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            dead = s.getLocalPort();
        }
        ProxyEndpoint gone = new ProxyEndpoint(ProxyType.HTTP, "127.0.0.1", dead, null, null);
        assertThatThrownBy(() -> ProxyTunnel.open(gone, "x", 1, T)).hasMessageContaining("cannot reach proxy 127.0.0.1:" + dead);
        try (TestProxy proxy = new TestProxy()) {
            ProxyEndpoint p = new ProxyEndpoint(ProxyType.HTTP, "127.0.0.1", proxy.port(), null, null);
            assertThatThrownBy(() -> ProxyTunnel.open(p, "127.0.0.1", dead, T)).hasMessageContaining("refused the tunnel")
                    .hasMessageContaining("502");
            ProxyEndpoint s = new ProxyEndpoint(ProxyType.SOCKS5, "127.0.0.1", proxy.port(), null, null);
            assertThatThrownBy(() -> ProxyTunnel.open(s, "127.0.0.1", dead, T)).hasMessageContaining("connection refused");
        }
    }

    @Test
    void sshThroughHttpAndSocksProxies() throws Exception {
        try (TestSshServer sshd = new TestSshServer();
             TestProxy proxy = new TestProxy("alice", "s3cret", h -> h.equals("node.behind.proxy") ? "127.0.0.1" : h);
             SshConnector connector = new SshConnector()) {
            for (ProxyType type : ProxyType.values()) {
                Ssh ssh = new Ssh(TestSshServer.USER, sshd.port(), SshAuth.PASSWORD, null, null, null, null, false, null,
                        new SshProxy(type, "127.0.0.1", proxy.port(), "alice"));
                Map<String, String> secrets = Map.of(SecretKeys.SSH_PASSWORD, TestSshServer.PASSWORD,
                        SecretKeys.SSH_PROXY_PASSWORD, "s3cret");
                // "node.behind.proxy" does not resolve here: only the proxy can reach it.
                try (SshConnection c = connector.connect(ssh, secrets, "node.behind.proxy", T)) {
                    assertThat(c.isOpen()).isTrue();
                    assertThat(c.exec("echo proxied-" + type, T)).contains("proxied-" + type);
                }
            }
            assertThat(proxy.tunnels).hasSize(2).allMatch(t -> t.equals("node.behind.proxy:" + sshd.port()));

            Ssh badPw = new Ssh(TestSshServer.USER, sshd.port(), SshAuth.PASSWORD, null, null, null, null, false, null,
                    new SshProxy(ProxyType.HTTP, "127.0.0.1", proxy.port(), "alice"));
            assertThatThrownBy(() -> connector.connect(badPw, Map.of(SecretKeys.SSH_PASSWORD, TestSshServer.PASSWORD,
                    SecretKeys.SSH_PROXY_PASSWORD, "wrong"), "node.behind.proxy", T))
                    .isInstanceOf(SshAccessException.class)
                    .hasMessageContaining("proxy 127.0.0.1:" + proxy.port() + " requires authentication");
        }
    }

    @Test
    void sshProxyReachesTheJumpHostFirst() throws Exception {
        try (TestSshServer sshd = new TestSshServer();
             TestProxy proxy = new TestProxy(null, null, h -> h.equals("bastion.example") ? "127.0.0.1" : h);
             SshConnector connector = new SshConnector()) {
            Ssh ssh = new Ssh(TestSshServer.USER, sshd.port(), SshAuth.PASSWORD, null, "bastion.example", sshd.port(), null,
                    false, null, new SshProxy(ProxyType.SOCKS5, "127.0.0.1", proxy.port(), null));
            try (SshConnection c = connector.connect(ssh, Map.of(SecretKeys.SSH_PASSWORD, TestSshServer.PASSWORD), "127.0.0.1", T)) {
                assertThat(c.isOpen()).isTrue();
            }
            assertThat(proxy.tunnels).containsExactly("bastion.example:" + sshd.port());
        }
    }
}
