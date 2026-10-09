package com.cassandrastudio.engine.api;

import static com.cassandrastudio.engine.api.RouteSupport.body;
import static com.cassandrastudio.engine.api.RouteSupport.confirmation;
import static com.cassandrastudio.engine.api.RouteSupport.id;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.bulk.BulkService;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.config.RoutesConfig;

/**
 * Phase 3 Track 6, bulk unload/load (BLK-1/2), docs/api/bulk.md. Unload and load run as jobs
 * (poll /api/jobs/{jobId}); load is guarded (category "bulk") unless it is a dry run.
 */
public final class BulkRoutes {
    private BulkRoutes() {}

    private static final String BASE = "/api/clusters/{id}/bulk";

    public static void register(RoutesConfig app, Engine engine) {
        BulkService svc = new BulkService(engine);
        engine.onDisconnect(svc::disconnect);

        app.get(BASE + "/defaults", ctx -> ctx.json(svc.defaults()));
        app.post(BASE + "/unload", ctx -> ctx.status(202).json(svc.unload(id(ctx), body(ctx))));
        app.post(BASE + "/load/preview", ctx -> ctx.json(svc.preview(id(ctx), body(ctx))));
        app.post(BASE + "/load", ctx -> {
            JsonNode b = body(ctx);
            ctx.status(202).json(svc.load(id(ctx), b, confirmation(b)));
        });
        app.get(BASE + "/jobs", ctx -> ctx.json(svc.jobs(id(ctx))));
        app.get(BASE + "/jobs/{jobId}/stats", ctx -> ctx.json(svc.stats(ctx.pathParam("jobId"))));
    }
}
