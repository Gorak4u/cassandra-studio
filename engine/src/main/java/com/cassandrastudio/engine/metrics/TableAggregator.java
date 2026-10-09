package com.cassandrastudio.engine.metrics;

import com.cassandrastudio.engine.metrics.MonitoringModel.TableMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * MON-18 cluster view of a table: counts, disk space, SSTables and pending compactions are
 * summed over nodes; latencies, maxima and ratios are the worst node; mean partition size and
 * key cache hit rate are the mean over nodes that report them.
 */
final class TableAggregator {
    private TableAggregator() {}

    static List<TableMetrics> aggregate(List<List<TableMetrics>> perNode) {
        Map<String, List<TableMetrics>> byTable = new TreeMap<>();
        for (List<TableMetrics> node : perNode) {
            for (TableMetrics t : node) byTable.computeIfAbsent(t.keyspace() + "." + t.table(), k -> new ArrayList<>()).add(t);
        }
        List<TableMetrics> out = new ArrayList<>();
        for (List<TableMetrics> l : byTable.values()) {
            TableMetrics f = l.get(0);
            out.add(new TableMetrics(f.keyspace(), f.table(), sum(l, TableMetrics::readCount),
                    sum(l, TableMetrics::writeCount), max(l, TableMetrics::readLatencyP99Micros),
                    max(l, TableMetrics::writeLatencyP99Micros), sum(l, TableMetrics::liveDiskSpaceBytes),
                    sum(l, TableMetrics::totalDiskSpaceBytes), sum(l, TableMetrics::sstableCount),
                    meanLong(l, TableMetrics::meanPartitionSizeBytes), maxLong(l, TableMetrics::maxPartitionSizeBytes),
                    max(l, TableMetrics::tombstonesPerReadP99), max(l, TableMetrics::sstablesPerReadP99),
                    max(l, TableMetrics::bloomFilterFalseRatio), sum(l, TableMetrics::pendingCompactions),
                    mean(l, TableMetrics::keyCacheHitRate)));
        }
        return out;
    }

    private static Long sum(List<TableMetrics> l, Function<TableMetrics, Long> f) {
        Long total = null;
        for (TableMetrics t : l) {
            Long v = f.apply(t);
            if (v != null) total = (total == null ? 0 : total) + v;
        }
        return total;
    }

    private static Long maxLong(List<TableMetrics> l, Function<TableMetrics, Long> f) {
        return l.stream().map(f).filter(v -> v != null).max(Long::compare).orElse(null);
    }

    private static Double max(List<TableMetrics> l, Function<TableMetrics, Double> f) {
        return l.stream().map(f).filter(v -> v != null).max(Double::compare).orElse(null);
    }

    private static Double mean(List<TableMetrics> l, Function<TableMetrics, Double> f) {
        var stats = l.stream().map(f).filter(v -> v != null).mapToDouble(Double::doubleValue).summaryStatistics();
        return stats.getCount() == 0 ? null : stats.getAverage();
    }

    private static Long meanLong(List<TableMetrics> l, Function<TableMetrics, Long> f) {
        var stats = l.stream().map(f).filter(v -> v != null).mapToLong(Long::longValue).summaryStatistics();
        return stats.getCount() == 0 ? null : Math.round(stats.getAverage());
    }
}
