package com.cassandrastudio.engine.api;

import static com.cassandrastudio.engine.api.RouteSupport.body;
import static com.cassandrastudio.engine.api.RouteSupport.id;
import static com.cassandrastudio.engine.api.RouteSupport.requiredText;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.gclog.GcLogService;
import com.cassandrastudio.engine.util.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import java.util.ArrayList;
import java.util.List;

/**
 * GC log analysis API (docs/api/gclog.md, GCL-1..4). Routes live under /api/clusters/{id}/gclog/...
 * Reading logs is read-only towards the cluster, so no ActionGuard; fetching runs as a job.
 */
public final class GcLogRoutes {
    private GcLogRoutes() {}

    private static final String BASE = "/api/clusters/{id}/gclog";

    public static void register(RoutesConfig app, Engine engine) {
        GcLogService svc = new GcLogService(engine);
        engine.onClose(svc);
        engine.onDisconnect(svc::drop);

        app.get(BASE + "/files", ctx -> ctx.json(svc.discover(id(ctx), ctx.queryParam("node"))));
        app.post(BASE + "/fetch", ctx -> {
            JsonNode b = body(ctx);
            List<String> paths = new ArrayList<>();
            JsonNode p = b.get("paths");
            if (p == null || !p.isArray()) throw ApiException.badRequest("paths must be a list of file paths");
            p.forEach(n -> paths.add(n.asText()));
            Long maxBytes = null;
            if (b.hasNonNull("maxMB")) {
                if (!b.get("maxMB").canConvertToLong()) throw ApiException.badRequest("maxMB must be a number");
                maxBytes = b.get("maxMB").asLong() * 1024 * 1024;
            }
            ctx.status(202).json(svc.fetch(id(ctx), requiredText(b, "node"), paths, maxBytes,
                    RouteSupport.text(b, "javaVersion")));
        });
        app.post(BASE + "/upload", ctx -> {
            String len = ctx.header("Content-Length");
            if (len != null && len.matches("\\d+") && Long.parseLong(len) > GcLogService.MAX_UPLOAD_BYTES) {
                throw new ApiException(413, "too_large", "Uploads are limited to 1 GB");
            }
            ctx.status(201).json(svc.upload(id(ctx), ctx.queryParam("name"), ctx.bodyInputStream()));
        });
        app.get(BASE + "/analyses", ctx -> ctx.json(svc.list(id(ctx))));
        app.get(BASE + "/analyses/{aid}", ctx -> ctx.json(svc.report(id(ctx), ctx.pathParam("aid"),
                doubleParam(ctx, "fromX"), doubleParam(ctx, "toX"))));
        app.delete(BASE + "/analyses/{aid}", ctx -> {
            svc.delete(id(ctx), ctx.pathParam("aid"));
            ctx.status(204);
        });
    }

    private static Double doubleParam(Context ctx, String name) {
        String v = ctx.queryParam(name);
        if (v == null || v.isBlank()) return null;
        try {
            double d = Double.parseDouble(v.trim());
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new NumberFormatException();
            return d;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(name + " must be a number");
        }
    }
}
