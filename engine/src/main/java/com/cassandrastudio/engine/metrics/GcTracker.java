package com.cassandrastudio.engine.metrics;

import com.cassandrastudio.engine.metrics.MonitoringModel.GcCollector;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MON-16 GC pressure from cumulative collector counters: share of wall time spent in GC and
 * the mean pause between two polls. Only stop-the-world collectors count when the node has any
 * (see {@link MetricCatalog#collector}). The first poll, and the poll after a restart (counters
 * went down), have no baseline and give null.
 */
final class GcTracker {
    private Map<String, long[]> previous = Map.of();
    private long previousAtMs = -1;

    record Delta(Double timePct, Double pauseMs) {}

    synchronized Delta update(List<GcCollector> collectors, long nowMs) {
        if (collectors == null || collectors.isEmpty()) {
            reset();
            return new Delta(null, null);
        }
        boolean anyPause = collectors.stream().anyMatch(c -> MetricCatalog.collector(c.name()).pause());
        Map<String, long[]> current = new HashMap<>();
        long dTime = 0, dCount = 0;
        double worstMeanPause = 0;
        boolean baseline = previousAtMs >= 0 && nowMs > previousAtMs;
        for (GcCollector c : collectors) {
            if (c.count() == null || c.timeMs() == null) continue;
            current.put(c.name(), new long[] {c.count(), c.timeMs()});
            if (anyPause && !MetricCatalog.collector(c.name()).pause()) continue;
            long[] prev = previous.get(c.name());
            if (prev == null || c.count() < prev[0] || c.timeMs() < prev[1]) {
                baseline = false;
                continue;
            }
            long dc = c.count() - prev[0], dt = c.timeMs() - prev[1];
            dTime += dt;
            dCount += dc;
            if (dc > 0) worstMeanPause = Math.max(worstMeanPause, (double) dt / dc);
        }
        Delta d = baseline
                ? new Delta(Math.min(100.0, 100.0 * dTime / (nowMs - previousAtMs)), dCount == 0 ? 0.0 : worstMeanPause)
                : new Delta(null, null);
        previous = current;
        previousAtMs = nowMs;
        return d;
    }

    synchronized void reset() {
        previous = Map.of();
        previousAtMs = -1;
    }
}
