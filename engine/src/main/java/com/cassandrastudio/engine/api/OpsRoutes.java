package com.cassandrastudio.engine.api;

import static com.cassandrastudio.engine.api.RouteSupport.blankToNull;
import static com.cassandrastudio.engine.api.RouteSupport.body;
import static com.cassandrastudio.engine.api.RouteSupport.confirmation;
import static com.cassandrastudio.engine.api.RouteSupport.id;
import static com.cassandrastudio.engine.api.RouteSupport.text;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.ops.Maintenance;
import com.cassandrastudio.engine.ops.OpsService;
import com.cassandrastudio.engine.ops.Repair;
import com.cassandrastudio.engine.ops.Snapshots;
import com.cassandrastudio.engine.util.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.config.RoutesConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 3 Track 1, operations (OPS-1..4), docs/api/ops.md. Views are read-only; every action is
 * ActionGuard-checked (428 until confirmed) and then runs as a job (202 + Job).
 */
public final class OpsRoutes {
    private OpsRoutes() {}

    private static final String BASE = "/api/clusters/{id}/ops";

    public static void register(RoutesConfig app, Engine engine) {
        OpsService svc = new OpsService(engine.connections, engine::secretsFor, engine.jmx, engine.opsJmx, engine.topology,
                engine.guard, engine.jobs);
        engine.onClose(svc);
        register(app, svc);
    }

    /** Package-visible for tests with a fake service graph. */
    public static void register(RoutesConfig app, OpsService svc) {
        app.get(BASE + "/views", ctx -> ctx.json(svc.views()));
        app.get(BASE + "/views/{view}", ctx -> ctx.json(svc.view(id(ctx), ctx.pathParam("view"),
                blankToNull(ctx.queryParam("node")), blankToNull(ctx.queryParam("keyspace")),
                blankToNull(ctx.queryParam("table")), ctx.queryParam("key"))));

        app.post(BASE + "/actions/{action}", ctx -> {
            JsonNode b = body(ctx);
            Maintenance.Request r = new Maintenance.Request(Maintenance.Kind.of(ctx.pathParam("action")), strings(b, "nodes"),
                    text(b, "keyspace"), strings(b, "tables"), bool(b, "splitOutput"), integer(b, "jobs", 0),
                    bool(b, "disableSnapshot"), bool(b, "skipCorrupted"), bool(b, "noValidate"), bool(b, "reinsertOverflowedTtl"),
                    bool(b, "includeAll"), text(b, "granularity"), strings(b, "files"), bool(b, "continueOnError"));
            ctx.status(202).json(svc.maintenance(id(ctx), r, confirmation(b)));
        });

        app.post(BASE + "/repair", ctx -> {
            JsonNode b = body(ctx);
            List<Repair.Range> ranges = new ArrayList<>();
            JsonNode rs = b.get("ranges");
            if (rs != null && !rs.isNull()) {
                if (!rs.isArray()) throw ApiException.badRequest("ranges must be a list of {start, end}");
                for (JsonNode x : rs) ranges.add(new Repair.Range(text(x, "start"), text(x, "end")));
            }
            String mode = text(b, "mode");
            if (mode != null && !mode.equals("full") && !mode.equals("incremental")) {
                throw ApiException.badRequest("mode must be full or incremental");
            }
            Repair.Request r = new Repair.Request(strings(b, "nodes"), text(b, "keyspace"), strings(b, "tables"),
                    !"incremental".equals(mode), bool(b, "primaryRange"), strings(b, "dataCenters"),
                    Repair.Parallelism.of(text(b, "parallelism")), ranges, integer(b, "jobThreads", 1), bool(b, "continueOnError"));
            ctx.status(202).json(svc.repair(id(ctx), r, confirmation(b)));
        });

        app.get(BASE + "/snapshots", ctx -> ctx.json(svc.snapshots(id(ctx), blankToNull(ctx.queryParam("node")))));
        app.post(BASE + "/snapshots", ctx -> {
            JsonNode b = body(ctx);
            Snapshots.Create c = new Snapshots.Create(strings(b, "nodes"), text(b, "tag"), strings(b, "keyspaces"),
                    strings(b, "tables"), bool(b, "skipFlush"));
            ctx.status(202).json(svc.takeSnapshot(id(ctx), c, confirmation(b)));
        });
        app.post(BASE + "/snapshots/clear", ctx -> {
            JsonNode b = body(ctx);
            Snapshots.Clear c = new Snapshots.Clear(strings(b, "nodes"), text(b, "tag"), strings(b, "keyspaces"), bool(b, "all"));
            ctx.status(202).json(svc.clearSnapshot(id(ctx), c, confirmation(b)));
        });
    }

    private static List<String> strings(JsonNode b, String field) {
        JsonNode v = b.get(field);
        if (v == null || v.isNull()) return List.of();
        if (!v.isArray()) throw ApiException.badRequest(field + " must be a list of strings");
        List<String> out = new ArrayList<>();
        for (JsonNode x : v) {
            if (!x.isTextual()) throw ApiException.badRequest(field + " must be a list of strings");
            if (!x.asText().isBlank()) out.add(x.asText().trim());
        }
        return out;
    }

    private static boolean bool(JsonNode b, String field) {
        JsonNode v = b.get(field);
        if (v == null || v.isNull()) return false;
        if (!v.isBoolean()) throw ApiException.badRequest(field + " must be true or false");
        return v.asBoolean();
    }

    private static int integer(JsonNode b, String field, int dflt) {
        JsonNode v = b.get(field);
        if (v == null || v.isNull()) return dflt;
        if (!v.canConvertToInt() || !v.isIntegralNumber()) throw ApiException.badRequest(field + " must be a whole number");
        return v.asInt();
    }
}
