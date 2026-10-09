package com.cassandrastudio.engine.diag;

import com.cassandrastudio.engine.util.ApiException;

/**
 * Per-connection diagnostics settings (settings table, key {@code diag.settings/<connectionId>}).
 * Null fields mean the default.
 *
 * @param logPath              system.log on the nodes, read over SSH
 * @param largePartitionMb     flag a table whose max partition is larger (MiB)
 * @param tombstonesP99        flag a table whose p99 tombstones scanned per read is higher
 * @param tombstoneScanScript  the estate's tombstone-scan.sh on the nodes
 */
public record DiagSettings(String logPath, Long largePartitionMb, Double tombstonesP99, String tombstoneScanScript) {
    public static final DiagSettings DEFAULTS = new DiagSettings(LogWarnings.DEFAULT_LOG, 100L, 1000.0,
            LogWarnings.DEFAULT_SCAN);

    public DiagSettings effective() {
        return new DiagSettings(blank(logPath) ? DEFAULTS.logPath : logPath.trim(),
                largePartitionMb == null ? DEFAULTS.largePartitionMb : largePartitionMb,
                tombstonesP99 == null ? DEFAULTS.tombstonesP99 : tombstonesP99,
                blank(tombstoneScanScript) ? DEFAULTS.tombstoneScanScript : tombstoneScanScript.trim());
    }

    /** Throws 400 with the first problem. */
    public DiagSettings validated() {
        DiagSettings e = effective();
        if (!e.logPath.startsWith("/")) throw ApiException.badRequest("logPath must be an absolute path");
        if (!e.tombstoneScanScript.startsWith("/")) throw ApiException.badRequest("tombstoneScanScript must be an absolute path");
        if (e.logPath.length() > 1024 || e.tombstoneScanScript.length() > 1024) throw ApiException.badRequest("Path too long");
        if (e.largePartitionMb < 1 || e.largePartitionMb > 1_000_000) {
            throw ApiException.badRequest("largePartitionMb must be between 1 and 1000000");
        }
        if (e.tombstonesP99 < 1 || e.tombstonesP99 > 10_000_000) {
            throw ApiException.badRequest("tombstonesP99 must be between 1 and 10000000");
        }
        return this;
    }

    TableHistograms.Thresholds thresholds() {
        DiagSettings e = effective();
        return new TableHistograms.Thresholds(e.largePartitionMb * 1024 * 1024, e.tombstonesP99);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
