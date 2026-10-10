package com.cassandrastudio.engine.perf;

import static org.assertj.core.api.Assertions.assertThat;

import com.cassandrastudio.engine.metrics.MonitoringModel.ClusterSnapshot;
import com.cassandrastudio.engine.metrics.MonitoringModel.NodeSnapshot;
import com.cassandrastudio.engine.metrics.MonitoringModel.Ring;
import com.cassandrastudio.engine.metrics.MonitoringModel.Series;
import com.cassandrastudio.engine.metrics.ScaleHarness;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * NFR-PERF / NFR-SCALE: a synthetic 500-node cluster (5 DCs) through MonitoringService at the
 * default 10 s interval. Every node is a full in-process 4.1 MBean set, so the numbers measure the
 * engine's own cost (reading, merging, health rules, history), not the network. Measurements are
 * printed as {@code PERF ...} lines (docs/perf.md); the assertions are the budgets.
 */
class MonitoringScaleTest {
    static final int NODES = 500;
    static final long INTERVAL_MS = 10_000;

    @Test
    void fiveHundredNodesPollWellWithinTheInterval() {
        try (ScaleHarness h = new ScaleHarness(NODES, 5)) {
            h.latencyMs(20); // a LAN-like JMX connect cost per node
            long startNs = System.nanoTime();
            h.start(INTERVAL_MS);
            long firstMs = (System.nanoTime() - startNs) / 1_000_000;
            com.sun.management.OperatingSystemMXBean os =
                    (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            ThreadMXBean threads = ManagementFactory.getThreadMXBean();
            int sessionsBefore = h.jmxSessions();
            List<Long> durations = new ArrayList<>();
            long cpuBefore = os.getProcessCpuTime();
            int peakThreads = 0;
            int polls = 6;
            ClusterSnapshot last = null;
            for (int i = 0; i < polls; i++) {
                long t0 = System.nanoTime();
                last = h.poll(INTERVAL_MS);
                durations.add((System.nanoTime() - t0) / 1_000_000);
                peakThreads = Math.max(peakThreads, threads.getThreadCount());
            }
            double cpuMsPerPoll = (os.getProcessCpuTime() - cpuBefore) / 1e6 / polls;
            int sessionsPerPoll = (h.jmxSessions() - sessionsBefore) / polls;
            System.gc();
            long heapMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
            long max = durations.stream().mapToLong(Long::longValue).max().orElseThrow();
            double avg = durations.stream().mapToLong(Long::longValue).average().orElseThrow();
            System.out.printf("PERF monitoring 500 nodes: first poll %d ms, poll avg %.0f ms max %d ms, CPU %.0f ms/poll"
                            + " (%.1f%% of one core at 10 s), peak threads %d, max concurrent JMX reads %d, JMX sessions/poll %d,"
                            + " heap after GC %d MB (incl. 500 fake MBean servers)%n",
                    firstMs, avg, max, cpuMsPerPoll, cpuMsPerPoll / INTERVAL_MS * 100, peakThreads, h.maxConcurrentReads(),
                    sessionsPerPoll, heapMb);

            assertThat(last.nodes()).hasSize(NODES);
            assertThat(last.nodes()).allSatisfy(n -> assertThat(n.error()).isNull());
            assertThat(max).isLessThan(INTERVAL_MS / 2); // well inside the interval, even on a busy CI host
            assertThat(sessionsPerPoll).isEqualTo(NODES); // one JMX session per node per poll (NFR-PERF)
            assertThat(h.maxConcurrentReads()).isLessThanOrEqualTo(64);
            assertThat(peakThreads).isLessThan(200); // virtual threads: no thread per node
        }
    }

    @Test
    void twentyFourHoursOfHistoryForFiveHundredNodesIsBoundedAndQuick() {
        try (ScaleHarness h = new ScaleHarness(NODES, 5)) {
            h.start(INTERVAL_MS);
            h.fillHistory(24, INTERVAL_MS);
            long bytes = h.historyBytes();
            int points = h.historyPoints();
            long t0 = System.nanoTime();
            Series s = h.monitoring.series(h.connectionId, "heap.used", null, h.now() - 24 * 3_600_000L, h.now());
            long queryMs = (System.nanoTime() - t0) / 1_000_000;
            int returned = s.pointsByNode().values().stream().mapToInt(List::size).sum();
            System.out.printf("PERF history 500 nodes x 24 h x %d metrics: %d points, ~%d MB; 24 h series query of one metric"
                            + " (all nodes) %d ms, %d points%n",
                    com.cassandrastudio.engine.metrics.SeriesMetrics.ALL.size(), points, bytes / (1024 * 1024), queryMs, returned);
            assertThat(s.pointsByNode()).hasSize(NODES);
            assertThat(bytes).isLessThan(160L * 1024 * 1024);
            assertThat(queryMs).isLessThan(3_000);
        }
    }

    @Test
    void hungNodesNeverStallThePollOrTheOnDemandViews() {
        try (ScaleHarness h = new ScaleHarness(NODES, 5)) {
            Set<String> hung = h.hung;
            for (int i = 0; i < NODES; i += 5) hung.add(h.addresses.get(i)); // 100 nodes, node 0 included
            h.hangMs = 9_000; // each hung read gives up after 9 s (longer than the 8 s node timeout), then is retried
            long t0 = System.nanoTime();
            long timeoutMs = h.start(INTERVAL_MS);
            long firstMs = (System.nanoTime() - t0) / 1_000_000;
            t0 = System.nanoTime();
            ClusterSnapshot second = h.poll(INTERVAL_MS);
            long secondMs = (System.nanoTime() - t0) / 1_000_000;
            long ok = second.nodes().stream().filter(n -> n.error() == null).count();
            // the hung reads have ended by now: this poll retries them, after the healthy nodes
            sleep(1_500);
            t0 = System.nanoTime();
            ClusterSnapshot third = h.poll(INTERVAL_MS);
            long thirdMs = (System.nanoTime() - t0) / 1_000_000;
            long okThird = third.nodes().stream().filter(n -> n.error() == null).count();

            t0 = System.nanoTime();
            Ring ring = h.monitoring.ring(h.connectionId, "shop");
            long ringMs = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf("PERF 100 of 500 nodes hung: first poll %d ms (node timeout %d ms), next poll %d ms with %d"
                            + " nodes read, poll retrying the hung nodes %d ms with %d read, ring %d ms%n",
                    firstMs, timeoutMs, secondMs, ok, thirdMs, okThird, ringMs);

            assertThat(firstMs).isLessThan(timeoutMs + 3_000);
            assertThat(secondMs).isLessThan(timeoutMs + 1_000);
            assertThat(ok).isEqualTo(NODES - hung.size()); // healthy nodes are all read despite the hung ones
            assertThat(second.nodes().stream().filter(n -> hung.contains(n.address())).map(NodeSnapshot::error))
                    .allSatisfy(e -> assertThat(e).isNotNull());
            assertThat(thirdMs).isLessThan(timeoutMs + 1_000);
            assertThat(okThird).isEqualTo(NODES - hung.size());
            assertThat(ringMs).isLessThan(2_000); // a node read fine by the last poll answers the ring
            assertThat(ring.datacenters()).hasSize(5);
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
