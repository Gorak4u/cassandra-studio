package com.cassandrastudio.engine.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.cassandrastudio.engine.conn.ConnectionRepository;
import com.cassandrastudio.engine.jmx.JmxAccess.ExporterSample;
import com.cassandrastudio.engine.metrics.MonitoringModel.AccessStatus;
import com.cassandrastudio.engine.metrics.MonitoringModel.Alert;
import com.cassandrastudio.engine.metrics.MonitoringModel.ClusterSnapshot;
import com.cassandrastudio.engine.metrics.MonitoringModel.Level;
import com.cassandrastudio.engine.metrics.MonitoringModel.NodeSnapshot;
import com.cassandrastudio.engine.metrics.MonitoringModel.Ring;
import com.cassandrastudio.engine.metrics.MonitoringModel.RingNode;
import com.cassandrastudio.engine.metrics.MonitoringModel.Series;
import com.cassandrastudio.engine.metrics.MonitoringModel.TableMetrics;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.Environment;
import com.cassandrastudio.engine.model.ConnectionConfig.Jmx;
import com.cassandrastudio.engine.model.ConnectionConfig.JmxMethod;
import com.cassandrastudio.engine.secrets.SecretStores;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.ApiException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MonitoringServiceTest {
    private Database db;
    private ConnectionRepository repo;
    private FakeNodes.Jmx jmx;
    private FakeNodes.Topo topo;
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private MonitoringService svc;
    private String id;
    private FakeNodes.Node n1;

    static ConnectionConfig conn(String name, JmxMethod method) {
        return new ConnectionConfig(null, null, name, Environment.DEV, null, false, List.of("10.0.0.1"), "dc1", null,
                null, null, null, null, null, new Jmx(method, 7199, null, false, null, null), null, List.of(), null, null);
    }

    @BeforeEach
    void setUp() {
        db = Database.inMemory();
        repo = new ConnectionRepository(db, SecretStores.inMemory());
        id = repo.save(conn("c1", JmxMethod.DIRECT), Map.of("jmxPassword", "secret")).id();
        n1 = FakeNodes.cassandra41G1("10.0.0.1", "h1");
        jmx = new FakeNodes.Jmx().add(n1).add(FakeNodes.cassandra311Cms("10.0.0.2", "h2"));
        topo = new FakeNodes.Topo();
        topo.nodes.set(1, FakeNodes.Topo.info("h2", "10.0.0.2", "rack2", "3.11.16", "UP"));
        svc = new MonitoringService(db, repo, jmx, topo, clock::get);
    }

    @AfterEach
    void tearDown() {
        svc.close();
        db.close();
    }

    /** Started with a long interval: the loop polls once, the test drives further polls. */
    private Poller started() {
        return awaitFirstPoll(svc.startPoller(id, 60_000));
    }

    /** Waits for the loop's own first poll so later test-driven polls do not race with it. */
    static Poller awaitFirstPoll(Poller p) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (p.latest() == null && System.currentTimeMillis() < deadline) Thread.onSpinWait();
        assertThat(p.latest()).isNotNull();
        return p;
    }

    @Test
    void notStartedAndUnknownConnection() {
        assertThatThrownBy(() -> svc.snapshot(id)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(409);
            assertThat(e.code()).isEqualTo("not_started");
        });
        assertThatThrownBy(() -> svc.snapshot("nope")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(404));
        assertThatThrownBy(() -> svc.start("nope", 10)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(404));
        AccessStatus st = svc.status(id);
        assertThat(st.polling()).isFalse();
        assertThat(st.method()).isEqualTo("DIRECT");
        assertThat(svc.alerts(id)).isEmpty();
        assertThat(svc.series(id, "heap.used", null, null, null).pointsByNode()).isEmpty();
        assertThatThrownBy(() -> svc.start(id, 1)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    void pollMergesDriverGossipAndJmx() {
        started();
        ClusterSnapshot s = svc.snapshot(id);
        assertThat(s.pollIntervalSec()).isEqualTo(60);
        assertThat(s.nodes()).extracting(NodeSnapshot::address).containsExactly("10.0.0.1", "10.0.0.2", "10.0.0.3");
        NodeSnapshot a = s.nodes().get(0), b = s.nodes().get(1), c = s.nodes().get(2);
        assertThat(a.state()).isEqualTo("UN");
        assertThat(a.route()).isEqualTo("direct");
        assertThat(a.heapUsedBytes()).isEqualTo(4_000_000_000L);
        assertThat(b.cassandraVersion()).isEqualTo("3.11.16");
        assertThat(b.state()).isEqualTo("UN");
        // unreachable JMX: identity and state from driver + gossip, metrics null
        assertThat(c.error()).contains("Connection refused");
        assertThat(c.state()).isEqualTo("DN");
        assertThat(c.hostId()).isEqualTo("h3");
        assertThat(c.rack()).isEqualTo("rack3");
        assertThat(c.heapUsedBytes()).isNull();
        assertThat(s.alerts()).extracting(Alert::id).containsExactly("node.down:10.0.0.3", "compaction.backlog:10.0.0.2",
                "node.unreachable:10.0.0.3", "threadpool.blocked:10.0.0.2");
        assertThat(s.health().level()).isEqualTo(Level.RED);
        assertThat(s.health().reasons()).hasSize(4);
        assertThat(svc.alerts(id)).isEqualTo(s.alerts());

        AccessStatus st = svc.status(id);
        assertThat(st.polling()).isTrue();
        assertThat(st.pollIntervalSec()).isEqualTo(60);
        assertThat(st.nodes()).extracting(n -> n.address() + "=" + n.ok())
                .containsExactly("10.0.0.1=true", "10.0.0.2=true", "10.0.0.3=false");
        assertThat(st.nodes().get(2).error()).contains("Connection refused");
    }

    @Test
    void gossipWinsOverUnknownDriverStateAndModeComesFromTheNode() {
        topo.nodes.set(0, FakeNodes.Topo.info("h1", "10.0.0.1", "rack1", "4.1.5", "UNKNOWN"));
        n1.set(FakeNodes.SS, "OperationMode", "LEAVING");
        started();
        assertThat(svc.snapshot(id).nodes().get(0).state()).isEqualTo("UL");
    }

    @Test
    void seriesAcrossPolls() {
        Poller p = started();
        clock.addAndGet(10_000);
        n1.gc("G1 Young Generation", 20, 2_100); // +1100 ms GC in 10 s
        n1.set("java.lang:type=Memory", "HeapMemoryUsage", FakeNodes.memoryUsage(5_000_000_000L, 8_000_000_000L));
        n1.metric("type=ClientRequest,scope=Read,name=Timeouts", "Count", 20L);
        p.pollOnce();
        Series heap = svc.series(id, "heap.used", "10.0.0.1", null, null);
        assertThat(heap.unit()).isEqualTo("bytes");
        assertThat(heap.pointsByNode().get("10.0.0.1")).extracting(pt -> pt[1]).containsExactly(4e9, 5e9);
        Series gc = svc.series(id, "gc.time_pct", null, null, null);
        assertThat(gc.pointsByNode()).containsOnlyKeys("10.0.0.1", "10.0.0.2");
        assertThat(gc.pointsByNode().get("10.0.0.1")).singleElement().satisfies(pt -> {
            assertThat(pt[0]).isEqualTo(1_010_000.0);
            assertThat(pt[1]).isCloseTo(11.0, within(1e-9));
        });
        assertThat(svc.series(id, "client.timeouts", "10.0.0.1", null, null).pointsByNode().get("10.0.0.1"))
                .singleElement().satisfies(pt -> assertThat(pt[1]).isEqualTo(2.0)); // 20 timeouts in 10 s
        assertThat(svc.series(id, "client.read.p99_us", "10.0.0.1", null, null).unit()).isEqualTo("us");
        assertThat(svc.series(id, "heap.used", null, 0L, 1_005_000L).pointsByNode().get("10.0.0.1")).hasSize(1);
        assertThat(svc.snapshot(id).alerts()).extracting(Alert::id).contains("client.timeouts:10.0.0.1",
                "gc.pressure:10.0.0.1");
        for (String metric : SeriesMetrics.ALL.keySet()) {
            assertThat(svc.series(id, metric, null, null, null).metric()).isEqualTo(metric);
        }
        assertThatThrownBy(() -> svc.series(id, "heap.nope", null, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(400));
        assertThatThrownBy(() -> svc.series(id, "heap.used", null, 10L, 5L)).isInstanceOf(ApiException.class);
    }

    @Test
    void ringFromJmxWithDefaultKeyspace() {
        started();
        Ring ring = svc.ring(id, null);
        assertThat(ring.keyspace()).isEqualTo("shop");
        assertThat(ring.partitioner()).endsWith("Murmur3Partitioner");
        assertThat(ring.datacenters()).singleElement().satisfies(dc -> {
            assertThat(dc.name()).isEqualTo("dc1");
            assertThat(dc.nodes()).extracting(RingNode::address).containsExactly("10.0.0.1", "10.0.0.2", "10.0.0.3");
            RingNode a = dc.nodes().get(0);
            assertThat(a.tokens()).containsExactly("-100", "100");
            assertThat(a.ownershipPct()).isCloseTo(50.0, within(1e-4));
            assertThat(a.effectiveOwnershipPct()).isEqualTo(100.0);
            assertThat(a.loadBytes()).isEqualTo(1_000_000L);
            assertThat(a.state()).isEqualTo("UN");
            assertThat(dc.nodes().get(2).state()).isEqualTo("DN");
            assertThat(dc.nodes().get(2).tokens()).containsExactly("50");
        });
    }

    @Test
    void ringFallsBackToDriverTokensWithoutJmx() {
        jmx.unreachable.addAll(List.of("10.0.0.1", "10.0.0.2"));
        Topology withTokens = new FakeNodes.Topo() {
            @Override
            public Map<String, List<String>> tokens(String connectionId) {
                return Map.of("h1", List.of("100", "-5"));
            }
        };
        MonitoringService s = new MonitoringService(db, repo, jmx, withTokens, clock::get);
        try {
            Ring ring = s.ring(id, "shop");
            RingNode a = ring.datacenters().get(0).nodes().stream().filter(n -> n.hostId().equals("h1")).findFirst()
                    .orElseThrow();
            assertThat(a.tokens()).containsExactly("-5", "100");
            assertThat(a.ownershipPct()).isNull();
            assertThat(a.state()).isEqualTo("UN");
        } finally {
            s.close();
        }
    }

    @Test
    void tablesSummedOverNodes() {
        List<TableMetrics> tables = svc.tables(id, null);
        assertThat(tables).singleElement().satisfies(t -> {
            assertThat(t.keyspace() + "." + t.table()).isEqualTo("shop.orders");
            assertThat(t.sstableCount()).isEqualTo(30);
            assertThat(t.readCount()).isEqualTo(200);
            assertThat(t.writeLatencyP99Micros()).isEqualTo(110.0);
            assertThat(t.maxPartitionSizeBytes()).isEqualTo(9_020);
            assertThat(t.meanPartitionSizeBytes()).isEqualTo(2_000);
            assertThat(t.keyCacheHitRate()).isCloseTo(0.9, within(1e-9));
        });
        jmx.unreachable.addAll(List.of("10.0.0.1", "10.0.0.2"));
        assertThatThrownBy(() -> svc.tables(id, null)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(503));
    }

    @Test
    void thresholdsPersistAndApply() {
        assertThat(svc.thresholds(id)).isEqualTo(Thresholds.DEFAULTS);
        Thresholds eff = svc.setThresholds(id, new Thresholds(null, null, null, null, 200L, null, null, null, null));
        assertThat(eff.compactionPending()).isEqualTo(200);
        assertThat(eff.heapYellowPct()).isEqualTo(85.0);
        started();
        assertThat(svc.snapshot(id).alerts()).extracting(Alert::rule).doesNotContain("compaction.backlog");
        MonitoringService again = new MonitoringService(db, repo, jmx, topo, clock::get);
        try {
            assertThat(again.thresholds(id).compactionPending()).isEqualTo(200);
        } finally {
            again.close();
        }
        assertThatThrownBy(() -> svc.setThresholds(id, new Thresholds(99.0, 90.0, null, null, null, null, null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(400));
        assertThatThrownBy(() -> svc.thresholds("nope")).isInstanceOf(ApiException.class);
    }

    @Test
    void startIsIdempotentAndStopEndsPolling() {
        AccessStatus st = svc.start(id, null);
        assertThat(st.polling()).isTrue();
        assertThat(st.pollIntervalSec()).isEqualTo(10);
        Poller first = svc.startPoller(id, 10_000);
        assertThat(svc.startPoller(id, 10_000)).isSameAs(first);
        assertThat(svc.start(id, 20).pollIntervalSec()).isEqualTo(20);
        assertThat(svc.startPoller(id, 20_000)).isNotSameAs(first);
        assertThat(first.running()).isFalse();
        svc.stop(id);
        assertThat(svc.isStarted(id)).isFalse();
        assertThat(svc.status(id).polling()).isFalse();
        assertThatThrownBy(() -> svc.snapshot(id)).isInstanceOf(ApiException.class);
        svc.stop(id); // idempotent
    }

    @Test
    void pollsNeverOverlap() throws Exception {
        jmx.delayMs = 150;
        Poller p = svc.startPoller(id, 20); // much shorter than a node read
        Thread.sleep(700);
        svc.stop(id);
        assertThat(p.running()).isFalse();
        assertThat(jmx.sessions.get()).isGreaterThan(6); // several polls ran
        assertThat(jmx.maxActive.get()).isLessThanOrEqualTo(2); // one read per reachable node at a time
    }

    @Test
    void slowNodeTimesOutAndIsNotReadTwice() {
        jmx.delayMs = 2_500;
        Poller p = svc.startPoller(id, 2_000); // per-node timeout 1.6 s
        ClusterSnapshot first = p.latestOrPoll();
        assertThat(first.nodes().get(0).error()).startsWith("Timed out");
        assertThat(first.nodes().get(0).state()).isEqualTo("UN"); // driver says UP
        ClusterSnapshot second = p.pollOnce();
        assertThat(second.nodes().get(0).error()).contains("still running");
        svc.stop(id);
    }

    @Test
    void topologyFailureKeepsLastKnownNodes() {
        topo.failure = new IllegalStateException("not connected");
        Poller p = awaitFirstPoll(svc.startPoller(id, 60_000));
        ClusterSnapshot s = p.latest();
        assertThat(s.health().level()).isEqualTo(Level.RED);
        assertThat(s.health().reasons().get(0)).contains("not connected");
        assertThat(s.nodes()).isEmpty();
        topo.failure = null;
        p.pollOnce();
        topo.failure = new IllegalStateException("not connected");
        s = p.pollOnce();
        assertThat(s.nodes()).hasSize(3);
        assertThat(s.nodes().get(0).state()).isEqualTo("UN"); // gossip from the node itself
    }

    @Test
    void exporterMethod() {
        String eid = repo.save(conn("exp", JmxMethod.EXPORTER), Map.of()).id();
        jmx.exporter.put("10.0.0.1", List.of(
                new ExporterSample("jvm_memory_bytes_used", Map.of("area", "heap"), 100),
                new ExporterSample("jvm_memory_bytes_max", Map.of("area", "heap"), 1000),
                new ExporterSample("cassandra_storage_load", Map.of(), 5000),
                new ExporterSample("cassandra_table_livesstablecount", Map.of("keyspace", "shop", "table", "orders"), 4),
                new ExporterSample("cassandra_table_readlatency", Map.of("keyspace", "shop", "table", "orders"), 9)));
        Poller p = awaitFirstPoll(svc.startPoller(eid, 60_000));
        NodeSnapshot a = p.latest().nodes().get(0);
        assertThat(a.route()).isEqualTo("jmx_exporter");
        assertThat(a.heapUsedBytes()).isEqualTo(100);
        assertThat(a.loadBytes()).isEqualTo(5000);
        assertThat(svc.status(eid).method()).isEqualTo("EXPORTER");
        assertThat(svc.tables(eid, null)).singleElement().satisfies(t -> {
            assertThat(t.sstableCount()).isEqualTo(4);
            assertThat(t.readCount()).isEqualTo(9);
        });
    }
}
