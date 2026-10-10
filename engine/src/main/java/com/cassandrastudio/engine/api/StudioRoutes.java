package com.cassandrastudio.engine.api;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.Version;
import com.cassandrastudio.engine.audit.AuditLog;
import com.cassandrastudio.engine.store.CrashLog;
import com.cassandrastudio.engine.store.SettingsBackup;
import com.cassandrastudio.engine.util.ApiException;
import com.cassandrastudio.engine.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.config.RoutesConfig;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Studio's own state (docs/api/studio.md): remembered UI layout (NFR-UX), settings backup and
 * restore (NFR-DATA), audit export (NFR-AUD) and local diagnostics (NFR-OBS).
 */
public final class StudioRoutes {
    static final String UI_STATE_KEY = "ui.state";
    static final int MAX_UI_STATE = 256 * 1024;
    /** UI errors recorded per minute at most, so a render loop cannot fill the disk. */
    static final int UI_ERRORS_PER_MINUTE = 30;

    private StudioRoutes() {}

    public static void register(RoutesConfig app, Engine engine) {
        SettingsBackup backup = new SettingsBackup(engine.db, engine.connections);

        // ---- UI state: open tabs, active views, tree, pane sizes (NFR-UX) ----
        app.get("/api/ui-state", ctx -> {
            List<Map<String, Object>> rows = engine.db.query("SELECT value FROM settings WHERE key=?", UI_STATE_KEY);
            ctx.contentType("application/json").result(rows.isEmpty() ? "{}" : String.valueOf(rows.get(0).get("value")));
        });
        app.put("/api/ui-state", ctx -> {
            String body = ctx.body();
            if (body.length() > MAX_UI_STATE) throw ApiException.badRequest("UI state is larger than 256 KB");
            JsonNode n = RouteSupport.body(ctx);
            engine.db.update("INSERT OR REPLACE INTO settings(key, value) VALUES (?, ?)", UI_STATE_KEY, Json.write(n));
            ctx.status(204);
        });

        // ---- settings backup / restore (NFR-DATA) ----
        app.post("/api/studio/backup", ctx -> {
            JsonNode b = RouteSupport.body(ctx);
            boolean withSecrets = b.path("includeSecrets").asBoolean(false);
            String passphrase = withSecrets ? RouteSupport.requiredText(b, "passphrase") : null;
            ctx.header("Content-Disposition", "attachment; filename=\"cassandra-studio-settings-" + LocalDate.now() + ".json\"");
            ctx.json(backup.export(passphrase));
        });
        app.post("/api/studio/restore", ctx -> {
            JsonNode b = RouteSupport.body(ctx);
            SettingsBackup.File file = file(b.get("file"));
            if (b.path("dryRun").asBoolean(false)) {
                ctx.json(backup.preview(file));
                return;
            }
            SettingsBackup.Conflict conflict = conflict(RouteSupport.text(b, "conflict"));
            SettingsBackup.Result r = backup.restore(file, conflict, RouteSupport.text(b, "passphrase"));
            engine.audit.record(null, null, "studio", "restore settings",
                    "folders " + r.folders() + ", connections " + r.connections() + ", scripts " + r.scripts()
                            + ", settings " + r.settings() + ", skipped " + r.skipped() + " (conflicts: " + conflict + ")",
                    AuditLog.Outcome.SUCCESS, null);
            ctx.json(r);
        });

        // ---- audit export (NFR-AUD) ----
        app.get("/api/audit/export", ctx -> {
            String format = ctx.queryParam("format") == null ? "csv" : ctx.queryParam("format").toLowerCase();
            if (!format.equals("csv") && !format.equals("json")) throw ApiException.badRequest("format must be csv or json");
            Long limit = RouteSupport.longParam(ctx, "limit");
            List<AuditLog.Entry> rows = engine.audit.export(ctx.queryParam("connectionId"), ctx.queryParam("q"),
                    ctx.queryParam("since"), limit == null ? AuditLog.MAX_EXPORT : (int) Math.min(limit, AuditLog.MAX_EXPORT));
            String name = "studio-audit-" + LocalDate.now() + "." + format;
            ctx.header("Content-Disposition", "attachment; filename=\"" + name + "\"");
            ctx.header("X-Row-Count", String.valueOf(rows.size()));
            if (format.equals("json")) ctx.json(rows);
            else ctx.contentType("text/csv; charset=utf-8").result(AuditLog.toCsv(rows).getBytes(StandardCharsets.UTF_8));
        });

        // ---- local diagnostics (NFR-OBS): nothing here leaves the machine ----
        app.get("/api/diagnostics", ctx -> ctx.json(diagnostics(engine)));
        AtomicLong window = new AtomicLong();
        AtomicInteger inWindow = new AtomicInteger();
        app.post("/api/diagnostics/ui-error", ctx -> {
            JsonNode b = RouteSupport.body(ctx);
            long minute = System.currentTimeMillis() / 60_000;
            if (window.getAndSet(minute) != minute) inWindow.set(0);
            if (inWindow.incrementAndGet() <= UI_ERRORS_PER_MINUTE) {
                CrashLog log = CrashLog.get();
                String message = RouteSupport.text(b, "message");
                String detail = String.join("\n", nonNull(RouteSupport.text(b, "stack")),
                        nonNull(RouteSupport.text(b, "componentStack")));
                if (log != null) log.record("ui", message == null ? "UI error" : message, detail);
            }
            ctx.status(204);
        });
    }

    static Map<String, Object> diagnostics(Engine engine) {
        Map<String, Object> d = new LinkedHashMap<>();
        Runtime rt = Runtime.getRuntime();
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        d.put("studioVersion", Version.VERSION);
        d.put("dbVersion", engine.db.schemaVersion());
        d.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        d.put("java", System.getProperty("java.runtime.version", System.getProperty("java.version")) + " "
                + System.getProperty("java.vendor", ""));
        d.put("uptimeSec", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        d.put("heapUsedMb", mem.getHeapMemoryUsage().getUsed() / (1024 * 1024));
        d.put("heapMaxMb", rt.maxMemory() / (1024 * 1024));
        d.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        d.put("processors", rt.availableProcessors());
        d.put("secretStore", engine.connections.secretStoreDescription().replaceAll("\\(.*\\)", "").trim());
        d.put("savedConnections", engine.connections.list().size());
        CrashLog log = CrashLog.get();
        d.put("crashLog", log == null ? null : log.file().toString());
        d.put("recentErrors", log == null ? List.of() : log.recent());
        return d;
    }

    private static SettingsBackup.File file(JsonNode n) {
        if (n == null || !n.isObject()) throw ApiException.badRequest("file is required (the backup's JSON)");
        try {
            return Json.MAPPER.convertValue(n, SettingsBackup.File.class);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Not a valid settings backup: " + e.getMessage().lines().findFirst().orElse(""));
        }
    }

    private static SettingsBackup.Conflict conflict(String s) {
        if (s == null) return SettingsBackup.Conflict.SKIP;
        try {
            return SettingsBackup.Conflict.valueOf(s.toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("conflict must be skip, replace or keep_both");
        }
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }
}
