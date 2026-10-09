package com.cassandrastudio.engine.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.cassandrastudio.engine.jmx.DefaultJmxAccess;
import com.cassandrastudio.engine.jmx.JmxAccess.JmxSession;
import com.cassandrastudio.engine.jmx.JmxAccess.JmxUnavailableException;
import com.cassandrastudio.engine.jmx.NodeEndpoint;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.Environment;
import com.cassandrastudio.engine.model.ConnectionConfig.Jmx;
import com.cassandrastudio.engine.model.ConnectionConfig.JmxMethod;
import com.cassandrastudio.engine.model.ConnectionConfig.SecretKeys;
import com.cassandrastudio.engine.model.ConnectionConfig.Ssh;
import com.cassandrastudio.engine.model.ConnectionConfig.SshAuth;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * Node access against the running test-env (not Testcontainers): SSH tunnels to the 4.1 nodes
 * (stock LOCAL_JMX=yes, so every node advertises 127.0.0.1:7199), the bastion jump host, direct
 * JMX to 3.11, and clear errors. Start it with
 * {@code test-env/make-certs.sh && PROFILES="secure jmx" test-env/wait-ready.sh}; skipped when
 * the SSH ports are not up. Overrides: STUDIO_IT_SSH_HOST (127.0.0.1), STUDIO_IT_SSH_PORTS
 * (2201,2202,2203), STUDIO_IT_BASTION_PORT (2200), STUDIO_IT_DIRECT_JMX (127.0.0.1:27199),
 * STUDIO_IT_SSH_DIR (../test-env/ssh: id_test and known_hosts).
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JmxAccessIntegrationTest {
    private static final ObjectName STORAGE = name("org.apache.cassandra.db:type=StorageService");
    private static final ObjectName MEMORY = name("java.lang:type=Memory");
    private static final ObjectName READ_LATENCY =
            name("org.apache.cassandra.metrics:type=ClientRequest,scope=Read,name=Latency");

    private final String host = env("STUDIO_IT_SSH_HOST", "127.0.0.1");
    private final List<Integer> nodePorts = List.of(env("STUDIO_IT_SSH_PORTS", "2201,2202,2203").split(","))
            .stream().map(String::trim).map(Integer::parseInt).toList();
    private final int bastionPort = Integer.parseInt(env("STUDIO_IT_BASTION_PORT", "2200"));
    private final String direct = env("STUDIO_IT_DIRECT_JMX", "127.0.0.1:27199");
    private final Path sshDir = Path.of(env("STUDIO_IT_SSH_DIR", "../test-env/ssh"));
    private DefaultJmxAccess access;

    private static String env(String k, String dflt) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? dflt : v;
    }

    private static ObjectName name(String n) {
        try {
            return new ObjectName(n);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static boolean listening(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @BeforeAll
    void setUp() {
        assumeTrue(listening(host, nodePorts.get(0)) && Files.exists(sshDir.resolve("id_test")),
                "test-env with --profile jmx is not running (test-env/make-certs.sh && PROFILES=\"secure jmx\" "
                        + "test-env/wait-ready.sh)");
        access = new DefaultJmxAccess(Duration.ofSeconds(10), Duration.ofSeconds(30));
    }

    @AfterAll
    void tearDown() {
        if (access != null) access.close();
    }

    private static ConnectionConfig cfg(String id, Jmx jmx, Ssh ssh) {
        return new ConnectionConfig(id, null, id, Environment.DEV, null, false, List.of(), null, null, null, null, null,
                null, null, jmx, ssh, null, null, null);
    }

    private Ssh keyAuth(int port, String jumpHost, Integer jumpPort) {
        return new Ssh("studio", port, SshAuth.KEY, sshDir.resolve("id_test").toString(), jumpHost, jumpPort, null, true,
                sshDir.resolve("known_hosts").toString());
    }

    private static ConnectionConfig tunnel(String id, Ssh ssh) {
        return cfg(id, new Jmx(JmxMethod.SSH_TUNNEL, 7199, null, false, null, null), ssh);
    }

    private static NodeEndpoint node(String address) {
        return new NodeEndpoint(null, address, null, null, null);
    }

    private static String hostId(JmxSession s) throws Exception {
        return (String) s.mbeans().getAttribute(STORAGE, "LocalHostId");
    }

    /** The reads Track B's poller relies on, from any version. */
    private static void readsCoreMetrics(MBeanServerConnection m, String versionPrefix) throws Exception {
        assertThat((String) m.getAttribute(STORAGE, "ReleaseVersion")).startsWith(versionPrefix);
        CompositeData heap = (CompositeData) m.getAttribute(MEMORY, "HeapMemoryUsage");
        assertThat((Long) heap.get("used")).isPositive();
        assertThat((Long) m.getAttribute(READ_LATENCY, "Count")).isNotNegative();
    }

    @Test
    void tunnelsToSeveralNodesConcurrentlyNeverMixThemUp() throws Exception {
        // Every node advertises 127.0.0.1:7199; each session must still reach its own node.
        List<ConnectionConfig> cfgs = new ArrayList<>();
        for (int p : nodePorts) cfgs.add(tunnel("ssh-" + p, keyAuth(p, null, null)));
        ExecutorService pool = Executors.newFixedThreadPool(cfgs.size() * 2);
        Map<Integer, String> firstSeen = new ConcurrentHashMap<>();
        try {
            List<Future<?>> work = new ArrayList<>();
            for (int round = 0; round < 10; round++) {
                for (int i = 0; i < cfgs.size(); i++) {
                    int idx = i;
                    Callable<Void> read = () -> {
                        String id = hostId(access.session(cfgs.get(idx), Map.of(), node(host)));
                        String first = firstSeen.putIfAbsent(idx, id);
                        assertThat(id).as("node %s", idx).isEqualTo(first == null ? id : first);
                        return null;
                    };
                    work.add(pool.submit(read));
                }
            }
            for (Future<?> f : work) f.get();
        } finally {
            pool.shutdown();
        }
        assertThat(new HashSet<>(firstSeen.values())).hasSize(cfgs.size());
        // ... and each id is that node's own: the seed's HostIdMap knows them all, once each.
        @SuppressWarnings("unchecked")
        Map<String, String> ring = (Map<String, String>) access.session(cfgs.get(0), Map.of(), node(host)).mbeans()
                .getAttribute(STORAGE, "HostIdMap");
        assertThat(ring.values()).containsAll(firstSeen.values());
        JmxSession s = access.session(cfgs.get(0), Map.of(), node(host));
        readsCoreMetrics(s.mbeans(), "4.1");
        assertThat(s.route()).isEqualTo("ssh tunnel -> " + host + ":7199 (ssh port " + nodePorts.get(0) + ")");
    }

    @Test
    void jumpHostReachesNodesByTheirInternalNames() throws Exception {
        ConnectionConfig c = tunnel("bastion", keyAuth(2222, host, bastionPort));
        Set<String> ids = new HashSet<>();
        for (String n : List.of("east1", "east2", "west1")) {
            JmxSession s = access.session(c, Map.of(), node(n));
            ids.add(hostId(s));
            assertThat(s.route()).isEqualTo("ssh tunnel via " + host + ":" + bastionPort + " -> " + n + ":7199 (ssh port 2222)");
        }
        assertThat(ids).hasSize(3);
    }

    @Test
    void passwordAuthentication() throws Exception {
        Ssh pw = new Ssh("studio", nodePorts.get(1), SshAuth.PASSWORD, null, null, null, null, true,
                sshDir.resolve("known_hosts").toString());
        JmxSession s = access.session(tunnel("ssh-pw", pw), Map.of(SecretKeys.SSH_PASSWORD, "studio-test"), node(host));
        readsCoreMetrics(s.mbeans(), "4.1");
    }

    @Test
    void directJmxToCassandra311() throws Exception {
        String[] hp = direct.split(":");
        ConnectionConfig c = cfg("direct-311", new Jmx(JmxMethod.DIRECT, Integer.parseInt(hp[1]), null, false, null, null),
                null);
        assumeTrue(listening(hp[0], Integer.parseInt(hp[1])), "legacy311 remote JMX is not published on " + direct);
        JmxSession s = access.session(c, Map.of(), node(hp[0]));
        readsCoreMetrics(s.mbeans(), "3.11");
        assertThat(s.route()).isEqualTo("direct " + direct);
    }

    @Test
    void clearErrors(@TempDir Path tmp) throws Exception {
        Path wrongKey = tmp.resolve("wrong_key");
        try (OutputStream out = Files.newOutputStream(wrongKey)) {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(
                    KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256), "wrong", null, out);
        }
        int p = nodePorts.get(0);
        Ssh bad = new Ssh("studio", p, SshAuth.KEY, wrongKey.toString(), null, null, null, true,
                sshDir.resolve("known_hosts").toString());
        assertThatThrownBy(() -> access.session(tunnel("wrong-key", bad), Map.of(), node(host)))
                .isInstanceOf(JmxUnavailableException.class)
                .hasMessage("SSH auth failed for user studio@" + host + ":" + p + " (key authentication)");

        ConnectionConfig wrongPort = cfg("wrong-jmx-port", new Jmx(JmxMethod.SSH_TUNNEL, 7299, null, false, null, null),
                keyAuth(p, null, null));
        assertThatThrownBy(() -> access.session(wrongPort, Map.of(), node(host)))
                .hasMessageStartingWith("JMX not listening on " + host + ":7299");

        Path unknown = Files.writeString(tmp.resolve("known_hosts"), "");
        Ssh strict = new Ssh("studio", p, SshAuth.KEY, sshDir.resolve("id_test").toString(), null, null, null, true,
                unknown.toString());
        assertThatThrownBy(() -> access.session(tunnel("unknown-host", strict), Map.of(), node(host)))
                .hasMessageStartingWith("host key for [" + host + "]:" + p + " not in known_hosts");
    }
}
