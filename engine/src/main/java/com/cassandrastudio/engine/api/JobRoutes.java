package com.cassandrastudio.engine.api;

import com.cassandrastudio.engine.jobs.JobService;
import io.javalin.config.RoutesConfig;

/** Jobs API (docs/api/jobs.md): list, poll and cancel long-running tasks. Features start jobs. */
public final class JobRoutes {
    private JobRoutes() {}

    public static void register(RoutesConfig app, JobService jobs) {
        app.get("/api/jobs", ctx -> ctx.json(jobs.list(RouteSupport.blankToNull(ctx.queryParam("connectionId")))));
        app.get("/api/jobs/{jobId}", ctx -> ctx.json(jobs.get(ctx.pathParam("jobId"))));
        app.post("/api/jobs/{jobId}/cancel", ctx -> ctx.json(jobs.cancel(ctx.pathParam("jobId"))));
    }
}
