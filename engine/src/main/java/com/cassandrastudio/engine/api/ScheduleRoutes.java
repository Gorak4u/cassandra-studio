package com.cassandrastudio.engine.api;

import static com.cassandrastudio.engine.api.RouteSupport.body;
import static com.cassandrastudio.engine.api.RouteSupport.confirmation;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.audit.AuditLog;
import com.cassandrastudio.engine.guard.ActionGuard;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.sched.Schedule;
import com.cassandrastudio.engine.util.ApiException;
import com.cassandrastudio.engine.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.config.RoutesConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * Schedules API (docs/api/schedules.md, SRV-5). Saving or running a schedule is confirmed once, like
 * starting the job by hand (typed name on PROD); its unattended runs then need no confirmation.
 */
public final class ScheduleRoutes {
    private ScheduleRoutes() {}

    public static void register(RoutesConfig app, Engine engine) {
        app.get("/api/schedules", ctx -> ctx.json(engine.schedules.list(RouteSupport.blankToNull(ctx.queryParam("connectionId")))));
        app.get("/api/schedules/types", ctx -> ctx.json(engine.schedules.types()));
        app.get("/api/schedules/{scheduleId}", ctx -> ctx.json(find(engine, ctx.pathParam("scheduleId"))));
        app.get("/api/schedules/{scheduleId}/runs", ctx -> {
            Long limit = RouteSupport.longParam(ctx, "limit");
            ctx.json(engine.schedules.runs(find(engine, ctx.pathParam("scheduleId")).id(), limit == null ? 50 : limit.intValue()));
        });
        app.put("/api/schedules", ctx -> {
            JsonNode b = body(ctx);
            Schedule s = Json.MAPPER.treeToValue(b.has("schedule") ? b.get("schedule") : b, Schedule.class);
            if (s.type() == null || s.name() == null || s.name().isBlank()) {
                throw ApiException.badRequest("type and name are required");
            }
            ConnectionConfig conn = s.connectionId() == null ? null : engine.connections.get(s.connectionId());
            String summary = (s.enabled() ? "Schedule " : "Save disabled schedule ") + s.type() + " '" + s.name() + "' " + when(s);
            if (conn != null) {
                engine.guard.check(conn, new ActionGuard.Action("schedule", summary, List.of(summary + "; params " + Json.write(s.params())),
                        List.of("Runs unattended while Studio (or Studio Server) is running."), false, null), confirmation(b));
            }
            Schedule saved;
            try {
                saved = engine.schedules.save(s);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest(e.getMessage());
            }
            if (conn != null) engine.audit.record(conn, null, "schedule", summary, Json.write(saved.params()), AuditLog.Outcome.SUCCESS, null);
            ctx.json(saved);
        });
        app.delete("/api/schedules/{scheduleId}", ctx -> {
            Schedule s = find(engine, ctx.pathParam("scheduleId"));
            engine.schedules.delete(s.id());
            if (s.connectionId() != null) {
                engine.audit.record(engine.connections.get(s.connectionId()), null, "schedule", "Delete schedule '" + s.name() + "'",
                        null, AuditLog.Outcome.SUCCESS, null);
            }
            ctx.status(204);
        });
        app.post("/api/schedules/{scheduleId}/run", ctx -> {
            Schedule s = find(engine, ctx.pathParam("scheduleId"));
            if (s.connectionId() != null) {
                String summary = "Run schedule '" + s.name() + "' now";
                engine.guard.check(engine.connections.get(s.connectionId()),
                        new ActionGuard.Action("schedule", summary, List.of(summary), new ArrayList<>(), false, null), confirmation(body(ctx)));
            }
            ctx.status(202).json(engine.schedules.runNow(s.id()));
        });
    }

    private static Schedule find(Engine engine, String id) {
        return engine.schedules.get(id).orElseThrow(() -> ApiException.notFound("schedule " + id));
    }

    static String when(Schedule s) {
        int m = s.everyMinutes();
        String every = m % 1440 == 0 ? (m / 1440) + " day(s)" : m % 60 == 0 ? (m / 60) + " hour(s)" : m + " minute(s)";
        StringBuilder sb = new StringBuilder("every ").append(every);
        if (s.atTime() != null) sb.append(" at ").append(s.atTime());
        if (s.windowStart() != null) sb.append(", only between ").append(s.windowStart()).append(" and ").append(s.windowEnd());
        return sb.toString();
    }
}
