package com.cassandrastudio.engine.gclog;

import java.util.List;
import java.util.Map;

/**
 * The GC report the API returns (docs/api/gclog.md): summary (GCL-3), chart data (GCL-2),
 * findings (GCL-4) and the events. Mirrored by ui/src/panels/gclog/gclogTypes.ts.
 */
public record GcReport(String id, String name, Source source, long createdAtMs, LogInfo log, Range range,
                       Summary summary, Series series, List<Finding> findings, List<GcEvent> events,
                       boolean eventsTruncated) {

    /** Where the log came from: ssh (node + files) or upload (file name). */
    public record Source(String kind, String node, List<SourceFile> files) {}

    public record SourceFile(String path, long sizeBytes, boolean truncated) {}

    /**
     * @param timeAxis wall (x = seconds since {@code startTs}) or uptime (x = JVM uptime seconds)
     */
    public record LogInfo(String format, String collector, String jvmVersion, Integer javaMajor, String jvmFlags,
                          Long heapMaxK, Long regionSizeK, String timeAxis, Long startTs, double startX, double endX,
                          long lines, long bytes, int eventCount, List<String> warnings) {}

    /** The analysed window, in x seconds; the whole log when the request had no range. */
    public record Range(double fromX, double toX, boolean whole) {}

    public record Stats(long count, double totalMs, double avgMs, double minMs, double maxMs, double p50Ms, double p95Ms,
                        double p99Ms) {}

    public record TypeStats(String type, String category, String kind, long count, double totalMs, double avgMs,
                            double maxMs) {}

    public record Bucket(String label, double fromMs, Double toMs, long count) {}

    public record Count(String name, long count) {}

    public record Summary(double durationSec, Stats pauses, List<Bucket> histogram, List<TypeStats> byType,
                          double gcTimePct, double throughputPct, long fullGcCount, List<Count> fullGcCauses,
                          List<TypeStats> concurrent, long humongousAllocations, Long humongousPeakK,
                          long toSpaceExhausted, long evacuationFailures, long concurrentModeFailures,
                          long promotionFailures, long degenerated, Stalls stalls, Safepoints safepoints, Heap heap,
                          Rate allocation, Rate promotion, List<Count> causes) {}

    public record Stalls(long count, double totalMs, double maxMs) {}

    public record Safepoints(long count, double totalMs, double maxMs, double ttspTotalMs, double ttspMaxMs,
                             double stoppedPct, List<TypeStats> reasons) {}

    /** KiB. */
    public record Heap(Long maxK, Long peakUsedK, Long peakAfterK, Long avgAfterK, Long peakOldAfterK,
                       Long peakMetaK) {}

    /** MB/s over the window, and the total in MB; null when the log does not have the numbers. */
    public record Rate(Double avgMBs, Double peakMBs, Double totalMB) {}

    /** Chart series as [x, value] pairs, bucketed by {@code bucketSec}. */
    public record Series(double bucketSec, List<double[]> gcTimePct, List<double[]> allocationMBs,
                         List<double[]> promotionMBs, List<double[]> safepointMs) {}

    /**
     * A tuning finding (GCL-4).
     *
     * @param severity critical, warning or info
     * @param evidence what in the log shows it (counts, times)
     * @param options  the JVM options involved, e.g. -Xmx, -XX:G1HeapRegionSize
     * @param file     where Cassandra sets them (jvm-server.options, jvm11-server.options ...)
     * @param atX      x of the worst occurrence, for the UI to jump to, or null
     */
    public record Finding(String id, String severity, String title, String detail, List<String> evidence,
                          String hint, List<String> options, String file, Double atX) {}

    static Map<String, Integer> severityOrder() {
        return Map.of("critical", 0, "warning", 1, "info", 2);
    }
}
