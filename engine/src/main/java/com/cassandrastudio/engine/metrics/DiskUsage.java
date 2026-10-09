package com.cassandrastudio.engine.metrics;

import com.cassandrastudio.engine.metrics.MonitoringModel.DataDir;
import java.util.ArrayList;
import java.util.List;

/**
 * Data directory sizes from {@code df} on the node (over SSH), since JMX does not expose free
 * space. POSIX output ({@code -P}) keeps one line per argument, in argument order.
 */
final class DiskUsage {
    private DiskUsage() {}

    /** {@code df -Pk -- 'dir' ...}; paths are single-quoted so spaces and shell characters stay literal. */
    static String command(List<DataDir> dirs) {
        StringBuilder b = new StringBuilder("df -Pk --");
        for (DataDir d : dirs) b.append(" '").append(d.path().replace("'", "'\\''")).append('\'');
        return b.toString();
    }

    /**
     * The dirs with total and free bytes filled from {@code df -Pk} output; a dir whose line is
     * missing or unreadable keeps nulls. Total is the usable size, used + available, as df's own
     * Capacity column counts it: blocks reserved for root (or held back by the host) are neither,
     * and counting them as used would raise disk alerts on a disk df shows as half empty.
     */
    static List<DataDir> apply(List<DataDir> dirs, String dfOutput) {
        List<long[]> rows = new ArrayList<>();
        for (String line : dfOutput.split("\\R")) {
            String[] f = line.trim().split("\\s+");
            // Filesystem 1024-blocks Used Available Capacity Mounted-on; the header has no numbers.
            if (f.length < 6) continue;
            try {
                long used = Long.parseLong(f[2]);
                long available = Long.parseLong(f[3]);
                rows.add(new long[] {used + available, available});
            } catch (NumberFormatException header) {
                // header line
            }
        }
        List<DataDir> out = new ArrayList<>(dirs.size());
        for (int i = 0; i < dirs.size(); i++) {
            DataDir d = dirs.get(i);
            out.add(i < rows.size() ? new DataDir(d.path(), rows.get(i)[0] * 1024, rows.get(i)[1] * 1024) : d);
        }
        return out;
    }
}
