package com.cassandrastudio.engine.backup;

import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.ApiException;
import com.cassandrastudio.engine.util.Json;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * BAK-1: the backup provider chosen for one cluster and how to reach it on the nodes, stored in
 * the settings table under {@code backup.provider/<connectionId>}. Null fields mean "default".
 *
 * @param provider      ESTATE (the estate's backup scripts), MEDUSA (Cassandra Medusa CLI) or
 *                      SNAPSHOT (nodetool-style snapshots over JMX); null = not chosen yet
 * @param scriptDir     where the estate scripts are installed ($manage_bin_dir, /usr/local/bin)
 * @param configFile    the estate scripts' config (/etc/backup/config.json, root-only)
 * @param privilege     SUDO: run scripts with {@code sudo -n} (the estate scripts need root);
 *                      NONE: run them as the SSH user
 * @param medusaCommand the medusa executable
 * @param medusaConfig  medusa.ini path, or null for medusa's default
 * @param nodeTimeoutMinutes how long one node's backup may run before Studio gives up on it
 */
public record BackupSettings(Provider provider, String scriptDir, String configFile, Privilege privilege,
                             String medusaCommand, String medusaConfig, Integer nodeTimeoutMinutes) {

    public enum Provider { ESTATE, MEDUSA, SNAPSHOT }

    public enum Privilege { SUDO, NONE }

    static final String KEY = "backup.provider/";
    public static final BackupSettings DEFAULTS = new BackupSettings(null, "/usr/local/bin", "/etc/backup/config.json",
            Privilege.SUDO, "medusa", null, 360);

    /** Absolute path made of safe characters only: it ends up inside shell commands. */
    private static final Pattern PATH = Pattern.compile("/[A-Za-z0-9._/@+-]*");
    private static final Pattern COMMAND = Pattern.compile("[A-Za-z0-9._/@+-]+");

    public BackupSettings effective() {
        BackupSettings d = DEFAULTS;
        return new BackupSettings(provider, or(scriptDir, d.scriptDir), or(configFile, d.configFile),
                or(privilege, d.privilege), or(medusaCommand, d.medusaCommand), blank(medusaConfig),
                or(nodeTimeoutMinutes, d.nodeTimeoutMinutes));
    }

    /** Validates the effective values; throws 400 with the first problem. */
    public BackupSettings validated() {
        BackupSettings e = effective();
        path("scriptDir", e.scriptDir);
        path("configFile", e.configFile);
        if (e.medusaConfig != null) path("medusaConfig", e.medusaConfig);
        if (!COMMAND.matcher(e.medusaCommand).matches()) {
            throw ApiException.badRequest("medusaCommand must be a command name or path without spaces or quotes");
        }
        if (e.nodeTimeoutMinutes < 1 || e.nodeTimeoutMinutes > 24 * 60) {
            throw ApiException.badRequest("nodeTimeoutMinutes must be 1-1440");
        }
        return e;
    }

    /** "sudo -n " or "" in front of a node command. */
    String prefix() {
        return effective().privilege == Privilege.SUDO ? "sudo -n " : "";
    }

    String script(String name) {
        String dir = effective().scriptDir;
        return (dir.endsWith("/") ? dir : dir + "/") + name;
    }

    private static void path(String field, String v) {
        if (!PATH.matcher(v).matches() || v.contains("..")) {
            throw ApiException.badRequest(field + " must be an absolute path (letters, digits, . _ - / only)");
        }
    }

    private static <T> T or(T v, T dflt) {
        return v == null || v instanceof String s && s.isBlank() ? dflt : v;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static BackupSettings load(Database db, String connectionId) {
        if (db == null) return DEFAULTS;
        List<Map<String, Object>> rows = db.query("SELECT value FROM settings WHERE key=?", KEY + connectionId);
        if (rows.isEmpty()) return DEFAULTS;
        try {
            return Json.read(String.valueOf(rows.get(0).get("value")), BackupSettings.class).effective();
        } catch (IllegalArgumentException e) {
            return DEFAULTS;
        }
    }

    static void save(Database db, String connectionId, BackupSettings s) {
        db.update("INSERT OR REPLACE INTO settings(key, value) VALUES (?, ?)", KEY + connectionId, Json.write(s));
    }
}
