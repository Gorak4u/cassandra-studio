package com.cassandrastudio.engine.diag;

import com.cassandrastudio.engine.ssh.NodeShell;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tombstone and large-partition warnings from a node's system.log (PRF-2), read over SSH with
 * grep. Recognises the 3.11, 4.x and 5.0 wordings:
 * <pre>
 * WARN  [ReadStage-2] 2026-10-09 10:00:00,123 ReadCommand.java:569 - Read 0 live rows and 1500 tombstone cells for query SELECT ... FROM ks.t WHERE ... (see tombstone_warn_threshold)...
 * WARN  [CompactionExecutor:3] 2026-10-09 10:00:00,123 BigTableWriter.java:211 - Writing large partition ks/t:key (123.456MiB) to sstable /var/lib/...
 * WARN  [CompactionExecutor:3] 2026-10-09 10:00:00,123 BigTableWriter.java:211 - Compacting large partition ks/t:key (123456789 bytes)
 * ERROR [ReadStage-1] 2026-10-09 10:00:00,123 StorageProxy.java:... - Scanned over 100001 tombstones during query '...' (last scanned row token was ...); query aborted
 * </pre>
 */
public final class LogWarnings {
    private LogWarnings() {}

    public static final String DEFAULT_LOG = "/var/log/cassandra/system.log";
    static final String NO_LOG = "__STUDIO_NO_LOG__";

    /** The grep pattern (ERE) run on the node. */
    static final String GREP = "Read [0-9]+ live rows and [0-9]+ tombstone cells|Writing large partition"
            + "|Compacting large partition|Scanned over [0-9]+ tombstones";

    public enum Kind { TOMBSTONE_WARN, TOMBSTONE_ABORT, LARGE_PARTITION_WRITE, LARGE_PARTITION_COMPACT }

    /**
     * One warning. {@code tombstones}/{@code liveRows} for tombstone kinds, {@code partitionKey}
     * and {@code sizeBytes} for large partitions; {@code time} as logged (node local time).
     */
    public record Warning(String node, String time, String level, Kind kind, String keyspace, String table,
                          Long tombstones, Long liveRows, String partitionKey, Long sizeBytes, String detail) {}

    public record NodeLog(String node, String path, String error, int lines) {}

    public record View(List<Warning> warnings, List<NodeLog> nodes) {}

    /** Shell command: the newest {@code limit} matching lines, or a marker when the log is unreadable. */
    static String command(String path, int limit) {
        String p = NodeShell.quote(path);
        return "if [ -r " + p + " ]; then grep -aE -- " + NodeShell.quote(GREP) + " " + p + " | tail -n " + limit
                + "; else echo " + NO_LOG + "; fi";
    }

    private static final Pattern LINE = Pattern.compile(
            "^\\s*(?<level>[A-Z]+)\\s+\\[(?<thread>[^\\]]*)]\\s+(?<ts>\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[,.]\\d{1,3})?)\\s+\\S+\\s+-\\s+(?<msg>.*)$");
    private static final Pattern TOMB = Pattern.compile(
            "Read (\\d+) live rows and (\\d+) tombstone cells for query (.*?)(?:\\(see tombstone_warn_threshold\\).*)?$");
    private static final Pattern ABORT = Pattern.compile("Scanned over (\\d+) tombstones during query '?(.*?)'? \\(last scanned");
    private static final Pattern LARGE = Pattern.compile(
            "(Writing|Compacting) large partition ([^/\\s]+)/([^:\\s]+):(.*) \\(([^()]+)\\)(?: to sstable .*)?\\s*$");
    private static final Pattern FROM = Pattern.compile("(?i)\\bFROM\\s+\"?([\\w]+)\"?\\.\"?([\\w]+)\"?");
    private static final Pattern SIZE = Pattern.compile("([\\d.]+)\\s*([KMGT]i?B|bytes|B)?", Pattern.CASE_INSENSITIVE);

    /** Parses grep output of one node; lines that match none of the known wordings are skipped. */
    static List<Warning> parse(String node, String output) {
        List<Warning> out = new ArrayList<>();
        if (output == null) return out;
        for (String raw : output.split("\\R")) {
            Matcher m = LINE.matcher(raw);
            if (!m.matches()) continue;
            String msg = m.group("msg").trim();
            String ts = m.group("ts");
            String level = m.group("level");
            Matcher t = TOMB.matcher(msg);
            if (t.find()) {
                String q = t.group(3).trim();
                String[] kt = table(q);
                out.add(new Warning(node, ts, level, Kind.TOMBSTONE_WARN, kt[0], kt[1], Long.parseLong(t.group(2)),
                        Long.parseLong(t.group(1)), null, null, trim(q)));
                continue;
            }
            Matcher a = ABORT.matcher(msg);
            if (a.find()) {
                String[] kt = table(a.group(2));
                out.add(new Warning(node, ts, level, Kind.TOMBSTONE_ABORT, kt[0], kt[1], Long.parseLong(a.group(1)), null,
                        null, null, trim(a.group(2))));
                continue;
            }
            Matcher l = LARGE.matcher(msg);
            if (l.find()) {
                out.add(new Warning(node, ts, level, "Writing".equals(l.group(1)) ? Kind.LARGE_PARTITION_WRITE
                        : Kind.LARGE_PARTITION_COMPACT, l.group(2), l.group(3), null, null, l.group(4),
                        sizeBytes(l.group(5)), null));
            }
        }
        return out;
    }

    private static String[] table(String query) {
        Matcher f = FROM.matcher(query);
        return f.find() ? new String[] {f.group(1), f.group(2)} : new String[] {null, null};
    }

    private static String trim(String s) {
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }

    /** "123456789 bytes", "110.123MiB", "1.5GB" to bytes (binary units, as Cassandra prints them). */
    static Long sizeBytes(String s) {
        Matcher m = SIZE.matcher(s.trim());
        if (!m.find()) return null;
        double v;
        try {
            v = Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        String u = m.group(2) == null ? "" : m.group(2).toUpperCase(Locale.ROOT);
        long mult = u.startsWith("K") ? 1L << 10 : u.startsWith("M") ? 1L << 20 : u.startsWith("G") ? 1L << 30
                : u.startsWith("T") ? 1L << 40 : 1;
        return Math.round(v * mult);
    }

    /** Newest first over all nodes. */
    static List<Warning> sorted(List<Warning> all) {
        List<Warning> out = new ArrayList<>(all);
        out.sort(Comparator.comparing(Warning::time, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    // ---- estate tombstone-scan.sh (cassandra-control-repo, cass-ops tombstone-scan) ----------

    public static final String DEFAULT_SCAN = "/usr/local/bin/tombstone-scan.sh";
    static final String NO_SCRIPT = "__STUDIO_NO_SCRIPT__";

    /** One output row: overview (keyspace, table, avg live, avg/max tombstones per slice) or deep dive (sstable). */
    public record ScanRow(List<String> cells) {}

    public record ScanResult(String node, String command, boolean available, List<String> header, List<ScanRow> rows,
                             String output) {}

    static String scanCommand(String script, String keyspace, String table) {
        String s = NodeShell.quote(script);
        StringBuilder cmd = new StringBuilder("if [ -x ").append(s).append(" ]; then ").append(s);
        if (keyspace != null) cmd.append(" -k ").append(NodeShell.quote(keyspace));
        if (table != null) cmd.append(" -t ").append(NodeShell.quote(table));
        return cmd.append(" 2>&1; else echo ").append(NO_SCRIPT).append("; fi").toString();
    }

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]|\\\\e\\[[0-9;]*m");

    /** Parses the script's `column -t` table: the header line starts with KEYSPACE or SSTABLE_NAME. */
    static ScanResult parseScan(String node, String command, String output) {
        String clean = ANSI.matcher(output == null ? "" : output).replaceAll("");
        if (clean.contains(NO_SCRIPT)) return new ScanResult(node, command, false, List.of(), List.of(), "");
        List<String> header = List.of();
        List<ScanRow> rows = new ArrayList<>();
        for (String line : clean.split("\\R")) {
            String l = line.trim();
            if (l.isEmpty()) continue;
            String[] cells = l.split("\\s+");
            if (header.isEmpty()) {
                if (cells[0].equals("KEYSPACE") || cells[0].equals("SSTABLE_NAME")) header = List.of(cells);
                continue;
            }
            if (cells.length == header.size()) rows.add(new ScanRow(List.of(cells)));
        }
        return new ScanResult(node, command, true, header, rows, clean.length() > 20_000 ? clean.substring(0, 20_000) : clean);
    }
}
