package com.cassandrastudio.engine.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.cql.ClusterService.NodeInfo;
import com.cassandrastudio.engine.diag.DiagService;
import com.cassandrastudio.engine.diag.HotPartitions;
import com.cassandrastudio.engine.diag.LogWarnings;
import com.cassandrastudio.engine.diag.TableHistograms;
import com.cassandrastudio.engine.diag.ThreadDumps;
import com.cassandrastudio.engine.diag.ThreadTop;
import com.cassandrastudio.engine.jobs.Job;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.Environment;
import com.cassandrastudio.engine.model.ConnectionConfig.Jmx;
import com.cassandrastudio.engine.model.ConnectionConfig.JmxMethod;
import com.cassandrastudio.engine.model.ConnectionConfig.Ssh;
import com.cassandrastudio.engine.model.ConnectionConfig.SshAuth;
import com.cassandrastudio.engine.model.ConnectionConfig.Tls;
import com.cassandrastudio.engine.secrets.SecretStores;
import com.cassandrastudio.engine.store.Database;
import com.datastax.oss.driver.api.core.CqlSession;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Diagnostics against the running test-env (JVM-1/2, PRF-1/2): thread dump, series and compare,
 * top threads, table histograms and hot partitions on acme-core 4.1 (SSH-tunnelled JMX),
 * legacy-311 3.11 (direct JMX on 27199) and secure-50 5.0 (SSH tunnel through the bastion).
 * Writes only to keyspace t2_diag. Skipped when the test-env is not running. Overrides:
 * STUDIO_IT_SSH_DIR (../test-env/ssh), STUDIO_IT_CERT_DIR (../test-env/certs).
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiagIntegrationTest {
    private final Path sshDir = Path.of(env("STUDIO_IT_SSH_DIR", "../test-env/ssh"));
    private final Path certDir = Path.of(env("STUDIO_IT_CERT_DIR", "../test-env/certs"));
    private Engine engine;
    private DiagService diag;

    private static String env(String k, String dflt) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? dflt : v;
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
        assumeTrue(listening("127.0.0.1", 19042) && Files.exists(sshDir.resolve("id_test")), "test-env is not running");
        engine = new Engine(Database.inMemory(), SecretStores.inMemory(), "it");
        diag = new DiagService(engine.db, engine.connections, engine.jmx, engine.topology, engine.shell, engine.jobs,
                engine::secretsFor);
    }

    @AfterAll
    void tearDown() {
        if (diag != null) diag.close();
        if (engine != null) engine.close();
    }

    private Ssh ssh(String jumpHost, Integer jumpPort) {
        return new Ssh("studio", 2222, SshAuth.KEY, sshDir.resolve("id_test").toString(), jumpHost, jumpPort,
                jumpHost == null ? null : "studio", false, null);
    }

    private String save(String name, String contact, String dc, String user, Tls tls, Jmx jmx, Ssh ssh,
                        Map<String, String> secrets) {
        ConnectionConfig cfg = new ConnectionConfig(null, null, name, Environment.DEV, null, false, List.of(contact), dc,
                user, tls, null, "ONE", 30_000, null, jmx, ssh, null, null, null);
        return engine.connections.save(cfg, secrets).id();
    }

    private String acme() {
        return save("acme-core", "127.0.0.1:19042", "dc_east", null, null,
                new Jmx(JmxMethod.SSH_TUNNEL, 7199, null, false, null, null), ssh(null, null), Map.of());
    }

    private String legacy() {
        return save("legacy-311", "127.0.0.1:29042", "dc1", null, null,
                new Jmx(JmxMethod.DIRECT, 27199, null, false, null, null), null, Map.of());
    }

    private String secure() {
        return save("secure-50", "127.0.0.1:39042", null, "cassandra",
                new Tls(true, certDir.resolve("node.pem").toString(), "PEM", null, null, false),
                new Jmx(JmxMethod.SSH_TUNNEL, 7199, null, false, null, null), ssh("127.0.0.1", 2200),
                Map.of("password", "cassandra"));
    }

    private void schema(CqlSession s) {
        s.execute("CREATE KEYSPACE IF NOT EXISTS t2_diag WITH replication = {'class':'SimpleStrategy','replication_factor':1}");
        s.execute("CREATE TABLE IF NOT EXISTS t2_diag.events (pk int, ck int, v text, PRIMARY KEY (pk, ck))");
    }

    private void verifyThreads(String id, String node) throws Exception {
        ThreadDumps.ThreadDump d = diag.takeDump(id, node);
        assertThat(d.threadCount()).isGreaterThan(20);
        assertThat(d.groups()).isNotEmpty();
        assertThat(d.threads()).anyMatch(t -> t.name().startsWith("CompactionExecutor") || t.name().startsWith("ScheduledTasks")
                || t.name().contains("GossipStage") || t.name().contains("Native-Transport"));
        assertThat(ThreadDumps.jstack(d)).contains("java.lang.Thread.State: RUNNABLE").contains("\tat ");
        System.out.println(node + " dump: " + d.threadCount() + " threads " + d.byState() + " groups " + d.groups().size()
                + " jvm " + d.jvm());

        Job j = diag.series(id, node, 2, 1);
        Job done = engine.jobs.await(j.id(), 30_000);
        assertThat(done.state()).as(String.valueOf(done.error())).isEqualTo(Job.State.SUCCEEDED);
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) ((Map<String, Object>) done.result()).get("dumpIds");
        ThreadDumps.Comparison c = diag.compare(id, ids.get(0), ids.get(1));
        assertThat(c.intervalMs()).isGreaterThanOrEqualTo(900);
        System.out.println(node + " compare: +" + c.added().size() + " -" + c.removed().size() + " changed " + c.changed().size()
                + " stuck " + c.stuck().size());

        ThreadTop.TopView first = diag.top(id, node, 10, false);
        assertThat(first.rows()).isNotEmpty();
        Thread.sleep(1500);
        ThreadTop.TopView v = diag.top(id, node, 10, true);
        assertThat(v.firstSample()).isFalse();
        assertThat(v.intervalMs()).isGreaterThan(1000);
        assertThat(v.processCpuPct()).isNotNull();
        System.out.println(node + " ttop: proc " + v.processCpuPct() + "% threads " + v.threadsCpuPct() + "% alloc "
                + v.allocSupported() + " " + v.method() + " top " + v.rows().subList(0, Math.min(3, v.rows().size())));
    }

    private void verifyPartitions(String id, String node) throws Exception {
        CqlSession s = engine.sessions.session(id);
        schema(s);
        for (int i = 0; i < 50; i++) s.execute("INSERT INTO t2_diag.events (pk, ck, v) VALUES (42, " + i + ", 'x')");

        TableHistograms.View h = diag.histograms(id, null, "t2_diag", false);
        assertThat(h.errors()).isEmpty();
        assertThat(h.merged()).anyMatch(t -> t.table().equals("events"));
        System.out.println(node + " histograms: " + h.merged());
        // system tables have SSTables, so their partition-size percentiles are filled in
        TableHistograms.View sys = diag.histograms(id, null, "system", false);
        TableHistograms.TableHist local = sys.merged().stream().filter(t -> t.table().equals("local")).findFirst().orElseThrow();
        assertThat(local.partitionSize().count()).isPositive();
        assertThat(local.partitionSize().p50()).isPositive();
        assertThat(local.partitionSize().max()).isGreaterThanOrEqualTo(local.partitionSize().p50());
        System.out.println(node + " sized: " + local);

        AtomicBoolean stop = new AtomicBoolean();
        Thread load = Thread.ofVirtual().start(() -> {
            while (!stop.get()) {
                s.execute("SELECT * FROM t2_diag.events WHERE pk = 42");
                s.execute("INSERT INTO t2_diag.events (pk, ck, v) VALUES (42, 1, 'y')");
            }
        });
        try {
            Job j = diag.hotPartitions(id, new HotPartitions.Request(List.of("t2_diag.events"), 3000, 128, 5, List.of()));
            Job done = engine.jobs.await(j.id(), 60_000);
            assertThat(done.state()).as(String.valueOf(done.error())).isEqualTo(Job.State.SUCCEEDED);
            HotPartitions.Result r = (HotPartitions.Result) done.result();
            System.out.println(node + " hot: " + r.nodes() + "\n merged " + r.merged());
            var reads = r.merged().get(0).samplers().stream().filter(x -> x.sampler().equals("READS")).findFirst().orElseThrow();
            assertThat(reads.top()).anyMatch(k -> k.key().equals("42") && k.count() > 0);
        } finally {
            stop.set(true);
            load.join();
        }
    }

    private String firstNode(String id) {
        return engine.topology.info(id).nodes().stream().map(NodeInfo::address).sorted().findFirst().orElseThrow();
    }

    @Test
    void acmeCore41() throws Exception {
        String id = acme();
        verifyThreads(id, firstNode(id));
        verifyPartitions(id, firstNode(id));

        // system.log over SSH: the test-env sidecars do not mount the node logs unless configured,
        // so each node either reports a readable error or returns parsed warnings
        LogWarnings.View w = diag.warnings(id, null, 200);
        assertThat(w.nodes()).hasSize(3);
        assertThat(w.nodes()).allMatch(n -> n.error() == null || n.error().contains("not readable"));
        System.out.println("warnings: " + w.nodes() + " " + w.warnings().size());

        // the estate script is not installed in the test-env: a clear failure, not a hang
        Job scan = engine.jobs.await(diag.tombstoneScan(id, firstNode(id), "t2_diag", null).id(), 30_000);
        System.out.println("tombstone-scan: " + scan.state() + " " + scan.error());
        assertThat(scan.state()).isIn(Job.State.FAILED, Job.State.SUCCEEDED);
        if (scan.state() == Job.State.FAILED) assertThat(scan.error()).contains("not installed");
    }

    @Test
    void legacy311() throws Exception {
        assumeTrue(listening("127.0.0.1", 29042), "legacy-311 is not running");
        String id = legacy();
        verifyThreads(id, firstNode(id));
        verifyPartitions(id, firstNode(id));
    }

    @Test
    void secure50() throws Exception {
        assumeTrue(listening("127.0.0.1", 39042) && listening("127.0.0.1", 2200), "secure-50 is not running");
        String id = secure();
        verifyThreads(id, firstNode(id));
        verifyPartitions(id, firstNode(id));
    }
}
