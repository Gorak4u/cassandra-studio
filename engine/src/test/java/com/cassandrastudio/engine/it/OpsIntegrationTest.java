package com.cassandrastudio.engine.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.cassandrastudio.engine.audit.AuditLog;
import com.cassandrastudio.engine.conn.ConnectionRepository;
import com.cassandrastudio.engine.cql.ClusterService.ClusterInfo;
import com.cassandrastudio.engine.cql.ClusterService.NodeInfo;
import com.cassandrastudio.engine.guard.ActionGuard;
import com.cassandrastudio.engine.jmx.DefaultJmxAccess;
import com.cassandrastudio.engine.jobs.Job;
import com.cassandrastudio.engine.jobs.JobService;
import com.cassandrastudio.engine.metrics.Topology;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.Environment;
import com.cassandrastudio.engine.model.ConnectionConfig.Jmx;
import com.cassandrastudio.engine.model.ConnectionConfig.JmxMethod;
import com.cassandrastudio.engine.model.ConnectionConfig.Ssh;
import com.cassandrastudio.engine.model.ConnectionConfig.SshAuth;
import com.cassandrastudio.engine.ops.Maintenance;
import com.cassandrastudio.engine.ops.OpsModel.View;
import com.cassandrastudio.engine.ops.OpsService;
import com.cassandrastudio.engine.ops.Repair;
import com.cassandrastudio.engine.secrets.SecretStores;
import com.cassandrastudio.engine.store.Database;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * OPS-1..3 against the running test-env (not Testcontainers): Cassandra 4.1 through an SSH tunnel
 * and 3.11 over direct JMX. Views, a flush and a repair with live notifications, and the audit
 * entry the job leaves. Skipped when the test-env is not up. Overrides: STUDIO_IT_OPS_NODE
 * (10.231.42.11), STUDIO_IT_OPS_SSH_PORT (2222), STUDIO_IT_SSH_DIR (../test-env/ssh),
 * STUDIO_IT_DIRECT_JMX (127.0.0.1:27199).
 */
@Tag("integration")
class OpsIntegrationTest {
    private static final ActionGuard.Confirmation OK = new ActionGuard.Confirmation(true, null);

    private final String node41 = env("STUDIO_IT_OPS_NODE", "10.231.42.11");
    private final int sshPort = Integer.parseInt(env("STUDIO_IT_OPS_SSH_PORT", "2222"));
    private final Path key = Path.of(env("STUDIO_IT_SSH_DIR", "../test-env/ssh")).resolve("id_test").toAbsolutePath();
    private final String direct = env("STUDIO_IT_DIRECT_JMX", "127.0.0.1:27199");

    private Database db;
    private DefaultJmxAccess readJmx;
    private DefaultJmxAccess opsJmx;
    private JobService jobs;
    private OpsService svc;
    private AuditLog audit;
    private ConnectionRepository repo;

    private static String env(String k, String dflt) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? dflt : v;
    }

    private static boolean open(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String setUp(ConnectionConfig cfg, NodeInfo node) {
        db = Database.inMemory();
        repo = new ConnectionRepository(db, SecretStores.inMemory());
        String id = repo.save(cfg, Map.of()).id();
        audit = new AuditLog(db, "it");
        jobs = new JobService(repo, audit);
        readJmx = new DefaultJmxAccess();
        opsJmx = new DefaultJmxAccess(DefaultJmxAccess.DEFAULT_CONNECT_TIMEOUT, Duration.ZERO);
        Topology topo = c -> new ClusterInfo("it", null, List.of(node.datacenter()), List.of(node), true,
                List.of(node.version()), "V4");
        svc = new OpsService(repo, x -> Map.of(), readJmx, opsJmx, topo, new ActionGuard(audit), jobs);
        return id;
    }

    @AfterEach
    void tearDown() {
        if (svc != null) svc.close();
        if (jobs != null) jobs.close();
        if (readJmx != null) readJmx.close();
        if (opsJmx != null) opsJmx.close();
        if (db != null) db.close();
    }

    @Test
    void cassandra41OverSshTunnel() throws Exception {
        assumeTrue(Files.exists(key) && open(node41, sshPort), "test-env 4.1 SSH not reachable");
        ConnectionConfig cfg = new ConnectionConfig(null, null, "acme-core", Environment.DEV, null, false, List.of(node41),
                "dc_east", null, null, null, null, null, null, new Jmx(JmxMethod.SSH_TUNNEL, 7199, null, false, null, null),
                new Ssh("studio", sshPort, SshAuth.KEY, key.toString(), null, null, null, false, null), List.of(), null, null);
        String id = setUp(cfg, new NodeInfo(null, node41, 9042, "dc_east", "rack1", "4.1", "UP", 16, null, 0));
        checkViewsFlushAndRepair(id, node41, "4.1");
    }

    @Test
    void cassandra311OverDirectJmx() throws Exception {
        String[] hp = direct.split(":");
        assumeTrue(open(hp[0], Integer.parseInt(hp[1])), "test-env 3.11 JMX not reachable");
        ConnectionConfig cfg = new ConnectionConfig(null, null, "legacy-311", Environment.DEV, null, false, List.of(hp[0]),
                "dc1", null, null, null, null, null, null, new Jmx(JmxMethod.DIRECT, Integer.parseInt(hp[1]), null, false, null, null),
                null, List.of(), null, null);
        String id = setUp(cfg, new NodeInfo(null, hp[0], 9042, "dc1", "rack1", "3.11", "UP", 256, null, 0));
        checkViewsFlushAndRepair(id, hp[0], "3.11");
    }

    private void checkViewsFlushAndRepair(String id, String node, String version) throws Exception {
        View info = svc.view(id, "info", node, null, null, null);
        assertThat(info.sections().get(0).rows()).anySatisfy(r -> {
            assertThat(r.get(0)).isEqualTo("Release version");
            assertThat(r.get(1)).startsWith(version);
        });
        for (String v : List.of("status", "ring", "describecluster", "tpstats", "proxyhistograms", "gossipinfo",
                "compactionstats", "netstats")) {
            View view = svc.view(id, v, node, null, null, null);
            assertThat(view.sections()).as(v).isNotEmpty();
        }
        assertThat(svc.view(id, "tablestats", node, "system_auth", null, null).sections().get(0).rows()).isNotEmpty();
        assertThat(svc.view(id, "tablehistograms", node, "system_auth", "roles", null).sections().get(0).rows()).hasSize(7);
        assertThat(svc.view(id, "getendpoints", node, "system_auth", "roles", "cassandra").sections().get(0).rows()).isNotEmpty();

        Maintenance.Request flush = new Maintenance.Request(Maintenance.Kind.FLUSH, List.of(node), "system_auth", List.of(),
                false, 0, false, false, false, false, false, null, List.of(), false);
        Job f = jobs.await(svc.maintenance(id, flush, OK).id(), 60_000);
        assertThat(f.state()).as(f.error()).isEqualTo(Job.State.SUCCEEDED);

        Repair.Request repair = new Repair.Request(List.of(node), "system_auth", List.of(), true, true, List.of(),
                Repair.Parallelism.PARALLEL, List.of(), 1, false);
        Job r = jobs.await(svc.repair(id, repair, OK).id(), 300_000);
        assertThat(r.state()).as(r.error()).isEqualTo(Job.State.SUCCEEDED);
        assertThat(r.log()).anyMatch(l -> l.contains("repair") || l.contains("Nothing to repair"));

        assertThat(svc.snapshots(id, node).errors()).isEmpty();
        assertThat(audit.search(id, null, null, 10)).extracting(AuditLog.Entry::category).contains("ops");
    }
}
