package com.cassandrastudio.engine.api;

import static com.cassandrastudio.engine.api.RouteSupport.body;
import static com.cassandrastudio.engine.api.RouteSupport.confirmation;
import static com.cassandrastudio.engine.api.RouteSupport.id;
import static com.cassandrastudio.engine.api.RouteSupport.requiredText;
import static com.cassandrastudio.engine.api.RouteSupport.text;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.backup.BackupService;
import com.cassandrastudio.engine.backup.BackupSettings;
import com.cassandrastudio.engine.cql.ClusterService.NodeInfo;
import com.cassandrastudio.engine.jmx.NodeEndpoint;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.util.ApiException;
import com.cassandrastudio.engine.util.Json;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.config.RoutesConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Phase 3 Track 5, backups (BAK-1..3), docs/api/backup.md. Routes live under
 * /api/clusters/{id}/backup/...; the service is in com.cassandrastudio.engine.backup.
 */
public final class BackupRoutes {
    private BackupRoutes() {}

    private static final String BASE = "/api/clusters/{id}/backup";
    /** Keyspaces a full estate backup uploads besides the user keyspaces. */
    private static final Set<String> BACKED_UP_SYSTEM = Set.of("system_schema", "system_auth", "system_distributed");

    public static void register(RoutesConfig app, Engine engine) {
        BackupService svc = new BackupService(engine.db, engine.connections, engine.guard, engine.jobs, engine.topology,
                nodes(engine));
        engine.onClose(svc);
        engine.onDisconnect(svc::forget);
        register(app, svc);
    }

    public static BackupService.Nodes nodes(Engine engine) {
        return new BackupService.Nodes() {
            @Override
            public String exec(ConnectionConfig cfg, String host, String command, Duration timeout) {
                return engine.shell.exec(cfg, engine.secretsFor(cfg.id()), host, command, timeout, 256 * 1024);
            }

            @Override
            public <T> T jmx(ConnectionConfig cfg, NodeInfo n, BackupService.JmxCall<T> call) throws Exception {
                var session = engine.opsJmx.session(cfg, engine.secretsFor(cfg.id()),
                        new NodeEndpoint(n.hostId(), n.address(), n.datacenter(), n.rack(), n.version()));
                return call.call(session.mbeans());
            }

            @Override
            public int expectedTables(String connectionId) {
                int count = 0;
                for (KeyspaceMetadata ks : engine.sessions.session(connectionId).getMetadata().getKeyspaces().values()) {
                    String name = ks.getName().asInternal();
                    boolean system = name.startsWith("system") || name.startsWith("dse") || name.startsWith("solr");
                    if (!system || BACKED_UP_SYSTEM.contains(name)) count += ks.getTables().size();
                }
                return count;
            }
        };
    }

    public static void register(RoutesConfig app, BackupService svc) {
        app.get(BASE + "/settings", ctx -> ctx.json(svc.settings(id(ctx))));
        app.put(BASE + "/settings", ctx -> {
            BackupSettings s;
            try {
                s = Json.MAPPER.treeToValue(body(ctx), BackupSettings.class);
            } catch (Exception e) {
                throw ApiException.badRequest("Settings body is not valid: " + firstLine(e.getMessage()));
            }
            ctx.json(svc.saveSettings(id(ctx), s));
        });
        app.post(BASE + "/detect", ctx -> ctx.json(svc.detect(id(ctx))));
        app.get(BASE + "/catalogue", ctx -> ctx.json(svc.catalogue(id(ctx))));
        app.post(BASE + "/run", ctx -> {
            JsonNode b = body(ctx);
            ctx.status(202).json(svc.run(id(ctx), request(b), confirmation(b)));
        });
        app.get(BASE + "/runs/{jobId}", ctx -> ctx.json(svc.runStatus(id(ctx), ctx.pathParam("jobId"))));
        app.post(BASE + "/snapshots/clear", ctx -> {
            JsonNode b = body(ctx);
            ctx.json(svc.clearSnapshot(id(ctx), requiredText(b, "node"), requiredText(b, "tag"), confirmation(b)));
        });
    }

    static BackupService.RunRequest request(JsonNode b) {
        BackupService.Scope scope;
        String sc = requiredText(b, "scope");
        try {
            scope = BackupService.Scope.valueOf(sc.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("scope must be CLUSTER, DC or NODE");
        }
        Integer concurrency = null;
        if (b.hasNonNull("concurrency")) {
            if (!b.get("concurrency").canConvertToInt()) throw ApiException.badRequest("concurrency must be a number");
            concurrency = b.get("concurrency").asInt();
        }
        List<String> keyspaces = new ArrayList<>();
        if (b.hasNonNull("keyspaces")) {
            if (!b.get("keyspaces").isArray()) throw ApiException.badRequest("keyspaces must be a list");
            b.get("keyspaces").forEach(k -> keyspaces.add(k.asText()));
        }
        return new BackupService.RunRequest(scope, text(b, "datacenter"), text(b, "node"), text(b, "mode"), concurrency,
                text(b, "throttle"), text(b, "name"), keyspaces);
    }

    private static String firstLine(String m) {
        if (m == null) return "";
        int nl = m.indexOf('\n');
        return nl < 0 ? m : m.substring(0, nl);
    }
}
