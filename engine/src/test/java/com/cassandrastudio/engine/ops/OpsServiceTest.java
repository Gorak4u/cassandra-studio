package com.cassandrastudio.engine.ops;

import static com.cassandrastudio.engine.ops.FakeCassandra.M;
import static com.cassandrastudio.engine.ops.FakeCassandra.S;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cassandrastudio.engine.audit.AuditLog;
import com.cassandrastudio.engine.conn.ConnectionRepository;
import com.cassandrastudio.engine.guard.ActionGuard;
import com.cassandrastudio.engine.jobs.Job;
import com.cassandrastudio.engine.jobs.JobService;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.Environment;
import com.cassandrastudio.engine.model.ConnectionConfig.Jmx;
import com.cassandrastudio.engine.model.ConnectionConfig.JmxMethod;
import com.cassandrastudio.engine.ops.OpsModel.View;
import com.cassandrastudio.engine.secrets.SecretStores;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.ApiException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** OpsService over fake nodes: guard, jobs per node, repair notifications, cancel, snapshots, views. */
class OpsServiceTest {
    private static final ActionGuard.Confirmation OK = new ActionGuard.Confirmation(true, null);

    private Database db;
    private ConnectionRepository repo;
    private AuditLog audit;
    private JobService jobs;
    private OpsService svc;
    private FakeCassandra.Node n1;
    private FakeCassandra.Node n2;
    private FakeCassandra.Jmx jmx;
    private String id;

    static ConnectionConfig conn(String name, JmxMethod method, boolean readOnly) {
        return new ConnectionConfig(null, null, name, Environment.DEV, null, readOnly, List.of("10.0.0.1"), "dc1", null,
                null, null, null, null, null, new Jmx(method, 7199, null, false, null, null), null, List.of(), null, null);
    }

    @BeforeEach
    void setUp() {
        db = Database.inMemory();
        repo = new ConnectionRepository(db, SecretStores.inMemory());
        id = repo.save(conn("c1", JmxMethod.DIRECT, false), Map.of()).id();
        audit = new AuditLog(db, "tester");
        jobs = new JobService(repo, audit);
        n1 = FakeCassandra.cassandra41("10.0.0.1");
        n2 = FakeCassandra.cassandra41("10.0.0.2");
        jmx = new FakeCassandra.Jmx().add(n1).add(n2);
        svc = new OpsService(repo, x -> Map.of(), jmx, jmx, new FakeCassandra.Topo(), new ActionGuard(audit), jobs);
        svc.pollIntervalMs = 20;
    }

    @AfterEach
    void tearDown() {
        svc.close();
        jobs.close();
        db.close();
    }

    private static Maintenance.Request flush(List<String> nodes) {
        return new Maintenance.Request(Maintenance.Kind.FLUSH, nodes, "shop", List.of("orders"), false, 0, false, false, false,
                false, false, null, List.of(), false);
    }

    @Test
    void needsConfirmationWithNodetoolPreview() {
        assertThatThrownBy(() -> svc.maintenance(id, flush(List.of("10.0.0.1", "10.0.0.2")), null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(428);
                    assertThat(e.details().get("preview")).isEqualTo(List.of("nodetool -h 10.0.0.1 flush -- shop orders",
                            "nodetool -h 10.0.0.2 flush -- shop orders"));
                });
        assertThat(n1.storage.calls).isEmpty();
    }

    @Test
    void flushRunsOnEachNodeInTurnAndIsAudited() throws Exception {
        Job j = svc.maintenance(id, flush(List.of("10.0.0.1", "10.0.0.2")), OK);
        Job done = jobs.await(j.id(), 5000);
        assertThat(done.state()).isEqualTo(Job.State.SUCCEEDED);
        assertThat(done.progress()).isEqualTo(1.0);
        assertThat(n1.storage.calls).containsExactly("forceKeyspaceFlush [shop, [orders]]");
        assertThat(n2.storage.calls).containsExactly("forceKeyspaceFlush [shop, [orders]]");
        assertThat(done.log()).anyMatch(l -> l.startsWith("[10.0.0.2] done"));
        assertThat(done.node()).isEqualTo("10.0.0.1,10.0.0.2");
        List<AuditLog.Entry> entries = audit.search(id, "Flush", null, 10);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).toString()).contains("ops").contains("SUCCESS");
    }

    @Test
    void failingNodeStopsTheSequence() throws Exception {
        n1.storage.op("forceKeyspaceCleanup", List.of("int", S, FakeCassandra.SA), a -> 1);
        Maintenance.Request r = new Maintenance.Request(Maintenance.Kind.CLEANUP, List.of("10.0.0.1", "10.0.0.2"), "shop",
                List.of(), false, 1, false, false, false, false, false, null, List.of(), false);
        Job done = jobs.await(svc.maintenance(id, r, OK).id(), 5000);
        assertThat(done.state()).isEqualTo(Job.State.FAILED);
        assertThat(done.error()).contains("1 of 2 node(s) failed, 1 not run").contains("aborted");
        assertThat(n2.storage.calls).isEmpty();
    }

    @Test
    void cancellingACompactionStopsItOnTheNode() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        n1.storage.op("forceKeyspaceCompaction", List.of("boolean", S, FakeCassandra.SA), a -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
        n1.compaction.attr("Compactions", List.of(Map.of("keyspace", "shop", "columnfamily", "orders", "completed", "50",
                "total", "100", "taskType", "Compaction", "unit", "bytes")));
        Maintenance.Request r = new Maintenance.Request(Maintenance.Kind.COMPACT, List.of("10.0.0.1"), "shop", List.of(),
                false, 0, false, false, false, false, false, null, List.of(), false);
        Job j = svc.maintenance(id, r, OK);
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && (jobs.get(j.id()).progress() == null || jobs.get(j.id()).progress() < 0.5)) {
            Thread.sleep(10);
        }
        assertThat(jobs.get(j.id()).message()).contains("Compaction shop.orders 50.0%");
        jobs.cancel(j.id());
        release.countDown();
        assertThat(jobs.await(j.id(), 5000).state()).isEqualTo(Job.State.CANCELLED);
        assertThat(n1.compaction.calls).contains("stopCompaction [COMPACTION]");
    }

    @Test
    void repairFollowsProgressNotifications() throws Exception {
        n1.storage.op("repairAsync", List.of(S, M), a -> {
            Thread t = new Thread(() -> {
                sleep(30);
                n1.storage.emit("progress", "repair:3", "Starting repair command #3", Map.of("type", 0, "progressCount", 0, "total", 2));
                n1.storage.emit("progress", "repair:9", "someone else's repair", Map.of("type", 1, "progressCount", 1, "total", 2));
                n1.storage.emit("progress", "repair:3", "Repair session 1 finished", Map.of("type", 1, "progressCount", 1, "total", 2));
                n1.storage.emit("progress", "repair:3", "Repair command #3 finished", Map.of("type", 5, "progressCount", 2, "total", 2));
            });
            t.start();
            return 3;
        });
        Repair.Request r = new Repair.Request(List.of("10.0.0.1"), "shop", List.of(), true, true, List.of(),
                Repair.Parallelism.PARALLEL, List.of(), 1, false);
        Job done = jobs.await(svc.repair(id, r, OK).id(), 5000);
        assertThat(done.state()).isEqualTo(Job.State.SUCCEEDED);
        assertThat(done.log()).contains("[10.0.0.1] Repair session 1 finished", "[10.0.0.1] Repair command #3 finished")
                .noneMatch(l -> l.contains("someone else"));
        assertThat(n1.storage.calls.get(0)).contains("repairAsync [shop, {").contains("primaryRange=true")
                .contains("incremental=false");
    }

    @Test
    void repairErrorFailsTheJobAndCancelTerminatesSessions() throws Exception {
        n1.storage.op("repairAsync", List.of(S, M), a -> {
            new Thread(() -> {
                sleep(30);
                n1.storage.emit("progress", "repair:4", "Repair failed: endpoint down", Map.of("type", 2, "progressCount", 0, "total", 1));
                n1.storage.emit("progress", "repair:4", "done", Map.of("type", 5, "progressCount", 1, "total", 1));
            }).start();
            return 4;
        });
        n2.storage.op("repairAsync", List.of(S, M), a -> 5); // never completes
        Repair.Request one = new Repair.Request(List.of("10.0.0.1"), "shop", List.of(), true, false, List.of(),
                Repair.Parallelism.PARALLEL, List.of(), 1, false);
        Job failed = jobs.await(svc.repair(id, one, OK).id(), 5000);
        assertThat(failed.state()).isEqualTo(Job.State.FAILED);
        assertThat(failed.error()).contains("endpoint down");

        Repair.Request two = new Repair.Request(List.of("10.0.0.2"), "shop", List.of(), true, false, List.of(),
                Repair.Parallelism.PARALLEL, List.of(), 1, false);
        Job j = svc.repair(id, two, OK);
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && jobs.get(j.id()).log().stream().noneMatch(l -> l.contains("#5"))) {
            Thread.sleep(10);
        }
        jobs.cancel(j.id());
        assertThat(jobs.await(j.id(), 5000).state()).isEqualTo(Job.State.CANCELLED);
        assertThat(n2.storage.calls).contains("forceTerminateAllRepairSessions []");
    }

    @Test
    void refusesReadOnlyUnknownNodesAndExporterConnections() {
        String ro = repo.save(conn("ro", JmxMethod.DIRECT, true), Map.of()).id();
        assertThatThrownBy(() -> svc.maintenance(ro, flush(List.of("10.0.0.1")), OK))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(403));
        assertThatThrownBy(() -> svc.maintenance(id, flush(List.of("10.9.9.9")), OK))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("Unknown node"));
        String exp = repo.save(conn("exp", JmxMethod.EXPORTER, false), Map.of()).id();
        assertThatThrownBy(() -> svc.view(exp, "tpstats", null, null, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(409));
    }

    @Test
    void snapshotsAreTakenListedAndCleared() throws Exception {
        Snapshots.Create c = new Snapshots.Create(List.of("10.0.0.1"), "before", List.of("shop"), List.of(), false);
        assertThat(jobs.await(svc.takeSnapshot(id, c, OK).id(), 5000).state()).isEqualTo(Job.State.SUCCEEDED);
        assertThat(n1.storage.calls.get(0)).startsWith("takeSnapshot [before, {skipFlush=false}, [shop]]");

        String[] items = {"Snapshot name", "Keyspace name", "Column family name", "True size", "Size on disk", "Creation time"};
        OpenType<?>[] types = new OpenType<?>[items.length];
        java.util.Arrays.fill(types, SimpleType.STRING);
        CompositeType row = new CompositeType("SnapshotDetails", "d", items, items, types);
        TabularDataSupport td = new TabularDataSupport(new TabularType("SnapshotDetails", "d", row, items));
        td.put(new CompositeDataSupport(row, items, new Object[] {"before", "shop", "orders", "6.09 KiB", "12 KiB", "2026-10-09T08:29:17Z"}));
        Map<String, Object> details = new HashMap<>();
        details.put("before", td);
        n1.storage.attr("SnapshotDetails", details);
        OpsModel.SnapshotList list = svc.snapshots(id, null);
        assertThat(list.snapshots()).hasSize(1);
        assertThat(list.snapshots().get(0)).extracting(OpsModel.Snapshot::node, OpsModel.Snapshot::tag,
                OpsModel.Snapshot::table, OpsModel.Snapshot::trueSizeBytes).containsExactly("10.0.0.1", "before", "orders", 6236L);
        assertThat(list.errors()).isEmpty();

        Snapshots.Clear clear = new Snapshots.Clear(List.of("10.0.0.1"), "before", List.of(), false);
        assertThatThrownBy(() -> svc.clearSnapshot(id, clear, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.details().get("destructive")).isEqualTo(true));
        assertThat(jobs.await(svc.clearSnapshot(id, clear, OK).id(), 5000).state()).isEqualTo(Job.State.SUCCEEDED);
        assertThat(n1.storage.calls).contains("clearSnapshot [before, []]");
    }

    @Test
    void viewsReadTheNode() {
        n1.storage.attr("LiveNodes", List.of("10.0.0.1", "10.0.0.2")).attr("UnreachableNodes", List.of())
                .attr("LoadMap", Map.of("10.0.0.1", "1.5 MiB", "10.0.0.2", "2 MiB"))
                .attr("TokenToEndpointMap", Map.of("-100", "10.0.0.1", "100", "10.0.0.2", "5", "10.0.0.1"))
                .attr("Ownership", Map.of("10.0.0.1", 0.5f, "10.0.0.2", 0.5f))
                .attr("EndpointToHostId", Map.of("10.0.0.1", "h1", "10.0.0.2", "h2"));
        View status = svc.view(id, "status", "10.0.0.1", null, null, null);
        assertThat(status.command()).isEqualTo("nodetool -h 10.0.0.1 status");
        assertThat(status.sections().get(0).rows()).contains(List.of("UN", "10.0.0.1", "1.5 MiB", "2", "50.0%", "h1", "rack1"));
        View ring = svc.view(id, "ring", "10.0.0.1", null, null, null);
        assertThat(ring.sections().get(0).rows()).extracting(r -> r.get(6)).containsExactly("-100", "5", "100");
        View info = svc.view(id, "describecluster", "10.0.0.1", null, null, null);
        assertThat(info.sections().get(0).rows()).contains(List.of("Name", "test"));
        assertThatThrownBy(() -> svc.view(id, "decommission", "10.0.0.1", null, null, null)).hasMessageContaining("Unknown view");
        assertThatThrownBy(() -> svc.view(id, "tablehistograms", "10.0.0.1", "shop", null, null))
                .hasMessageContaining("needs keyspace and table");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
