package com.cassandrastudio.engine.metrics;

import java.util.List;
import java.util.Map;

/**
 * Phase 2 contract: the JSON the monitoring API returns (docs/api/monitoring.md).
 * Mirrored field-for-field by ui/src/lib/monitoringTypes.ts. Units are in the names:
 * Bytes, Ms, Micros, PerSec, Pct (0-100). A value the node could not provide is null,
 * never 0, so the UI can show "n/a" instead of a misleading zero.
 */
public final class MonitoringModel {
    private MonitoringModel() {}

    public enum Level { GREEN, YELLOW, RED }

    /** MON-10 overall health with the reasons behind it. */
    public record Health(Level level, List<String> reasons) {}

    /** ALR-1: one active alert from the health rules. */
    public record Alert(String id, Level level, String rule, String node, String message, Double value,
                        Double threshold, long sinceEpochMs) {}

    public record GcCollector(String name, Long count, Long timeMs) {}

    public record ThreadPool(String name, Long active, Long pending, Long blocked, Long completed,
                             Long allTimeBlocked) {}

    /** p50/p95/p99/max in microseconds; rate is the one-minute rate. */
    public record Latency(Double p50Micros, Double p95Micros, Double p99Micros, Double maxMicros, Double ratePerSec,
                          Long count) {}

    /** Client requests seen by this node as coordinator (MON-17). */
    public record ClientRequests(Latency read, Latency write, Latency rangeSlice, Latency casRead, Latency casWrite,
                                 Long readTimeouts, Long writeTimeouts, Long readUnavailables, Long writeUnavailables,
                                 Long readFailures, Long writeFailures) {}

    public record DataDir(String path, Long totalBytes, Long freeBytes) {}

    /** MON-12: everything about one node at one poll. */
    public record NodeSnapshot(
            String hostId, String address, String datacenter, String rack,
            /** UN, DN, UJ, UL, UM ... like nodetool status. */
            String state,
            /** How JMX was reached ("ssh tunnel", "direct", "jmx_exporter"), or null. */
            String route,
            /** Set when the node could not be read; other fields are then null. */
            String error,
            String cassandraVersion, String javaVersion, String javaVendor, Long uptimeSec,
            Long loadBytes, Integer tokens,
            Long heapUsedBytes, Long heapMaxBytes, Long offHeapBytes,
            List<GcCollector> gc,
            /** Share of wall time spent in GC since the previous poll (MON-16 "pressure"). */
            Double gcTimePct,
            Double cpuProcessPct, Double cpuSystemPct, Long openFds, Long maxFds,
            Long pendingCompactions, Long activeCompactions, Long completedCompactions,
            Long hintsInProgress, Long totalHints,
            List<ThreadPool> threadPools,
            /** Dropped messages by verb (MUTATION, READ, ...): totals since start. */
            Map<String, Long> dropped,
            ClientRequests clientRequests,
            Long liveSSTables,
            List<DataDir> dataDirs) {}

    /** MON-10/11: the whole cluster at one poll. */
    public record ClusterSnapshot(long atEpochMs, int pollIntervalSec, Health health, List<NodeSnapshot> nodes,
                                  List<Alert> alerts, boolean schemaAgreement) {}

    /** MON-3 history: one metric, one series per node. */
    public record Series(String metric, String unit, Map<String, List<double[]>> pointsByNode) {}

    /** MON-13/14: ring per DC. */
    public record RingNode(String hostId, String address, String rack, String state, Long loadBytes,
                           List<String> tokens, Double ownershipPct, Double effectiveOwnershipPct) {}

    public record RingDc(String name, List<RingNode> nodes) {}

    public record Ring(String partitioner, String keyspace, List<RingDc> datacenters) {}

    /** MON-18: per table, summed over nodes (latencies: worst node). */
    public record TableMetrics(String keyspace, String table, Long readCount, Long writeCount,
                               Double readLatencyP99Micros, Double writeLatencyP99Micros,
                               Long liveDiskSpaceBytes, Long totalDiskSpaceBytes, Long sstableCount,
                               Long meanPartitionSizeBytes, Long maxPartitionSizeBytes,
                               Double tombstonesPerReadP99, Double sstablesPerReadP99, Double bloomFilterFalseRatio,
                               Long pendingCompactions, Double keyCacheHitRate) {}

    /** How monitoring reaches each node right now (status endpoint). */
    public record AccessStatus(String method, boolean polling, int pollIntervalSec,
                               List<NodeAccess> nodes) {}

    public record NodeAccess(String address, boolean ok, String route, String error, long lastPollEpochMs) {}
}
