package com.cassandrastudio.engine.api;

import static com.cassandrastudio.engine.api.RouteSupport.blankToNull;
import static com.cassandrastudio.engine.api.RouteSupport.body;
import static com.cassandrastudio.engine.api.RouteSupport.id;
import static com.cassandrastudio.engine.api.RouteSupport.text;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.diag.DiagService;
import com.cassandrastudio.engine.diag.DiagSettings;
import com.cassandrastudio.engine.diag.HotPartitions;
import com.cassandrastudio.engine.diag.ThreadDumps;
import com.cassandrastudio.engine.util.ApiException;
import com.cassandrastudio.engine.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 3 Track 2, JVM and partition diagnostics (JVM-1/2, PRF-1/2), docs/api/diag.md. Routes
 * live under /api/clusters/{id}/diag/...; all of them only read from the cluster, so none needs
 * the ActionGuard. Long tasks (dump series, hot partitions, tombstone scan) return a Job (202).
 */
public final class DiagRoutes {
    private DiagRoutes() {}

    private static final String BASE = "/api/clusters/{id}/diag";

    public static void register(RoutesConfig app, Engine engine) {
        DiagService svc = new DiagService(engine.db, engine.connections, engine.jmx, engine.topology, engine.shell,
                engine.jobs, engine::secretsFor);
        engine.onClose(svc);
        engine.onDisconnect(svc::forget);

        // thread dumps (JVM-1)
        app.post(BASE + "/threads/dumps", ctx -> ctx.json(svc.takeDump(id(ctx), text(body(ctx), "node"))));
        app.get(BASE + "/threads/dumps", ctx -> ctx.json(svc.dumps(id(ctx))));
        app.get(BASE + "/threads/dumps/{dumpId}", ctx -> ctx.json(svc.dump(id(ctx), ctx.pathParam("dumpId"))));
        app.get(BASE + "/threads/dumps/{dumpId}/text", ctx -> {
            ThreadDumps.ThreadDump d = svc.dump(id(ctx), ctx.pathParam("dumpId"));
            String file = "threads-" + d.node().replaceAll("[^A-Za-z0-9.]", "_") + "-"
                    + Instant.ofEpochMilli(d.takenAtMs()).toString().replace(":", "") + ".txt";
            ctx.header("Content-Disposition", "attachment; filename=\"" + file + "\"");
            ctx.contentType("text/plain; charset=utf-8").result(ThreadDumps.jstack(d));
        });
        app.delete(BASE + "/threads/dumps/{dumpId}", ctx -> {
            svc.deleteDump(id(ctx), ctx.pathParam("dumpId"));
            ctx.status(204);
        });
        app.post(BASE + "/threads/series", ctx -> {
            JsonNode b = body(ctx);
            ctx.status(202).json(svc.series(id(ctx), text(b, "node"), intField(b, "count", 3), intField(b, "intervalSec", 5)));
        });
        app.get(BASE + "/threads/compare", ctx -> ctx.json(svc.compare(id(ctx), blankToNull(ctx.queryParam("a")),
                blankToNull(ctx.queryParam("b")))));

        // top threads (JVM-2)
        app.get(BASE + "/threads/top", ctx -> ctx.json(svc.top(id(ctx), ctx.queryParam("node"),
                intParam(ctx, "limit", 30), "true".equalsIgnoreCase(ctx.queryParam("group")))));

        // partitions (PRF-1, PRF-2)
        app.get(BASE + "/partitions/histograms", ctx -> ctx.json(svc.histograms(id(ctx), blankToNull(ctx.queryParam("node")),
                blankToNull(ctx.queryParam("keyspace")), "true".equalsIgnoreCase(ctx.queryParam("includeSystem")))));
        app.post(BASE + "/partitions/hot", ctx -> {
            JsonNode b = body(ctx);
            int durationSec = intField(b, "durationSec", 10);
            HotPartitions.Request req = new HotPartitions.Request(strings(b, "tables"),
                    durationSec > 600 ? 600_001 : durationSec * 1000, intField(b, "capacity", 256), intField(b, "top", 10),
                    strings(b, "nodes"));
            ctx.status(202).json(svc.hotPartitions(id(ctx), req));
        });
        app.get(BASE + "/partitions/warnings", ctx -> ctx.json(svc.warnings(id(ctx), blankToNull(ctx.queryParam("node")),
                intParam(ctx, "limit", 500))));
        app.post(BASE + "/partitions/tombstone-scan", ctx -> {
            JsonNode b = body(ctx);
            ctx.status(202).json(svc.tombstoneScan(id(ctx), text(b, "node"), text(b, "keyspace"), text(b, "table")));
        });

        app.get(BASE + "/settings", ctx -> ctx.json(svc.settings(id(ctx))));
        app.put(BASE + "/settings", ctx -> {
            DiagSettings s;
            try {
                s = Json.MAPPER.treeToValue(body(ctx), DiagSettings.class);
            } catch (Exception e) {
                throw ApiException.badRequest("Settings body is not valid: " + e.getMessage());
            }
            ctx.json(svc.saveSettings(id(ctx), s));
        });
    }

    private static int intField(JsonNode b, String field, int dflt) {
        JsonNode v = b.get(field);
        if (v == null || v.isNull()) return dflt;
        if (!v.canConvertToInt() || !v.isNumber()) throw ApiException.badRequest(field + " must be a whole number");
        return v.asInt();
    }

    private static int intParam(Context ctx, String name, int dflt) {
        Long v = RouteSupport.longParam(ctx, name);
        if (v == null) return dflt;
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) throw ApiException.badRequest(name + " is out of range");
        return v.intValue();
    }

    private static List<String> strings(JsonNode b, String field) {
        JsonNode v = b.get(field);
        if (v == null || v.isNull()) return List.of();
        if (!v.isArray()) throw ApiException.badRequest(field + " must be a list of strings");
        List<String> out = new ArrayList<>();
        for (JsonNode e : v) {
            if (!e.isTextual() || e.asText().isBlank()) throw ApiException.badRequest(field + " must be a list of strings");
            out.add(e.asText().trim());
        }
        return out;
    }
}
