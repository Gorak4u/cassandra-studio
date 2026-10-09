package com.cassandrastudio.engine.jmx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cassandrastudio.engine.jmx.JmxAccess.ExporterSample;
import com.cassandrastudio.engine.jmx.JmxAccess.JmxSession;
import com.cassandrastudio.engine.jmx.JmxAccess.JmxUnavailableException;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.Environment;
import com.cassandrastudio.engine.model.ConnectionConfig.Jmx;
import com.cassandrastudio.engine.model.ConnectionConfig.JmxMethod;
import com.cassandrastudio.engine.model.ConnectionConfig.SecretKeys;
import com.cassandrastudio.engine.model.ConnectionConfig.Ssh;
import com.cassandrastudio.engine.model.ConnectionConfig.SshAuth;
import com.cassandrastudio.engine.ssh.TestSshServer;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/** SSH tunnels, jump hosts, direct JMX, errors and back-off against in-process SSH and JMX servers. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DefaultJmxAccessTest {
    private static final Map<String, String> SECRETS = Map.of(SecretKeys.SSH_PASSWORD, TestSshServer.PASSWORD);

    @TempDir
    static Path dir;
    private TestSshServer sshd;
    private TestJmxServer nodeA;
    private TestJmxServer nodeB;
    private Path knownHosts;
    private DefaultJmxAccess access;

    @BeforeAll
    void setUp() throws Exception {
        sshd = new TestSshServer();
        nodeA = new TestJmxServer("A");
        nodeB = new TestJmxServer("B");
        knownHosts = sshd.writeKnownHosts(dir);
        access = new DefaultJmxAccess(Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    @AfterAll
    void tearDown() throws Exception {
        access.close();
        nodeA.close();
        nodeB.close();
        sshd.close();
    }

    static ConnectionConfig cfg(String id, Jmx jmx, Ssh ssh) {
        return new ConnectionConfig(id, null, id, Environment.DEV, null, false, List.of(), null, null, null, null, null,
                null, null, jmx, ssh, null, null, null);
    }

    private Ssh ssh(SshAuth auth, String keyPath, String jumpHost, boolean strict, Path known) {
        return new Ssh(TestSshServer.USER, sshd.port(), auth, keyPath, jumpHost, jumpHost == null ? null : sshd.port(),
                null, strict, known == null ? null : known.toString());
    }

    private ConnectionConfig tunnel(String id, int jmxPort) {
        return cfg(id, new Jmx(JmxMethod.SSH_TUNNEL, jmxPort, null, false, null, null),
                ssh(SshAuth.PASSWORD, null, null, true, knownHosts));
    }

    private static NodeEndpoint node(String hostId) {
        return new NodeEndpoint(hostId, "127.0.0.1", "dc1", "r1", "4.1.0");
    }

    private static String id(JmxSession s) throws Exception {
        return (String) s.mbeans().getAttribute(TestJmxServer.NODE, "Id");
    }

    @Test
    void tunnelledNodesConcurrentlyEachReadTheirOwnMBeans() throws Exception {
        ConnectionConfig a = tunnel("tun-a", nodeA.port);
        ConnectionConfig b = tunnel("tun-b", nodeB.port);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                boolean even = i % 2 == 0;
                Callable<String> read = () -> (even ? "A=" : "B=")
                        + id(access.session(even ? a : b, SECRETS, node(even ? "ha" : "hb")));
                results.add(pool.submit(read));
            }
            for (Future<String> f : results) {
                String r = f.get();
                assertThat(r.substring(2)).isEqualTo(r.substring(0, 1));
            }
        } finally {
            pool.shutdown();
        }
        JmxSession s = access.session(a, SECRETS, node("ha"));
        assertThat(s).isSameAs(access.session(a, SECRETS, node("ha")));
        assertThat(s.route()).isEqualTo("ssh tunnel -> 127.0.0.1:" + nodeA.port + " (ssh port " + sshd.port() + ")");
    }

    @Test
    void serverObjectsOnAnotherPortGetTheirOwnForward() throws Exception {
        // Stock LOCAL_JMX=yes: registry on 7199, RMI server object on a random port.
        try (TestJmxServer split = new TestJmxServer("S", true, null)) {
            assertThat(split.objectPort).isNotEqualTo(split.port);
            assertThat(id(access.session(tunnel("split", split.port), SECRETS, node("hs")))).isEqualTo("S");
        }
    }

    @Test
    void jumpHostAndKeyFile() throws Exception {
        Path key = TestSshServer.writeKey(sshd.clientKey(), dir.resolve("id_test"));
        ConnectionConfig c = cfg("jump", new Jmx(JmxMethod.SSH_TUNNEL, nodeB.port, null, false, null, null),
                ssh(SshAuth.KEY, key.toString(), "127.0.0.1", true, knownHosts));
        JmxSession s = access.session(c, Map.of(), node("hb"));
        assertThat(id(s)).isEqualTo("B");
        assertThat(s.route()).startsWith("ssh tunnel via 127.0.0.1:" + sshd.port() + " -> 127.0.0.1:" + nodeB.port);
    }

    @Test
    void directJmx() throws Exception {
        ConnectionConfig c = cfg("direct", new Jmx(JmxMethod.DIRECT, nodeA.port, null, false, null, null), null);
        JmxSession s = access.session(c, Map.of(), node("ha"));
        assertThat(id(s)).isEqualTo("A");
        assertThat(s.route()).isEqualTo("direct 127.0.0.1:" + nodeA.port);
    }

    @Test
    void closeConnectionReleasesSessionsAndReconnectsLater() throws Exception {
        ConnectionConfig c = tunnel("closing", nodeA.port);
        JmxSession first = access.session(c, SECRETS, node("ha"));
        access.closeConnection("closing");
        assertThatThrownBy(() -> id(first)).isInstanceOf(Exception.class);
        JmxSession second = access.session(c, SECRETS, node("ha"));
        assertThat(second).isNotSameAs(first);
        assertThat(id(second)).isEqualTo("A");
    }

    @Test
    void wrongPasswordIsReportedThenBackedOff() {
        ConnectionConfig c = tunnel("badpw", nodeA.port);
        Map<String, String> wrong = Map.of(SecretKeys.SSH_PASSWORD, "nope");
        assertThatThrownBy(() -> access.session(c, wrong, node("ha")))
                .isInstanceOf(JmxUnavailableException.class)
                .hasMessageStartingWith("SSH auth failed for user studio@127.0.0.1:" + sshd.port())
                .hasMessageNotContaining("nope");
        assertThatThrownBy(() -> access.session(c, wrong, node("ha")))
                .hasMessageContaining("SSH auth failed").hasMessageContaining("next retry in");
        // A corrected secret is a new configuration: no waiting for the old back-off.
        assertThat(access.session(c, SECRETS, node("ha")).route()).startsWith("ssh tunnel");
    }

    @Test
    void unknownHostKeyIsRefusedWhenStrictAndAcceptedOtherwise() throws Exception {
        Path empty = Files.writeString(dir.resolve("empty_known_hosts"), "");
        ConnectionConfig strict = cfg("strict", new Jmx(JmxMethod.SSH_TUNNEL, nodeA.port, null, false, null, null),
                ssh(SshAuth.PASSWORD, null, null, true, empty));
        assertThatThrownBy(() -> access.session(strict, SECRETS, node("ha")))
                .hasMessageContaining("host key for [127.0.0.1]:" + sshd.port() + " not in known_hosts");
        ConnectionConfig lax = cfg("lax", new Jmx(JmxMethod.SSH_TUNNEL, nodeA.port, null, false, null, null),
                ssh(SshAuth.PASSWORD, null, null, false, empty));
        assertThat(id(access.session(lax, SECRETS, node("ha")))).isEqualTo("A");
    }

    @Test
    void closedPortsGiveClearReasons() throws Exception {
        int closed = TestJmxServer.freePort();
        assertThatThrownBy(() -> access.session(tunnel("nojmx", closed), SECRETS, node("ha")))
                .hasMessageStartingWith("JMX not listening on 127.0.0.1:" + closed);
        ConnectionConfig noSsh = cfg("nossh", new Jmx(JmxMethod.SSH_TUNNEL, nodeA.port, null, false, null, null),
                new Ssh(TestSshServer.USER, closed, SshAuth.PASSWORD, null, null, null, null, true, knownHosts.toString()));
        assertThatThrownBy(() -> access.session(noSsh, SECRETS, node("ha")))
                .hasMessage("SSH connection to 127.0.0.1:" + closed + " refused");
        ConnectionConfig direct = cfg("nodirect", new Jmx(JmxMethod.DIRECT, closed, null, false, null, null), null);
        assertThatThrownBy(() -> access.session(direct, Map.of(), node("ha")))
                .hasMessage("JMX not listening on 127.0.0.1:" + closed);
    }

    @Test
    void backOffFailsFastUntilItExpiresAndThenDoubles() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        int closed = TestJmxServer.freePort();
        ConnectionConfig c = cfg("backoff", new Jmx(JmxMethod.DIRECT, closed, null, false, null, null), null);
        try (DefaultJmxAccess a = new DefaultJmxAccess(Duration.ofSeconds(2), Duration.ofSeconds(5), now::get)) {
            assertThatThrownBy(() -> a.session(c, Map.of(), node("x"))).hasMessage("JMX not listening on 127.0.0.1:" + closed);
            assertThatThrownBy(() -> a.session(c, Map.of(), node("x"))).hasMessageEndingWith("(next retry in 2 s)");
            now.addAndGet(Duration.ofSeconds(2).toNanos());
            assertThatThrownBy(() -> a.session(c, Map.of(), node("x"))).hasMessage("JMX not listening on 127.0.0.1:" + closed);
            assertThatThrownBy(() -> a.session(c, Map.of(), node("x"))).hasMessageEndingWith("(next retry in 4 s)");
        }
    }

    @Test
    void methodsWithoutJmxHaveNoSession() {
        for (JmxMethod m : List.of(JmxMethod.EXPORTER, JmxMethod.SIDECAR, JmxMethod.NONE)) {
            ConnectionConfig c = cfg("m", new Jmx(m, null, null, false, null, null), null);
            assertThatThrownBy(() -> access.session(c, Map.of(), node("x"))).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void routes() {
        NodeEndpoint n = new NodeEndpoint("h", "10.0.0.5", "dc1", "r1", "4.1");
        Ssh viaBastion = new Ssh("u", 22, SshAuth.AGENT, null, "bastion", 22, null, true, null);
        assertThat(DefaultJmxAccess.route(cfg("r", Jmx.DEFAULT, viaBastion), n))
                .isEqualTo("ssh tunnel via bastion:22 -> 10.0.0.5:7199");
        assertThat(DefaultJmxAccess.route(cfg("r", Jmx.DEFAULT, Ssh.DEFAULT), n)).isEqualTo("ssh tunnel -> 10.0.0.5:7199");
        assertThat(DefaultJmxAccess.route(cfg("r", new Jmx(JmxMethod.DIRECT, 7199, null, true, null, null), null), n))
                .isEqualTo("direct 10.0.0.5:7199 (ssl)");
        assertThat(DefaultJmxAccess.route(cfg("r", new Jmx(JmxMethod.EXPORTER, null, null, false, 7071, null), null),
                new NodeEndpoint("h", "fd00::5", null, null, null))).isEqualTo("jmx_exporter http://[fd00::5]:7071/metrics");
    }

    @Test
    void scrapesJmxExporter() throws Exception {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/metrics", ex -> {
            byte[] body = """
                    # HELP cassandra_load_bytes Load
                    # TYPE cassandra_load_bytes gauge
                    cassandra_load_bytes{node="a b"} 1234.0
                    jvm_up 1
                    """.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        http.start();
        try {
            int port = http.getAddress().getPort();
            ConnectionConfig c = cfg("exp", new Jmx(JmxMethod.EXPORTER, null, null, false, port, null), null);
            List<ExporterSample> samples = access.scrapeExporter(c, node("x"));
            assertThat(samples).containsExactly(
                    new ExporterSample("cassandra_load_bytes", Map.of("node", "a b"), 1234.0),
                    new ExporterSample("jvm_up", Map.of(), 1.0));
            http.removeContext("/metrics");
            assertThatThrownBy(() -> access.scrapeExporter(c, node("x")))
                    .hasMessage("jmx_exporter on 127.0.0.1:" + port + " answered HTTP 404");
        } finally {
            http.stop(0);
        }
        int closed = TestJmxServer.freePort();
        ConnectionConfig c = cfg("exp", new Jmx(JmxMethod.EXPORTER, null, null, false, closed, null), null);
        assertThatThrownBy(() -> access.scrapeExporter(c, node("x")))
                .hasMessage("jmx_exporter not listening on 127.0.0.1:" + closed);
    }
}
