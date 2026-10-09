package com.cassandrastudio.engine.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.cassandrastudio.engine.jmx.JmxAccess.ExporterSample;
import com.cassandrastudio.engine.metrics.MonitoringModel.NodeSnapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExporterReaderTest {
    private static final NodeReader.Identity ID = new NodeReader.Identity("h1", "10.0.0.1", "dc1", "r1", "4.1.5");

    private static List<ExporterSample> scrape(double gcSeconds, double gcCount, double readCount) {
        return List.of(
                new ExporterSample("jvm_memory_used_bytes", Map.of("area", "heap"), 300),
                new ExporterSample("jvm_memory_used_bytes", Map.of("area", "nonheap"), 77),
                new ExporterSample("jvm_memory_max_bytes", Map.of("area", "heap"), 1000),
                new ExporterSample("jvm_info", Map.of("version", "17.0.10+7", "vendor", "Eclipse Adoptium"), 1),
                new ExporterSample("process_start_time_seconds", Map.of(), 1_000),
                new ExporterSample("process_open_fds", Map.of(), 321),
                new ExporterSample("jvm_gc_collection_seconds_count", Map.of("gc", "G1 Young Generation"), gcCount),
                new ExporterSample("jvm_gc_collection_seconds_sum", Map.of("gc", "G1 Young Generation"), gcSeconds),
                new ExporterSample("cassandra_compaction_pendingtasks", Map.of(), 4),
                new ExporterSample("cassandra_storage_totalhintsinprogress", Map.of(), 2),
                new ExporterSample("cassandra_table_livesstablecount", Map.of("table", ""), 17),
                new ExporterSample("cassandra_threadpools_pendingtasks", Map.of("path", "request", "threadpools", "ReadStage"), 6),
                new ExporterSample("cassandra_threadpools_currentlyblockedtasks", Map.of("path", "request", "threadpools", "ReadStage"), 1),
                new ExporterSample("cassandra_droppedmessage_dropped", Map.of("droppedmessage", "MUTATION"), 9),
                new ExporterSample("cassandra_clientrequest_latency", Map.of("clientrequest", "Read"), readCount),
                new ExporterSample("cassandra_clientrequest_latency", Map.of("clientrequest", "Read", "quantile", "0.99"), 1234),
                new ExporterSample("cassandra_clientrequest_timeouts", Map.of("clientrequest", "Write"), 3));
    }

    @Test
    void mapsStandardExporterNames() {
        ExporterReader r = new ExporterReader();
        NodeSnapshot n = r.read(scrape(1.0, 10, 100), ID, 2_000_000).snapshot();
        assertThat(n.route()).isEqualTo("jmx_exporter");
        assertThat(n.heapUsedBytes()).isEqualTo(300);
        assertThat(n.heapMaxBytes()).isEqualTo(1000);
        assertThat(n.javaVersion()).isEqualTo("17.0.10+7");
        assertThat(n.uptimeSec()).isEqualTo(1_000);
        assertThat(n.openFds()).isEqualTo(321);
        assertThat(n.pendingCompactions()).isEqualTo(4);
        assertThat(n.hintsInProgress()).isEqualTo(2);
        assertThat(n.liveSSTables()).isEqualTo(17);
        assertThat(n.threadPools()).singleElement().satisfies(p -> {
            assertThat(p.name()).isEqualTo("ReadStage");
            assertThat(p.pending()).isEqualTo(6);
            assertThat(p.blocked()).isEqualTo(1);
        });
        assertThat(n.dropped()).containsEntry("MUTATION", 9L);
        assertThat(n.clientRequests().read().p99Micros()).isEqualTo(1234.0);
        assertThat(n.clientRequests().read().ratePerSec()).isNull(); // first scrape
        assertThat(n.clientRequests().writeTimeouts()).isEqualTo(3);
        assertThat(n.loadBytes()).isNull(); // not in this scrape
        assertThat(n.gc()).singleElement().satisfies(g -> assertThat(g.timeMs()).isEqualTo(1000));

        NodeReader.Result second = r.read(scrape(1.5, 12, 600), ID, 2_010_000);
        assertThat(second.snapshot().gcTimePct()).isCloseTo(5.0, within(1e-9));
        assertThat(second.gcPauseMs()).isCloseTo(250.0, within(1e-9));
        assertThat(second.snapshot().clientRequests().read().ratePerSec()).isCloseTo(50.0, within(1e-9));
    }
}
