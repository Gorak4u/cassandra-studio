package com.cassandrastudio.engine.api;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.Version;
import com.cassandrastudio.engine.net.NetworkService;
import com.cassandrastudio.engine.net.NetworkSettings;
import com.cassandrastudio.engine.net.UpdateChecker;
import com.cassandrastudio.engine.util.ApiException;
import com.cassandrastudio.engine.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.config.RoutesConfig;

/**
 * Global network settings and the update check (NFR-NET, NFR-UPD, NFR-SEC); docs/api/network.md.
 * <pre>
 *   GET /api/settings/network     settings + what is in effect
 *   PUT /api/settings/network     save (body: settings fields + optional proxyPassword)
 *   GET /api/updates[?force=true] update check (never runs when disabled or offline)
 * </pre>
 */
public final class NetworkRoutes {
    private NetworkRoutes() {}

    public static void register(RoutesConfig app, Engine engine) {
        NetworkService net = new NetworkService(engine.db, engine.secrets, new UpdateChecker(Version.VERSION));
        net.start();

        app.get("/api/settings/network", ctx -> ctx.json(net.view()));
        app.put("/api/settings/network", ctx -> {
            JsonNode b = RouteSupport.body(ctx);
            String pw = b.has("proxyPassword") && !b.get("proxyPassword").isNull() ? b.get("proxyPassword").asText() : null;
            ObjectNode fields = ((ObjectNode) b).deepCopy();
            fields.remove("proxyPassword");
            NetworkSettings s;
            try {
                s = Json.MAPPER.treeToValue(fields, NetworkSettings.class);
            } catch (Exception e) {
                throw ApiException.badRequest("Invalid network settings: " + e.getMessage().lines().findFirst().orElse(""));
            }
            ctx.json(net.save(s, pw));
        });
        app.get("/api/updates", ctx -> ctx.json(net.updates("true".equals(ctx.queryParam("force")))));
    }
}
