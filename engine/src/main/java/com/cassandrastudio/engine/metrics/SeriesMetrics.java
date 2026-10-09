package com.cassandrastudio.engine.metrics;

import com.cassandrastudio.engine.metrics.MonitoringModel.ClientRequests;
import com.cassandrastudio.engine.metrics.MonitoringModel.Latency;
import com.cassandrastudio.engine.metrics.MonitoringModel.NodeSnapshot;
import com.cassandrastudio.engine.metrics.MonitoringModel.ThreadPool;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The metric ids the series endpoint accepts (SERIES_METRICS in ui/src/lib/monitoringTypes.ts)
 * and how each is derived from a poll. Counters become per-second rates since the previous
 * poll, so averaging them into minute points stays meaningful.
 */
public final class SeriesMetrics {
    private SeriesMetrics() {}

    /** What one node's poll offers to the extractors. */
    public record Sample(NodeSnapshot now, NodeSnapshot previous, double secondsSincePrevious, Double gcPauseMs) {}

    public record Def(String id, String unit, Function<Sample, Double> extract) {}

    private static final Map<String, Def> DEFS = new LinkedHashMap<>();

    static {
        def("heap.used", "bytes", s -> d(s.now().heapUsedBytes()));
        def("heap.max", "bytes", s -> d(s.now().heapMaxBytes()));
        def("gc.time_pct", "pct", s -> s.now().gcTimePct());
        def("gc.pause_ms", "ms", Sample::gcPauseMs);
        def("load.bytes", "bytes", s -> d(s.now().loadBytes()));
        def("cpu.process_pct", "pct", s -> s.now().cpuProcessPct());
        def("client.read.rate", "per_sec", s -> latency(s.now(), true, Latency::ratePerSec));
        def("client.write.rate", "per_sec", s -> latency(s.now(), false, Latency::ratePerSec));
        def("client.read.p99_us", "us", s -> latency(s.now(), true, Latency::p99Micros));
        def("client.write.p99_us", "us", s -> latency(s.now(), false, Latency::p99Micros));
        def("client.read.p50_us", "us", s -> latency(s.now(), true, Latency::p50Micros));
        def("client.write.p50_us", "us", s -> latency(s.now(), false, Latency::p50Micros));
        def("client.timeouts", "per_sec", s -> rate(s, SeriesMetrics::timeouts));
        def("client.unavailables", "per_sec", s -> rate(s, SeriesMetrics::unavailables));
        def("compaction.pending", "count", s -> d(s.now().pendingCompactions()));
        def("hints.in_progress", "count", s -> d(s.now().hintsInProgress()));
        def("dropped.total", "per_sec", s -> rate(s, SeriesMetrics::droppedTotal));
        def("threadpool.pending_total", "count", s -> poolSum(s.now(), ThreadPool::pending));
        def("threadpool.blocked_total", "count", s -> poolSum(s.now(), ThreadPool::blocked));
    }

    /** Every metric id in API order. */
    public static final Map<String, Def> ALL = Collections.unmodifiableMap(DEFS);

    private static void def(String id, String unit, Function<Sample, Double> f) {
        DEFS.put(id, new Def(id, unit, f));
    }

    public static boolean known(String id) {
        return id != null && ALL.containsKey(id);
    }

    private static Double d(Long v) {
        return v == null ? null : v.doubleValue();
    }

    private static Double latency(NodeSnapshot n, boolean read, Function<Latency, Double> f) {
        ClientRequests c = n.clientRequests();
        Latency l = c == null ? null : read ? c.read() : c.write();
        return l == null ? null : f.apply(l);
    }

    private static Double rate(Sample s, Function<NodeSnapshot, Long> counter) {
        if (s.previous() == null || s.secondsSincePrevious() <= 0) return null;
        Long now = counter.apply(s.now()), before = counter.apply(s.previous());
        if (now == null || before == null || now < before) return null;
        return (now - before) / s.secondsSincePrevious();
    }

    static Long timeouts(NodeSnapshot n) {
        ClientRequests c = n.clientRequests();
        return c == null ? null : sum(c.readTimeouts(), c.writeTimeouts());
    }

    static Long unavailables(NodeSnapshot n) {
        ClientRequests c = n.clientRequests();
        return c == null ? null : sum(c.readUnavailables(), c.writeUnavailables());
    }

    static Long droppedTotal(NodeSnapshot n) {
        return n.dropped() == null ? null : n.dropped().values().stream().mapToLong(Long::longValue).sum();
    }

    private static Long sum(Long a, Long b) {
        return a == null && b == null ? null : (a == null ? 0 : a) + (b == null ? 0 : b);
    }

    private static Double poolSum(NodeSnapshot n, Function<ThreadPool, Long> f) {
        List<ThreadPool> pools = n.threadPools();
        if (pools == null) return null;
        return (double) pools.stream().map(f).filter(v -> v != null).mapToLong(Long::longValue).sum();
    }
}
