package com.cassandrastudio.engine.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.cassandrastudio.engine.metrics.MetricCatalog.GcFamily;
import com.cassandrastudio.engine.metrics.MetricCatalog.Major;
import com.cassandrastudio.engine.metrics.MonitoringModel.NodeSnapshot;
import com.cassandrastudio.engine.metrics.MonitoringModel.TableMetrics;
import com.cassandrastudio.engine.metrics.MonitoringModel.ThreadPool;
import java.util.List;
import org.junit.jupiter.api.Test;

class NodeReaderTest {
    private static final NodeReader.Identity ID1 = new NodeReader.Identity("h1", "10.0.0.1", "dc1", "rack1", "4.1.5");

    @Test
    void majorVersionsAndCollectors() {
        assertThat(Major.of("3.11.16")).isEqualTo(Major.V3_11);
        assertThat(Major.of("4.0.12")).isEqualTo(Major.V4_0);
        assertThat(Major.of("4.1.5")).isEqualTo(Major.V4_1);
        assertThat(Major.of("5.0.2")).isEqualTo(Major.V5_0);
        assertThat(Major.of("6.0-alpha1")).isEqualTo(Major.V5_0);
        assertThat(Major.of(null)).isEqualTo(Major.V4_1);
        assertThat(MetricCatalog.collector("ParNew").family()).isEqualTo(GcFamily.CMS);
        assertThat(MetricCatalog.collector("G1 Concurrent GC").pause()).isFalse();
        assertThat(MetricCatalog.collector("ZGC Major Pauses").pause()).isTrue();
        assertThat(MetricCatalog.collector("Shenandoah Cycles").pause()).isFalse();
        assertThat(MetricCatalog.collector("PS MarkSweep").family()).isEqualTo(GcFamily.PARALLEL);
        assertThat(MetricCatalog.tableTypes(Major.V3_11).get(0)).isEqualTo("ColumnFamily");
        assertThat(MetricCatalog.sources(MetricCatalog.Scalar.OWNERSHIP, Major.V5_0).get(0).attribute())
                .isEqualTo("OwnershipWithPort");
        assertThat(MetricCatalog.toMicros(2.0, "milliseconds")).isEqualTo(2000.0);
    }

    @Test
    void readsA41G1Node() {
        FakeNodes.Node node = FakeNodes.cassandra41G1("10.0.0.1", "h1");
        NodeReader.Result r = new NodeReader().read(node.server, "direct", ID1, 1_000);
        NodeSnapshot n = r.snapshot();
        assertThat(n.error()).isNull();
        assertThat(n.cassandraVersion()).isEqualTo("4.1.5");
        assertThat(n.javaVersion()).isEqualTo("17.0.10");
        assertThat(n.javaVendor()).isEqualTo("Eclipse Adoptium");
        assertThat(n.uptimeSec()).isEqualTo(3600);
        assertThat(n.heapUsedBytes()).isEqualTo(4_000_000_000L);
        assertThat(n.heapMaxBytes()).isEqualTo(8_000_000_000L);
        assertThat(n.offHeapBytes()).isEqualTo(1200);
        assertThat(n.cpuProcessPct()).isEqualTo(25.0);
        assertThat(n.cpuSystemPct()).isEqualTo(50.0); // CpuLoad preferred over SystemCpuLoad
        assertThat(n.openFds()).isEqualTo(900);
        assertThat(n.maxFds()).isEqualTo(100_000);
        assertThat(n.loadBytes()).isEqualTo(1_000_000);
        assertThat(n.tokens()).isEqualTo(2);
        assertThat(n.pendingCompactions()).isEqualTo(3);
        assertThat(n.completedCompactions()).isEqualTo(42);
        assertThat(n.activeCompactions()).isEqualTo(1);
        assertThat(n.totalHints()).isEqualTo(7);
        assertThat(n.hintsInProgress()).isZero();
        assertThat(n.liveSSTables()).isEqualTo(12);
        assertThat(n.dataDirs()).singleElement().satisfies(d -> assertThat(d.path()).isEqualTo("/var/lib/cassandra/data"));
        assertThat(n.gc()).extracting(g -> g.name()).containsExactly("G1 Concurrent GC", "G1 Old Generation",
                "G1 Young Generation");
        assertThat(n.gcTimePct()).isNull(); // no baseline yet
        assertThat(n.threadPools()).extracting(ThreadPool::name)
                .containsExactly("MutationStage", "Native-Transport-Requests", "ReadStage");
        ThreadPool mutation = n.threadPools().get(0);
        assertThat(mutation.active()).isEqualTo(1);
        assertThat(mutation.pending()).isEqualTo(2);
        assertThat(mutation.completed()).isEqualTo(1000);
        assertThat(mutation.blocked()).isZero();
        assertThat(n.dropped()).containsEntry("MUTATION", 0L).containsEntry("READ", 0L);
        assertThat(n.clientRequests().read().p99Micros()).isEqualTo(1500.0);
        assertThat(n.clientRequests().write().ratePerSec()).isEqualTo(120.5);
        assertThat(n.clientRequests().casWrite().count()).isEqualTo(5000);
        assertThat(n.clientRequests().readTimeouts()).isZero();
        assertThat(r.operationMode()).isEqualTo("NORMAL");
        assertThat(r.gossip().live()).containsExactly("10.0.0.1", "10.0.0.2");
        assertThat(r.gossip().hostIdByEndpoint()).containsEntry("10.0.0.3", "h3");
    }

    @Test
    void readsA311CmsNode() {
        FakeNodes.Node node = FakeNodes.cassandra311Cms("10.0.0.2", "h2");
        NodeSnapshot n = new NodeReader().read(node.server, "direct",
                new NodeReader.Identity("h2", "10.0.0.2", "dc1", "rack2", "3.11.16"), 1_000).snapshot();
        assertThat(n.cassandraVersion()).isEqualTo("3.11.16");
        assertThat(n.javaVersion()).isEqualTo("1.8"); // no SystemProperties: spec version
        assertThat(n.cpuSystemPct()).isEqualTo(30.0); // SystemCpuLoad on JDK 8
        assertThat(n.liveSSTables()).isEqualTo(30); // ColumnFamily global metric
        assertThat(n.gc()).extracting(g -> g.name()).containsExactly("ConcurrentMarkSweep", "ParNew");
        assertThat(n.threadPools()).extracting(ThreadPool::name).containsExactly("CompactionExecutor", "MutationStage");
        assertThat(n.threadPools().get(1).blocked()).isEqualTo(2);
        assertThat(n.threadPools().get(1).allTimeBlocked()).isEqualTo(9);
        assertThat(n.clientRequests().read().p99Micros()).isEqualTo(2500.0); // milliseconds converted
        assertThat(n.clientRequests().write()).isNull();
        assertThat(n.clientRequests().readTimeouts()).isEqualTo(3);
        assertThat(n.offHeapBytes()).isNull();
    }

    @Test
    void missingBeansGiveNullsNotErrors() {
        FakeNodes.Node node = FakeNodes.cassandra50Sparse("10.0.0.5");
        NodeSnapshot n = new NodeReader().read(node.server, "direct",
                new NodeReader.Identity("h5", "10.0.0.5", "dc1", "r", "5.0.2"), 1_000).snapshot();
        assertThat(n.error()).isNull();
        assertThat(n.cassandraVersion()).isEqualTo("5.0.2");
        assertThat(n.heapUsedBytes()).isEqualTo(1_000);
        assertThat(n.hostId()).isEqualTo("h5");
        assertThat(n.loadBytes()).isNull();
        assertThat(n.cpuProcessPct()).isNull();
        assertThat(n.threadPools()).isNull();
        assertThat(n.dropped()).isNull();
        assertThat(n.clientRequests()).isNull();
        assertThat(n.pendingCompactions()).isNull();
        assertThat(n.liveSSTables()).isNull();
        assertThat(n.dataDirs()).isNull();
        assertThat(n.gc()).hasSize(2);
        assertThat(NodeReader.readTables(node.server, "5.0.2", null)).isEmpty();
        NodeReader.RingRead ring = NodeReader.readRing(node.server, "5.0.2", "shop");
        assertThat(ring.endpointByToken()).isEmpty();
        assertThat(ring.ownershipPct()).isEmpty();
    }

    @Test
    void gcTimePctFromDeltasUsesPauseCollectorsOnly() {
        FakeNodes.Node node = FakeNodes.cassandra41G1("10.0.0.1", "h1");
        NodeReader reader = new NodeReader();
        reader.read(node.server, "direct", ID1, 10_000);
        // 10 s later: young GC +500 ms over 5 collections, concurrent GC +9 s (not a pause)
        node.gc("G1 Young Generation", 15, 1_500).gc("G1 Concurrent GC", 6, 59_000);
        NodeReader.Result r = reader.read(node.server, "direct", ID1, 20_000);
        assertThat(r.snapshot().gcTimePct()).isCloseTo(5.0, within(1e-9));
        assertThat(r.gcPauseMs()).isCloseTo(100.0, within(1e-9));
        // no GC since: 0 %
        assertThat(reader.read(node.server, "direct", ID1, 30_000).snapshot().gcTimePct()).isZero();
        // restart: counters go down, no baseline
        node.gc("G1 Young Generation", 1, 10);
        assertThat(reader.read(node.server, "direct", ID1, 40_000).snapshot().gcTimePct()).isNull();
    }

    @Test
    void zgcPressureCountsPausesNotCycles() {
        FakeNodes.Node node = FakeNodes.cassandra50Sparse("10.0.0.5");
        NodeReader reader = new NodeReader();
        NodeReader.Identity id = new NodeReader.Identity("h5", "10.0.0.5", "dc1", "r", "5.0.2");
        reader.read(node.server, "direct", id, 0);
        node.gc("ZGC Cycles", 5, 9_000).gc("ZGC Pauses", 15, 106);
        assertThat(reader.read(node.server, "direct", id, 10_000).snapshot().gcTimePct()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void tablesForBothTableTypes() {
        List<TableMetrics> t41 = NodeReader.readTables(FakeNodes.cassandra41G1("10.0.0.1", "h1").server, "4.1.5", null);
        assertThat(t41).singleElement().satisfies(t -> {
            assertThat(t.keyspace()).isEqualTo("shop");
            assertThat(t.table()).isEqualTo("orders");
            assertThat(t.readCount()).isEqualTo(100);
            assertThat(t.readLatencyP99Micros()).isEqualTo(800.0);
            assertThat(t.sstableCount()).isEqualTo(10);
            assertThat(t.meanPartitionSizeBytes()).isEqualTo(2000);
            assertThat(t.tombstonesPerReadP99()).isEqualTo(3.0);
            assertThat(t.keyCacheHitRate()).isEqualTo(0.9);
        });
        List<TableMetrics> sys = NodeReader.readTables(FakeNodes.cassandra41G1("10.0.0.1", "h1").server, "4.1.5", "system");
        assertThat(sys).extracting(TableMetrics::table).containsExactly("local");
        List<TableMetrics> t311 = NodeReader.readTables(FakeNodes.cassandra311Cms("10.0.0.2", "h2").server, "3.11.16", "shop");
        assertThat(t311).singleElement().satisfies(t -> assertThat(t.sstableCount()).isEqualTo(20));
    }

    @Test
    void ringFromStorageService() {
        NodeReader.RingRead r41 = NodeReader.readRing(FakeNodes.cassandra41G1("10.0.0.1", "h1").server, "4.1.5", "shop");
        assertThat(r41.partitioner()).endsWith("Murmur3Partitioner");
        assertThat(r41.endpointByToken()).containsEntry("-100", "10.0.0.1").containsEntry("50", "10.0.0.3");
        assertThat(r41.ownershipPct().get("10.0.0.1")).isCloseTo(50.0, within(1e-4));
        assertThat(r41.effectivePct()).containsEntry("10.0.0.2", 100.0);
        NodeReader.RingRead r311 = NodeReader.readRing(FakeNodes.cassandra311Cms("10.0.0.2", "h2").server, "3.11.16", "shop");
        assertThat(r311.ownershipPct().get("10.0.0.3")).isCloseTo(20.0, within(1e-4));
        assertThat(r311.effectivePct()).containsKeys("10.0.0.1", "10.0.0.2");
    }

    @Test
    void addressNormalisation() {
        assertThat(Mbeans.bareAddress("/10.0.0.1:7000")).isEqualTo("10.0.0.1");
        assertThat(Mbeans.bareAddress("node1/10.0.0.1")).isEqualTo("10.0.0.1");
        assertThat(Mbeans.bareAddress("[::1]:7000")).isEqualTo("::1");
        assertThat(Mbeans.bareAddress("fe80::1")).isEqualTo("fe80::1");
        assertThat(Mbeans.bareAddress(FakeNodes.inet("10.0.0.9"))).isEqualTo("10.0.0.9");
    }
}
