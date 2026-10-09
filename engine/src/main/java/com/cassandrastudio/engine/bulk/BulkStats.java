package com.cassandrastudio.engine.bulk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Live counters of one bulk job, read by the progress line and GET .../bulk/jobs. */
public final class BulkStats {
    public final String kind;
    public final String path;
    public final String target;
    public final long startNanos = System.nanoTime();
    public final AtomicLong rowsRead = new AtomicLong();
    public final AtomicLong rowsWritten = new AtomicLong();
    public final AtomicLong rejected = new AtomicLong();
    public final AtomicLong bytes = new AtomicLong();
    public final AtomicLong rangesDone = new AtomicLong();
    public final AtomicLong rangesFailed = new AtomicLong();
    public volatile long rangesTotal;
    public volatile long totalBytes;
    public volatile Long estimatedRows;
    public volatile String errorFile;
    public volatile String rejectFile;
    public volatile boolean dryRun;
    private volatile long endNanos;

    public BulkStats(String kind, String path, String target) {
        this.kind = kind;
        this.path = path;
        this.target = target;
    }

    public void finish() {
        if (endNanos == 0) endNanos = System.nanoTime();
    }

    public double elapsedSeconds() {
        long end = endNanos == 0 ? System.nanoTime() : endNanos;
        return Math.max(1e-3, (end - startNanos) / 1e9);
    }

    /** Rows per second over the whole run: rows written (unload: rows written to the file). */
    public long rate() {
        return Math.round(rowsWritten.get() / elapsedSeconds());
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("path", path);
        m.put("target", target);
        m.put("rowsRead", rowsRead.get());
        m.put("rowsWritten", rowsWritten.get());
        m.put("rejected", rejected.get());
        m.put("bytes", bytes.get());
        m.put("totalBytes", totalBytes == 0 ? null : totalBytes);
        m.put("rangesDone", rangesDone.get());
        m.put("rangesFailed", rangesFailed.get());
        m.put("rangesTotal", rangesTotal == 0 ? null : rangesTotal);
        m.put("estimatedRows", estimatedRows);
        m.put("rowsPerSecond", rate());
        m.put("elapsedMs", Math.round(elapsedSeconds() * 1000));
        m.put("errorFile", errorFile);
        m.put("rejectFile", rejectFile);
        m.put("dryRun", dryRun);
        return m;
    }

    static String bytesText(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KiB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.1f MiB", b / (1024.0 * 1024));
        return String.format("%.2f GiB", b / (1024.0 * 1024 * 1024));
    }
}
