package com.cassandrastudio.engine.api;

import static com.cassandrastudio.engine.api.RouteSupport.body;
import static com.cassandrastudio.engine.api.RouteSupport.id;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.config.ConfigModel.HieraSettings;
import com.cassandrastudio.engine.config.ConfigService;
import com.cassandrastudio.engine.config.NodeCollector;
import com.cassandrastudio.engine.util.ApiException;
import com.cassandrastudio.engine.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.config.RoutesConfig;

/**
 * Phase 3 Track 4, effective config and drift (CFG-1/2), docs/api/config.md. Read-only towards
 * the cluster (no ActionGuard); collecting runs as a job. The feature's services live in
 * package com.cassandrastudio.engine.config.
 */
public final class ConfigRoutes {
    private ConfigRoutes() {}

    private static final String BASE = "/api/clusters/{id}/config";

    public static void register(RoutesConfig app, Engine engine) {
        ConfigService svc = new ConfigService(engine.db, engine.connections, engine.topology, engine.jobs,
                engine::secretsFor,
                connectionId -> new NodeCollector(engine.jmx, engine.shell, () -> engine.sessions.session(connectionId))::collect);
        engine.onClose(svc);
        engine.onDisconnect(svc::forget);

        app.post(BASE + "/collect", ctx -> ctx.status(202).json(svc.collect(id(ctx))));
        app.get(BASE + "/snapshot", ctx -> ctx.json(svc.snapshot(id(ctx))));
        app.get(BASE + "/drift", ctx -> ctx.json(svc.drift(id(ctx), ctx.queryParam("scope"),
                bool(ctx.queryParam("onlyDifferences"), true), bool(ctx.queryParam("hiera"), true))));
        app.get(BASE + "/hiera", ctx -> ctx.json(svc.hieraSettings(id(ctx))));
        app.put(BASE + "/hiera", ctx -> {
            JsonNode b = body(ctx);
            HieraSettings s;
            try {
                s = Json.MAPPER.treeToValue(b, HieraSettings.class);
            } catch (Exception e) {
                throw ApiException.badRequest("Hiera settings are not valid: facts and certnames must map names to text");
            }
            ctx.json(svc.setHieraSettings(id(ctx), s));
        });
        app.get(BASE + "/hiera/options", ctx -> ctx.json(svc.hieraOptions(id(ctx), ctx.queryParam("repoPath"))));
    }

    private static boolean bool(String v, boolean dflt) {
        if (v == null || v.isBlank()) return dflt;
        if (v.equalsIgnoreCase("true") || v.equals("1")) return true;
        if (v.equalsIgnoreCase("false") || v.equals("0")) return false;
        throw ApiException.badRequest("Expected true or false, got " + v);
    }
}
