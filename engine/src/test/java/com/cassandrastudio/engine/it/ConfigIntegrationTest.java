package com.cassandrastudio.engine.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.config.ConfigModel;
import com.cassandrastudio.engine.config.ConfigModel.DriftReport;
import com.cassandrastudio.engine.config.ConfigModel.DriftRow;
import com.cassandrastudio.engine.config.ConfigModel.NodeConfig;
import com.cassandrastudio.engine.config.ConfigModel.Setting;
import com.cassandrastudio.engine.config.ConfigService;
import com.cassandrastudio.engine.config.NodeCollector;
import com.cassandrastudio.engine.cql.ClusterService.NodeInfo;
import com.cassandrastudio.engine.jmx.NodeEndpoint;
import com.cassandrastudio.engine.jobs.Job;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.Environment;
import com.cassandrastudio.engine.model.ConnectionConfig.Jmx;
import com.cassandrastudio.engine.model.ConnectionConfig.JmxMethod;
import com.cassandrastudio.engine.model.ConnectionConfig.Ssh;
import com.cassandrastudio.engine.model.ConnectionConfig.SshAuth;
import com.cassandrastudio.engine.secrets.SecretStores;
import com.cassandrastudio.engine.store.Database;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Effective config and drift against the running test-env: acme-core (4.1, settings table on
 * each node, JVM over SSH-tunnelled JMX, OS over SSH) and legacy-311 (3.11, direct JMX, no SSH:
 * runtime settings only). Acceptance criterion 11: one node's compaction throughput is changed
 * over JMX, the drift report shows it, and the original value is restored. Skipped when the
 * test-env is not up. Overrides: STUDIO_IT_SSH_DIR (../test-env/ssh).
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigIntegrationTest {
    private static final ObjectName STORAGE = name("org.apache.cassandra.db:type=StorageService");
    private final Path sshDir = Path.of(env("STUDIO_IT_SSH_DIR", "../test-env/ssh"));
    private Engine engine;
    private ConfigService svc;

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
        assumeTrue(listening("127.0.0.1", 19042) && listening("10.231.42.11", 2222) && Files.exists(sshDir.resolve("id_test")),
                "test-env (acme-core with SSH sidecars) is not running");
        engine = new Engine(Database.inMemory(), SecretStores.inMemory(), "it");
        svc = new ConfigService(engine.db, engine.connections, engine.topology, engine.jobs, engine::secretsFor,
                id -> new NodeCollector(engine.jmx, engine.shell, () -> engine.sessions.session(id))::collect);
    }

    @AfterAll
    void tearDown() {
        if (svc != null) svc.close();
        if (engine != null) engine.close();
    }

    private String save(String name, String contact, String dc, Jmx jmx, Ssh ssh) {
        return engine.connections.save(new ConnectionConfig(null, null, name, Environment.DEV, null, false,
                List.of(contact), dc, null, null, null, null, null, null, jmx, ssh, List.of(), null, null), Map.of()).id();
    }

    private Map<String, Setting> byName(NodeConfig n) {
        return n.settings().stream().collect(Collectors.toMap(s -> s.category() + ":" + s.name(), s -> s, (a, b) -> a));
    }

    private void collect(String id) throws InterruptedException {
        Job j = svc.collect(id);
        Job done = engine.jobs.await(j.id(), 120_000);
        assertThat(done.state()).as(String.valueOf(done.error())).isEqualTo(Job.State.SUCCEEDED);
    }

    @Test
    void cassandra41DriftShowsOneChangedNode() throws Exception {
        Ssh ssh = new Ssh("studio", 2222, SshAuth.KEY, sshDir.resolve("id_test").toAbsolutePath().toString(), null, null,
                null, false, null);
        String id = save("acme-core", "127.0.0.1:19042", "dc_east", new Jmx(JmxMethod.SSH_TUNNEL, 7199, null, false, null, null), ssh);
        collect(id);
        List<NodeConfig> nodes = svc.snapshot(id).nodes();
        assertThat(nodes).hasSize(3);
        for (NodeConfig n : nodes) {
            Map<String, Setting> s = byName(n);
            assertThat(n.sources().get(ConfigModel.YAML)).isEqualTo("system_views.settings");
            assertThat(s.get("yaml:cluster_name").value()).isEqualTo("acme-core");
            // pinned to the node: each node reports its own listen address
            assertThat(s.get("yaml:listen_address").value()).isEqualTo(n.address());
            assertThat(s.get("jvm:vm.MaxHeapSize")).isNotNull();
            assertThat(s).containsKey("os:vm.max_map_count");
        }
        DriftReport before = svc.drift(id, "cluster", true, false);
        assertThat(before.rows()).extracting(DriftRow::name).doesNotContain("compaction_throughput");

        NodeConfig target = nodes.get(0);
        ConnectionConfig cfg = engine.connections.get(id);
        MBeanServerConnection m = engine.jmx.session(cfg, engine.secretsFor(id), new NodeEndpoint(target.hostId(),
                target.address(), target.datacenter(), target.rack(), target.version())).mbeans();
        int original = ((Number) m.getAttribute(STORAGE, "CompactionThroughputMbPerSec")).intValue();
        int changed = original == 17 ? 19 : 17;
        try {
            m.setAttribute(STORAGE, new javax.management.Attribute("CompactionThroughputMbPerSec", changed));
            collect(id);
            DriftReport after = svc.drift(id, "cluster", true, false);
            DriftRow row = after.rows().stream().filter(r -> r.name().equals("compaction_throughput")).findFirst().orElseThrow();
            assertThat(row.differsInCluster()).isTrue();
            assertThat(row.values()).containsEntry(target.address(), changed + "MiB/s");
            assertThat(row.dcsDiffering()).contains(target.datacenter());
            assertThat(svc.drift(id, "cluster", true, true).hiera().enabled()).isEqualTo(svc.hieraSettings(id).enabled());
        } finally {
            m.setAttribute(STORAGE, new javax.management.Attribute("CompactionThroughputMbPerSec", original));
        }
        assertThat(((Number) m.getAttribute(STORAGE, "CompactionThroughputMbPerSec")).intValue()).isEqualTo(original);
    }

    @Test
    void cassandra311WithoutSshFallsBackToJmx() throws Exception {
        assumeTrue(listening("127.0.0.1", 29042) && listening("127.0.0.1", 27199), "legacy-311 is not running");
        String id = save("legacy-311", "127.0.0.1:29042", "dc1", new Jmx(JmxMethod.DIRECT, 27199, null, false, null, null), null);
        collect(id);
        NodeConfig n = svc.snapshot(id).nodes().get(0);
        Map<String, Setting> s = byName(n);
        assertThat(n.version()).startsWith("3.11");
        assertThat(n.sources().get(ConfigModel.YAML)).contains("JMX");
        assertThat(n.notices()).anyMatch(x -> x.contains("SSH is not configured"));
        assertThat(s.get("yaml:compaction_throughput").value()).endsWith("MiB/s");
        assertThat(s.get("yaml:read_request_timeout").value()).isEqualTo("5s");
        assertThat(s.get("yaml:partitioner").value()).isEqualTo("Murmur3Partitioner");
        assertThat(s.get("jvm:vm.MaxHeapSize")).isNotNull();
        assertThat(s.get("jvm:runtime.SpecVersion").value()).isEqualTo("1.8");
        assertThat(s.get("os:process.max_open_files")).isNotNull();
    }

    @Test
    void cassandra50OverBastion() throws Exception {
        assumeTrue(listening("127.0.0.1", 39042) && listening("127.0.0.1", 2200), "secure-50 is not running");
        // no TLS client setup here: the 5.0 node needs TLS + auth for CQL; covered by the JMX/SSH part only
        Ssh ssh = new Ssh("studio", 2222, SshAuth.KEY, sshDir.resolve("id_test").toAbsolutePath().toString(), "127.0.0.1",
                2200, "studio", false, null);
        ConnectionConfig cfg = engine.connections.get(save("secure-50-jmx", "127.0.0.1:39042", "dc1",
                new Jmx(JmxMethod.SSH_TUNNEL, 7199, null, false, null, null), ssh));
        NodeConfig n = new NodeCollector(engine.jmx, engine.shell, () -> {
            throw new IllegalStateException("no CQL in this test");
        }).collect(cfg, Map.of(), new NodeInfo(null, "10.231.42.31", 9042, "dc1", "rack1", "5.0.0", "UP", 16, null, 0));
        Map<String, Setting> s = byName(n);
        assertThat(s.get("jvm:vm.UseG1GC").value()).isEqualTo("true");
        assertThat(s.get("jvm:runtime.SpecVersion").value()).isEqualTo("17");
        assertThat(s).containsKey("os:thp.enabled");
        assertThat(n.notices()).anyMatch(x -> x.contains("system_views.settings"));
    }
}
