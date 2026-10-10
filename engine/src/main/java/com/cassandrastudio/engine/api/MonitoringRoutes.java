package com.cassandrastudio.engine.api;

import com.cassandrastudio.engine.metrics.MonitoringService;
import com.cassandrastudio.engine.metrics.Thresholds;
import com.cassandrastudio.engine.util.ApiException;
import com.cassandrastudio.engine.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;

/**
 * Monitoring API (docs/api/monitoring.md). Read-only towards the cluster, so no ActionGuard.
 * Errors go through the server's ApiException handler like every other route.
 */
public final class MonitoringRoutes {
    private MonitoringRoutes() {}

    private static final String BASE = "/api/clusters/{id}/monitoring";

    public static void register(RoutesConfig app, MonitoringService svc) {
        app.post(BASE + "/start", ctx -> {
            JsonNode b = body(ctx);
            Integer interval = null;
            if (b.hasNonNull("intervalSec")) {
                if (!b.get("intervalSec").canConvertToInt()) throw ApiException.badRequest("intervalSec must be a number");
                interval = b.get("intervalSec").asInt();
            }
            ctx.json(svc.start(id(ctx), interval));
        });
        app.post(BASE + "/stop", ctx -> {
            svc.stop(id(ctx));
            ctx.status(204);
        });
        app.get(BASE + "/status", ctx -> ctx.json(svc.status(id(ctx))));
        app.get(BASE + "/snapshot", ctx -> ctx.json(svc.snapshot(id(ctx))));
        app.get(BASE + "/series", ctx -> {
            Long maxPoints = longParam(ctx, "maxPoints");
            ctx.json(svc.series(id(ctx), ctx.queryParam("metric"), blankToNull(ctx.queryParam("node")),
                    longParam(ctx, "fromMs"), longParam(ctx, "toMs"), maxPoints == null ? 0 : (int) Math.min(maxPoints, 100_000)));
        });
        app.get(BASE + "/ring", ctx -> ctx.json(svc.ring(id(ctx), blankToNull(ctx.queryParam("keyspace")))));
        app.get(BASE + "/tables", ctx -> ctx.json(svc.tables(id(ctx), blankToNull(ctx.queryParam("keyspace")))));
        app.get(BASE + "/alerts", ctx -> ctx.json(svc.alerts(id(ctx))));
        app.get(BASE + "/thresholds", ctx -> ctx.json(svc.thresholds(id(ctx))));
        app.put(BASE + "/thresholds", ctx -> {
            Thresholds t;
            try {
                t = Json.MAPPER.treeToValue(body(ctx), Thresholds.class);
            } catch (Exception e) {
                throw ApiException.badRequest("Thresholds body is not valid: " + e.getMessage());
            }
            ctx.json(svc.setThresholds(id(ctx), t));
        });
    }

    private static String id(Context ctx) {
        return ctx.pathParam("id");
    }

    private static JsonNode body(Context ctx) {
        try {
            JsonNode n = Json.MAPPER.readTree(ctx.body().isBlank() ? "{}" : ctx.body());
            if (n == null || !n.isObject()) throw ApiException.badRequest("Body must be a JSON object");
            return n;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("Body is not valid JSON");
        }
    }

    private static Long longParam(Context ctx, String name) {
        String v = ctx.queryParam(name);
        if (v == null || v.isBlank()) return null;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(name + " must be a number");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
