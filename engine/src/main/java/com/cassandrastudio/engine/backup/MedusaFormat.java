package com.cassandrastudio.engine.backup;

import com.cassandrastudio.engine.ssh.NodeShell;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cassandra Medusa's CLI over SSH. Medusa has no JSON output for listing, so its text is parsed:
 *
 * <pre>
 * medusa list-backups:
 *   studio-20261009-0200 (started: 2026-10-09 02:00:01, finished: 2026-10-09 02:04:47)
 *   nightly-1009 (started: 2026-10-09 03:00:00, finished: Incomplete) [Incomplete!]
 * medusa status --backup-name X:
 *   X
 *   - Started: 2026-10-09 02:00:01, Finished: 2026-10-09 02:04:47
 *   - 3 nodes completed, 0 nodes incomplete, 0 nodes missing
 *   - 1180 files, 12.34 MB
 * </pre>
 * Times are printed in the node's local time (UTC assumed). Backups are cluster-wide by name.
 */
final class MedusaFormat {
    private MedusaFormat() {}

    static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,100}");
    private static final Pattern LISTED = Pattern.compile(
            "^(\\S+) \\(started: ([^,]+), finished: ([^)]+)\\)(.*)$");
    private static final Pattern NODES = Pattern.compile("(\\d+) nodes? completed, (\\d+) nodes? incomplete, (\\d+) nodes? missing");
    private static final Pattern FILES = Pattern.compile("-\\s*(\\d+) files, ([0-9.]+)\\s*([A-Za-z]+)");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    record Listed(String name, Long startedMs, Long finishedMs, boolean complete) {}

    record Status(Integer completed, Integer incomplete, Integer missing, Integer files, Long bytes) {}

    static String base(BackupSettings s) {
        BackupSettings e = s.effective();
        String cmd = s.prefix() + NodeShell.quote(e.medusaCommand());
        if (e.medusaConfig() != null) cmd += " --config-file " + NodeShell.quote(e.medusaConfig());
        return cmd;
    }

    static String listCommand(BackupSettings s) {
        return base(s) + " list-backups";
    }

    static String statusCommand(BackupSettings s, String name) {
        return base(s) + " status --backup-name " + NodeShell.quote(name);
    }

    /** {@code mode} full or differential (Medusa's incremental). */
    static String backupNodeCommand(BackupSettings s, String name, String mode) {
        return base(s) + " backup-node --backup-name " + NodeShell.quote(name) + " --mode " + mode;
    }

    static List<Listed> parseList(String out) {
        List<Listed> list = new ArrayList<>();
        for (String raw : out.split("\\R")) {
            Matcher m = LISTED.matcher(EstateFormat.stripAnsi(raw).strip());
            if (!m.matches()) continue;
            Long finished = time(m.group(3));
            boolean incomplete = finished == null || m.group(4).contains("Incomplete");
            list.add(new Listed(m.group(1), time(m.group(2)), finished, !incomplete));
        }
        return list;
    }

    static Status parseStatus(String out) {
        Integer c = null, i = null, mi = null, f = null;
        Long b = null;
        for (String raw : out.split("\\R")) {
            String line = EstateFormat.stripAnsi(raw).strip();
            Matcher n = NODES.matcher(line);
            if (n.find()) {
                c = Integer.parseInt(n.group(1));
                i = Integer.parseInt(n.group(2));
                mi = Integer.parseInt(n.group(3));
            }
            Matcher fm = FILES.matcher(line);
            if (fm.find()) {
                f = Integer.parseInt(fm.group(1));
                b = SnapshotJmx.parseSize(fm.group(2) + " " + fm.group(3));
            }
        }
        return new Status(c, i, mi, f, b);
    }

    static BackupEntry entry(Listed l, Status st) {
        String detail = l.complete() ? "finished" : "not finished on every node";
        if (st != null && st.completed() != null) {
            detail = st.completed() + " nodes completed, " + st.incomplete() + " incomplete, " + st.missing() + " missing";
        }
        return new BackupEntry(l.name(), "medusa", null, null, null, "unknown",
                l.finishedMs() != null ? l.finishedMs() : l.startedMs(), st == null ? null : st.bytes(), null,
                l.complete() ? BackupEntry.COMPLETE : BackupEntry.INCOMPLETE, detail,
                "Medusa storage (see medusa.ini)", "Medusa purge policy (max_backup_age / max_backup_count in medusa.ini)",
                null, "not reported by Medusa", null, null, st == null ? null : st.files(),
                "type (full/differential), schema version and per-node detail are not shown by medusa list-backups");
    }

    static Long time(String s) {
        if (s == null) return null;
        try {
            return LocalDateTime.parse(s.strip(), TIME).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** A Medusa backup name Studio generates: studio-yyyyMMdd-HHmmss (UTC). */
    static String defaultName(long nowMs) {
        return "studio-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
                .format(java.time.Instant.ofEpochMilli(nowMs).atOffset(ZoneOffset.UTC));
    }
}
