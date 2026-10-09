package com.cassandrastudio.engine.backup;

import com.cassandrastudio.engine.ssh.NodeShell;
import com.cassandrastudio.engine.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The estate's backup scripts (control repo cassandra_pfpt/files: full-backup-to-s3.sh,
 * incremental-backup-to-s3.sh, restore-from-s3.sh, backup-status.sh, backup-storage-lib.sh):
 * the commands Studio runs and the parsing of what they print and store.
 *
 * <p>Object layout (all backends, via backup-storage-lib.sh): {@code <host>/<tag>/} per backup set,
 * tag {@code YYYY-MM-DD-HH-MM[-SS]} in the node's local time, holding
 * {@code <ks>/<table>.tar.gz.enc} archives, {@code backup_manifest.json} (written last by a full
 * backup), {@code schema_mapping.json} and {@code schema.cql}. A set without a manifest is a run
 * that did not finish.
 */
final class EstateFormat {
    private EstateFormat() {}

    static final String FULL_SCRIPT = "full-backup-to-s3.sh";
    static final String INCREMENTAL_SCRIPT = "incremental-backup-to-s3.sh";
    static final String STATUS_SCRIPT = "backup-status.sh";
    static final String RESTORE_SCRIPT = "restore-from-s3.sh";
    static final String STORAGE_LIB = "backup-storage-lib.sh";
    static final List<String> SCRIPTS = List.of(FULL_SCRIPT, INCREMENTAL_SCRIPT, STATUS_SCRIPT, RESTORE_SCRIPT,
            "verify-backup.sh", STORAGE_LIB);
    /** Newest backup sets listed per node; older ones are summarised in a note. */
    static final int LIST_LIMIT = 100;

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");
    private static final String RED = "\u001B[0;31m";
    private static final Pattern STAMP = Pattern.compile("^\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}] ");
    static final Pattern TAG = Pattern.compile("\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}(-\\d{2})?");
    private static final Pattern THROTTLE = Pattern.compile("[0-9]+(\\.[0-9]+)?[KMG]?B?/s");

    static String stripAnsi(String s) {
        return s == null ? null : ANSI.matcher(s).replaceAll("");
    }

    /** A script log line without colour codes and without its "[date time] " prefix. */
    static String clean(String line) {
        return STAMP.matcher(stripAnsi(line)).replaceFirst("").strip();
    }

    /** Tag time. Tags are the node's local time; nodes run UTC in the estate, so UTC is assumed. */
    static Long tagTime(String tag) {
        if (tag == null || !TAG.matcher(tag).matches()) return null;
        String t = tag.length() == 16 ? tag + "-00" : tag;
        try {
            return LocalDateTime.parse(t, DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"))
                    .toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static Long isoTime(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s.trim()).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // ---- run ---------------------------------------------------------------------------------

    /** The exact command for one node: what the preview shows and what runs. */
    static String runCommand(BackupSettings s, String mode, String throttle) {
        String script = s.script("incremental".equals(mode) ? INCREMENTAL_SCRIPT : FULL_SCRIPT);
        String cmd = s.prefix() + NodeShell.quote(script);
        if (throttle != null) cmd += " --throttle " + NodeShell.quote(throttle);
        return cmd;
    }

    /** Throttle rates the scripts accept (passed to AWS_MAX_BANDWIDTH): 50M/s, 1G/s, 500KB/s. */
    static boolean validThrottle(String t) {
        return t != null && THROTTLE.matcher(t).matches();
    }

    /** Follows a full or incremental backup's output and turns it into progress. */
    static final class Progress {
        private final boolean full;
        private final int expectedTables;
        int tablesDone;
        int tablesFailed;
        double fraction;
        String phase = "starting";
        String backupId;
        String summary;
        String lastError;
        boolean nothingToDo;

        /** @param expectedTables tables the run should upload (0 = unknown) */
        Progress(boolean full, int expectedTables) {
            this.full = full;
            this.expectedTables = expectedTables;
        }

        /** Takes one raw output line; returns it cleaned for the job log. */
        String accept(String raw) {
            String line = clean(raw);
            boolean error = raw.contains(RED);
            if (error && !line.isEmpty()) lastError = line;
            Matcher tag = Pattern.compile("Backup Timestamp \\(Tag\\): (\\S+)").matcher(line);
            if (tag.find()) backupId = tag.group(1);
            if (line.startsWith("Summary:")) summary = line;
            if (full) fullBackup(line, error);
            else incremental(line, error);
            return line;
        }

        private void fullBackup(String line, boolean error) {
            if (line.contains("credentials are valid")) step(0.03, "storage credentials checked");
            else if (line.startsWith("Taking full snapshot")) step(0.05, "taking snapshot");
            else if (line.startsWith("Full snapshot taken successfully")) step(0.12, "snapshot taken");
            else if (line.startsWith("--- Starting Parallel Backup of Tables")) step(0.15, "uploading tables");
            else if (line.startsWith("Successfully uploaded backup for") || line.startsWith("Successfully streamed backup for")) {
                tablesDone++;
                tableStep();
            } else if (error && (line.startsWith("Failed to") || line.startsWith("Streaming backup failed")
                    || line.startsWith("Uploaded "))) {
                tablesFailed++;
                tableStep();
            } else if (line.startsWith("--- Finished Parallel Backup of Tables")) step(0.85, "tables uploaded");
            else if (line.startsWith("Dumping cluster schema")) step(0.87, "dumping schema");
            else if (line.startsWith("Creating backup manifest")) step(0.9, "writing manifest");
            else if (line.startsWith("Manifest uploaded successfully")) step(0.95, "manifest uploaded");
            else if (line.contains("Backup Process Finished")) step(1.0, error ? "finished with errors" : "finished");
        }

        private void tableStep() {
            int n = tablesDone + tablesFailed;
            double share = expectedTables > 0 ? Math.min(1.0, n / (double) expectedTables) : 0;
            step(0.15 + 0.7 * share, n + (expectedTables > 0 ? " / " + expectedTables : "") + " tables uploaded"
                    + (tablesFailed > 0 ? ", " + tablesFailed + " failed" : ""));
        }

        private void incremental(String line, boolean error) {
            if (line.startsWith("--- Step 1")) step(0.05, "archiving incremental files");
            else if (line.startsWith("Processing incremental backup for")) {
                tablesDone++;
                step(Math.min(0.55, 0.05 + 0.01 * tablesDone), tablesDone + " tables archived");
            } else if (line.startsWith("No new incremental backup files found")
                    || line.startsWith("No new incremental files to archive")) {
                nothingToDo = true;
                summary = "No new incremental files: nothing to back up";
                step(1.0, "nothing to do");
            } else if (line.startsWith("--- Local Backup Finished")) step(0.6, "local archive written");
            else if (line.startsWith("--- Step 2")) step(0.65, "uploading");
            else if (line.startsWith("--- Upload Finished Successfully")) step(0.9, "uploaded");
            else if (line.startsWith("--- Step 3")) step(0.92, "cleaning up");
            else if (line.startsWith("--- Incremental Backup Process Finished")) step(1.0, "finished");
            if (error) phase = line;
        }

        private void step(double f, String p) {
            fraction = Math.max(fraction, Math.min(1.0, f));
            phase = p;
        }
    }

    // ---- catalogue ---------------------------------------------------------------------------

    /** Non-secret storage settings from /etc/backup/config.json. */
    record Config(String backend, String bucket, String localPath, String azureAccount, Integer retentionDays,
                  boolean lockEnabled, String lockMode, Integer lockDays) {}

    /** One {@code <host>/<tag>/} prefix. {@code bytes} null = backend cannot tell cheaply. */
    record BackupSet(String id, int objects, Long bytes, JsonNode manifest) {}

    record Probe(String error, Config config, String host, List<BackupSet> sets, boolean truncated) {}

    /**
     * A read-only listing that sources the estate's own backup-storage-lib.sh, so it works for every
     * backend the scripts support. Reads only the storage keys of config.json (never the passwords or
     * the encryption key). Root is needed for config.json, hence {@code sudo -n} by default.
     */
    static String probeCommand(BackupSettings s) {
        String lib = NodeShell.quote(s.script(STORAGE_LIB));
        String cfg = NodeShell.quote(s.effective().configFile());
        String script = String.join("\n",
                "set -u",
                "LIB=" + lib + "; CFG=" + cfg,
                "[ -f \"$LIB\" ] || { echo \"ERROR storage library not found at $LIB\"; exit 0; }",
                "[ -r \"$CFG\" ] || { echo \"ERROR cannot read $CFG\"; exit 0; }",
                "command -v jq >/dev/null 2>&1 || { echo 'ERROR jq is not installed'; exit 0; }",
                ". \"$LIB\"",
                "echo \"CONFIG $(jq -c '{backup_backend, s3_bucket_name, storage_local_path, storage_azure_account,"
                        + " s3_retention_period, s3_object_lock_enabled, s3_object_lock_mode, s3_object_lock_retention}' \"$CFG\")\"",
                "storage_init \"$CFG\" 2>/dev/null || { echo 'ERROR the storage backend in the config could not be initialised';"
                        + " exit 0; }",
                "h=$(hostname -s); echo \"HOST $h\"",
                "all=$(storage_list_prefixes \"$h\" | grep -E '^[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{2}-[0-9]{2}(-[0-9]{2})?$'"
                        + " | sort -r)",
                "[ \"$(printf '%s\\n' \"$all\" | grep -c .)\" -gt " + LIST_LIMIT + " ] && echo TRUNCATED",
                "for ts in $(printf '%s\\n' \"$all\" | head -n " + LIST_LIMIT + "); do",
                "  m=$(storage_download_stream \"$h/$ts/backup_manifest.json\" 2>/dev/null | jq -c . 2>/dev/null || true)",
                "  n=''; b=''",
                "  case \"$STORAGE_BACKEND\" in",
                "    s3) out=$(_storage_aws s3 ls --recursive --summarize \"s3://$STORAGE_CONTAINER/$h/$ts/\" 2>/dev/null || true)",
                "        n=$(printf '%s\\n' \"$out\" | awk '/Total Objects:/ {print $3}'); b=$(printf '%s\\n' \"$out\" | awk '/Total Size:/ {print $3}') ;;",
                "    local) p=$(_storage_local_path_for \"$h/$ts\"); n=$(find \"$p\" -type f 2>/dev/null | wc -l)",
                "        b=$(find \"$p\" -type f -printf '%s\\n' 2>/dev/null | awk '{s+=$1} END {print s+0}') ;;",
                "    *) n=$(storage_list_keys \"$h/$ts/\" 2>/dev/null | wc -l) ;;",
                "  esac",
                "  echo \"SET $ts ${n:-0} ${b:--}\"",
                "  echo \"MANIFEST ${m:-null}\"",
                "done",
                "echo END");
        return s.prefix() + "bash -c " + NodeShell.quote(script);
    }

    static Probe parseProbe(String out) {
        Config cfg = null;
        String host = null;
        boolean truncated = false;
        List<BackupSet> sets = new ArrayList<>();
        String pendingId = null;
        int pendingObjects = 0;
        Long pendingBytes = null;
        boolean ended = false;
        for (String line : out.split("\\R")) {
            if (line.startsWith("ERROR ")) return new Probe(line.substring(6).strip(), cfg, host, sets, false);
            if (line.startsWith("CONFIG ")) cfg = config(line.substring(7));
            else if (line.startsWith("HOST ")) host = line.substring(5).strip();
            else if (line.equals("TRUNCATED")) truncated = true;
            else if (line.startsWith("SET ")) {
                String[] p = line.substring(4).strip().split("\\s+");
                pendingId = p[0];
                pendingObjects = p.length > 1 ? toInt(p[1]) : 0;
                pendingBytes = p.length > 2 && !"-".equals(p[2]) ? toLong(p[2]) : null;
            } else if (line.startsWith("MANIFEST ") && pendingId != null) {
                String m = line.substring(9).strip();
                JsonNode node = null;
                if (!m.isEmpty() && !"null".equals(m)) {
                    try {
                        node = Json.MAPPER.readTree(m);
                    } catch (Exception e) {
                        node = null;
                    }
                }
                sets.add(new BackupSet(pendingId, pendingObjects, pendingBytes, node));
                pendingId = null;
            } else if (line.equals("END")) ended = true;
        }
        if (!ended) return new Probe("the listing did not complete", cfg, host, sets, truncated);
        return new Probe(null, cfg, host, sets, truncated);
    }

    static Config config(String json) {
        try {
            JsonNode n = Json.MAPPER.readTree(json);
            return new Config(text(n, "backup_backend", "s3"), text(n, "s3_bucket_name", null),
                    text(n, "storage_local_path", null), text(n, "storage_azure_account", null),
                    intOrNull(n.get("s3_retention_period")), n.path("s3_object_lock_enabled").asBoolean(false),
                    text(n, "s3_object_lock_mode", "GOVERNANCE"), intOrNull(n.get("s3_object_lock_retention")));
        } catch (Exception e) {
            return null;
        }
    }

    /** Where a set lives, as backup-storage-lib.sh's storage_uri renders it. */
    static String location(Config c, String host, String id) {
        String key = host + "/" + id + "/";
        if (c == null) return null;
        return switch (c.backend()) {
            case "s3" -> "s3://" + c.bucket() + "/" + key;
            case "gcs" -> "gs://" + c.bucket() + "/" + key;
            case "azure" -> "azure://" + c.azureAccount() + "/" + c.bucket() + "/" + key;
            case "local" -> (c.localPath() == null ? "" : c.localPath().replaceAll("/+$", "")) + "/" + key;
            default -> key;
        };
    }

    static List<BackupEntry> entries(Probe p, String node, String datacenter) {
        List<BackupEntry> out = new ArrayList<>();
        Config c = p.config();
        for (BackupSet s : p.sets()) {
            JsonNode m = s.manifest();
            String type = m == null ? "unknown" : m.path("backup_type").asText("unknown");
            Long time = m == null ? null : isoTime(m.path("timestamp_utc").asText(null));
            if (time == null) time = tagTime(s.id());
            String dc = m == null ? null : m.path("source_node").path("datacenter").asText(null);
            if (dc == null || "Unknown".equals(dc)) dc = datacenter;
            Integer tables = null;
            if (m != null && m.has("tables_backed_up_count")) tables = m.get("tables_backed_up_count").asInt();
            else if (m != null && m.path("tables_backed_up").isArray()) tables = m.get("tables_backed_up").size();
            String status = m == null ? BackupEntry.INCOMPLETE : BackupEntry.COMPLETE;
            String detail = m == null
                    ? "no backup_manifest.json: the run did not finish (or is still running)"
                    : "manifest present" + (tables == null ? "" : ", " + tables + " tables");
            List<String> notes = new ArrayList<>();
            notes.add("schema version: not recorded by the estate scripts");
            if (s.bytes() == null) notes.add("size: not listed for backend " + (c == null ? "?" : c.backend()));
            Retention r = retention(c, time);
            out.add(new BackupEntry(s.id(), "estate", node, p.host(), dc, type, time, s.bytes(), null, status, detail,
                    location(c, p.host(), s.id()), r.text, r.expiresAt, r.lock, r.lockedUntil, tables, s.objects(),
                    String.join("; ", notes)));
        }
        return out;
    }

    record Retention(String text, Long expiresAt, String lock, Long lockedUntil) {}

    /**
     * What the config says about retention and object lock. The scripts apply a lifecycle rule
     * and per-object retention only on backends that support them (s3; gcs for holds).
     */
    static Retention retention(Config c, Long time) {
        if (c == null) return new Retention(null, null, null, null);
        String text;
        Long expires = null;
        Integer days = c.retentionDays();
        if (days == null || days <= 0) {
            text = "none configured (kept until deleted)";
        } else if ("s3".equals(c.backend())) {
            text = "bucket lifecycle: expires after " + days + " days";
            if (time != null) expires = time + Duration.ofDays(days).toMillis();
        } else {
            text = days + " days configured, not enforced by the " + c.backend() + " backend";
        }
        String lock;
        Long until = null;
        if (!c.lockEnabled()) {
            lock = "off";
        } else if ("s3".equals(c.backend())) {
            int ld = c.lockDays() == null ? 0 : c.lockDays();
            lock = c.lockMode() + (ld > 0 ? ", " + ld + " days" : ", no retention days")
                    + " (applied only if the bucket has Object Lock enabled)";
            if (ld > 0 && time != null) until = time + Duration.ofDays(ld).toMillis();
        } else if ("gcs".equals(c.backend())) {
            lock = "event-based hold (duration from the bucket retention policy)";
        } else {
            lock = "enabled in config, not supported by the " + c.backend() + " backend";
        }
        return new Retention(text, expires, lock, until);
    }

    // ---- fallback catalogue: the scripts' own read-only commands ----------------------------

    private static final Pattern LISTED = Pattern.compile("^\\s*-\\s+(" + TAG.pattern() + ")\\s+\\(type:\\s*([A-Za-z_-]+)\\)");
    private static final Pattern LISTED_HOST = Pattern.compile("^Host:\\s*(\\S+)");

    /** {@code restore-from-s3.sh --list-backups}: tag → type, plus the host it listed. */
    record Listing(String host, Map<String, String> types) {}

    static Listing parseListBackups(String out) {
        Map<String, String> types = new LinkedHashMap<>();
        String host = null;
        for (String raw : out.split("\\R")) {
            String line = stripAnsi(raw);
            Matcher h = LISTED_HOST.matcher(line.strip());
            if (h.find()) host = h.group(1);
            Matcher m = LISTED.matcher(line);
            if (m.find()) types.put(m.group(1), m.group(3));
        }
        return new Listing(host, types);
    }

    /** {@code backup-status.sh --json}: the latest backup's manifest summary, or null. */
    static JsonNode parseStatusJson(String out) {
        int start = out.indexOf('{');
        if (start < 0) return null;
        try {
            return Json.MAPPER.readTree(out.substring(start));
        } catch (Exception e) {
            return null;
        }
    }

    static List<BackupEntry> entriesFromListing(Listing l, JsonNode status, String node, String datacenter) {
        List<BackupEntry> out = new ArrayList<>();
        String latest = status != null && "OK".equals(status.path("status").asText()) ? status.path("backup_id").asText(null) : null;
        String bucket = status == null ? null : status.path("bucket").asText(null);
        for (var e : l.types().entrySet()) {
            boolean isLatest = e.getKey().equals(latest);
            Long time = isLatest ? isoTime(status.path("completed_at_utc").asText(null)) : null;
            if (time == null) time = tagTime(e.getKey());
            Integer tables = isLatest && status.has("tables_count") ? status.get("tables_count").asInt() : null;
            boolean known = !"unknown".equals(e.getValue());
            String dc = isLatest ? status.path("source_dc").asText(datacenter) : datacenter;
            out.add(new BackupEntry(e.getKey(), "estate", node, l.host(), dc, e.getValue(), time, null, null,
                    known ? BackupEntry.COMPLETE : BackupEntry.INCOMPLETE,
                    known ? "manifest present" : "no readable manifest: the run did not finish",
                    bucket == null || l.host() == null ? null : "bucket " + bucket + ": " + l.host() + "/" + e.getKey() + "/",
                    null, null, null, null, tables, null,
                    "listed with restore-from-s3.sh --list-backups: size, retention and object lock not available"));
        }
        return out;
    }

    private static String text(JsonNode n, String f, String dflt) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() || v.asText().isBlank() ? dflt : v.asText();
    }

    private static Integer intOrNull(JsonNode v) {
        return v == null || v.isNull() || !v.canConvertToInt() && !v.isTextual() ? null : toIntOrNull(v.asText());
    }

    private static Integer toIntOrNull(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int toInt(String s) {
        Integer v = toIntOrNull(s);
        return v == null ? 0 : v;
    }

    private static Long toLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
