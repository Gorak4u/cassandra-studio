package com.cassandrastudio.engine.api;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.Version;
import com.cassandrastudio.engine.conn.ConnectionRepository;
import com.cassandrastudio.engine.cql.QueryService;
import com.cassandrastudio.engine.cql.RowEditService;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.schema.DdlBuilder;
import com.cassandrastudio.engine.security.RoleService;
import com.cassandrastudio.engine.util.ApiException;
import com.cassandrastudio.engine.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.Javalin;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The engine's HTTP API. Bound to 127.0.0.1 and protected by a per-launch
 * bearer token in desktop mode; the same API backs Studio Server later.
 */
public final class EngineServer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(EngineServer.class);

    private final Engine engine;
    private final String token;
    private final Javalin app;
    private final String bindHost;

    public record Options(String host, int port, String token, Path uiDir, boolean devCors) {}

    public EngineServer(Engine engine, Options options) {
        this.engine = engine;
        this.token = options.token();
        this.bindHost = options.host();
        this.app = Javalin.create(cfg -> {
            cfg.startup.showJavalinBanner = false;
            cfg.startup.showOldJavalinVersionWarning = false;
            cfg.jsonMapper(new JavalinJackson(Json.MAPPER, false));
            cfg.http.maxRequestSize = 64L * 1024 * 1024;
            if (options.uiDir() != null && Files.isDirectory(options.uiDir())) {
                cfg.staticFiles.add(sf -> {
                    sf.directory = options.uiDir().toAbsolutePath().toString();
                    sf.location = Location.EXTERNAL;
                    sf.hostedPath = "/";
                });
                cfg.spaRoot.addFile("/", options.uiDir().resolve("index.html").toAbsolutePath().toString(), Location.EXTERNAL);
            }
            if (options.devCors()) {
                cfg.bundledPlugins.enableCors(cors -> cors.addRule(rule -> {
                    rule.allowHost("http://localhost:5173", "http://127.0.0.1:5173");
                }));
            }
            security(cfg.routes);
            errors(cfg.routes);
            routes(cfg.routes);
        });
        app.start(options.host(), options.port());
    }

    public int port() {
        return app.port();
    }

    // ---- security ----------------------------------------------------------

    private void security(RoutesConfig app) {
        app.before("/api/*", ctx -> {
            if (ctx.method().name().equals("OPTIONS")) return;
            if (isLoopback(bindHost)) {
                // DNS-rebinding protection: a page on another site must not reach us via a name that resolves to 127.0.0.1.
                String host = ctx.header("Host");
                String h = host == null ? "" : host.replaceFirst(":\\d+$", "");
                if (!Set.of("127.0.0.1", "localhost", "[::1]").contains(h)) {
                    throw new ApiException(403, "bad_host", "Requests must use 127.0.0.1 or localhost");
                }
            }
            String auth = ctx.header("Authorization");
            String presented = auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : ctx.queryParam("token");
            if (presented == null || !MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
                throw new ApiException(401, "unauthorized", "Missing or wrong engine token");
            }
        });
        app.after(ctx -> {
            ctx.header("X-Content-Type-Options", "nosniff");
            ctx.header("Referrer-Policy", "no-referrer");
            ctx.header("Cache-Control", ctx.path().startsWith("/api/") ? "no-store" : "no-cache");
        });
    }

    private static boolean isLoopback(String host) {
        return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1");
    }

    private void errors(RoutesConfig app) {
        app.exception(ApiException.class, (e, ctx) -> error(ctx, e.status(), e.code(), e.getMessage(), e.details()));
        app.exception(IllegalArgumentException.class, (e, ctx) -> error(ctx, 400, "bad_request", e.getMessage(), Map.of()));
        app.exception(Exception.class, (e, ctx) -> {
            LOG.error("Unhandled error on {} {}", ctx.method(), ctx.path(), e);
            error(ctx, 500, "internal_error", e.getClass().getSimpleName() + ": " + e.getMessage(), Map.of());
        });
    }

    private static void error(Context ctx, int status, String code, String message, Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        if (details != null && !details.isEmpty()) body.put("details", details);
        ctx.status(status).json(body);
    }

    // ---- routes --------------------------------------------------------------

    private void routes(RoutesConfig app) {
        app.get("/api/info", ctx -> ctx.json(Map.of(
                "version", Version.VERSION,
                "secretStore", engine.connections.secretStoreDescription(),
                "actor", engine.audit.actor(),
                "dbVersion", engine.db.schemaVersion())));

        // folders (CON-2)
        app.get("/api/folders", ctx -> ctx.json(engine.connections.folders()));
        app.post("/api/folders", ctx -> {
            JsonNode b = body(ctx);
            ctx.status(201).json(engine.connections.createFolder(text(b, "parentId"), text(b, "name")));
        });
        app.put("/api/folders/{id}", ctx -> {
            JsonNode b = body(ctx);
            ctx.json(engine.connections.updateFolder(ctx.pathParam("id"), text(b, "parentId"), text(b, "name"),
                    b.hasNonNull("position") ? b.get("position").asInt() : null));
        });
        app.delete("/api/folders/{id}", ctx -> {
            engine.connections.deleteFolder(ctx.pathParam("id"));
            ctx.status(204);
        });

        // connections (CON-1, CON-8, CON-9)
        app.get("/api/connections", ctx -> ctx.json(engine.connections.list()));
        app.get("/api/connections/export", ctx -> {
            ctx.header("Content-Disposition", "attachment; filename=\"cassandra-studio-connections.json\"");
            ctx.json(engine.connections.export());
        });
        app.post("/api/connections/import", ctx ->
                ctx.json(engine.connections.importFile(ctx.bodyAsClass(ConnectionRepository.ImportFile.class))));
        app.post("/api/connections/test", ctx -> {
            SaveRequest r = ctx.bodyAsClass(SaveRequest.class);
            Map<String, String> secrets = new LinkedHashMap<>();
            if (r.connection().id() != null) {
                for (String k : ConnectionConfig.SecretKeys.ALL) {
                    engine.connections.secret(r.connection().id(), k).ifPresent(v -> secrets.put(k, v));
                }
            }
            if (r.secrets() != null) r.secrets().forEach((k, v) -> { if (v != null) secrets.put(k, v); });
            ctx.json(engine.sessions.test(r.connection(), secrets));
        });
        app.get("/api/connections/{id}", ctx -> ctx.json(engine.connections.get(ctx.pathParam("id"))));
        app.post("/api/connections", ctx -> {
            SaveRequest r = ctx.bodyAsClass(SaveRequest.class);
            if (r.connection() == null) throw ApiException.badRequest("connection required");
            ctx.status(201).json(engine.connections.save(r.connection().withId(null), r.secrets()));
        });
        app.put("/api/connections/{id}", ctx -> {
            SaveRequest r = ctx.bodyAsClass(SaveRequest.class);
            String id = ctx.pathParam("id");
            engine.connections.get(id);
            ConnectionConfig saved = engine.connections.save(r.connection().withId(id), r.secrets());
            engine.disconnect(id); // settings changed: reconnect on next use
            ctx.json(saved);
        });
        app.delete("/api/connections/{id}", ctx -> {
            engine.disconnect(ctx.pathParam("id"));
            engine.connections.delete(ctx.pathParam("id"));
            ctx.status(204);
        });
        app.post("/api/connections/{id}/clone", ctx -> ctx.status(201).json(engine.connections.cloneConnection(ctx.pathParam("id"))));
        app.post("/api/connections/{id}/connect", ctx -> {
            engine.sessions.session(ctx.pathParam("id"));
            ctx.json(engine.clusters.info(ctx.pathParam("id")));
        });
        app.post("/api/connections/{id}/disconnect", ctx -> {
            engine.disconnect(ctx.pathParam("id"));
            ctx.status(204);
        });
        app.get("/api/connections/{id}/status", ctx ->
                ctx.json(Map.of("connected", engine.sessions.isConnected(ctx.pathParam("id")))));

        // monitoring (Phase 2, docs/api/monitoring.md)
        MonitoringRoutes.register(app, engine.monitoring);

        // Phase 3 (docs/api/jobs.md and one routes class per feature)
        JobRoutes.register(app, engine.jobs);
        OpsRoutes.register(app, engine);
        DiagRoutes.register(app, engine);
        GcLogRoutes.register(app, engine);
        ConfigRoutes.register(app, engine);
        BackupRoutes.register(app, engine);
        BulkRoutes.register(app, engine);
        NetworkRoutes.register(app, engine);

        // cluster (CON-5)
        app.get("/api/clusters/{id}/info", ctx -> ctx.json(engine.clusters.info(ctx.pathParam("id"))));

        // CQL (CQL-1 ... CQL-10)
        app.post("/api/clusters/{id}/query", ctx ->
                ctx.json(engine.queries.execute(ctx.pathParam("id"), ctx.bodyAsClass(QueryService.QueryRequest.class))));
        app.get("/api/clusters/{id}/history", ctx -> ctx.json(engine.queries.history(ctx.pathParam("id"),
                ctx.queryParam("q"), intParam(ctx, "limit", 200))));
        app.delete("/api/clusters/{id}/history", ctx -> {
            engine.queries.clearHistory(ctx.pathParam("id"));
            ctx.status(204);
        });
        app.post("/api/clusters/{id}/rows/cql", ctx -> ctx.json(Map.of("cql",
                engine.rowEdits.toCql(ctx.pathParam("id"), ctx.bodyAsClass(RowEditService.RowEdit.class)))));

        // schema (SCH)
        app.get("/api/clusters/{id}/schema", ctx ->
                ctx.json(engine.schema.tree(ctx.pathParam("id"), "true".equals(ctx.queryParam("refresh")))));
        app.get("/api/clusters/{id}/schema/completions", ctx -> ctx.json(engine.schema.completions(ctx.pathParam("id"))));
        app.get("/api/clusters/{id}/schema/keyspaces/{ks}", ctx ->
                ctx.json(engine.schema.keyspace(ctx.pathParam("id"), ctx.pathParam("ks"))));
        app.get("/api/clusters/{id}/schema/keyspaces/{ks}/tables/{table}", ctx ->
                ctx.json(engine.schema.table(ctx.pathParam("id"), ctx.pathParam("ks"), ctx.pathParam("table"))));
        app.post("/api/ddl/{op}", ctx -> ctx.json(Map.of("cql", ddl(ctx.pathParam("op"), body(ctx)))));

        // users and roles (SEC)
        app.get("/api/clusters/{id}/roles", ctx -> ctx.json(engine.roles.list(ctx.pathParam("id"))));
        app.get("/api/clusters/{id}/roles/{role}/permissions", ctx ->
                ctx.json(engine.roles.permissionsOf(ctx.pathParam("id"), ctx.pathParam("role"))));
        app.post("/api/roles-cql/{op}", ctx -> ctx.json(Map.of("cql", rolesCql(ctx.pathParam("op"), body(ctx)))));

        // saved scripts (CQL-8)
        app.get("/api/scripts", ctx -> ctx.json(engine.scripts.list()));
        app.get("/api/scripts/{id}", ctx -> ctx.json(engine.scripts.get(ctx.pathParam("id"))));
        app.post("/api/scripts", ctx -> {
            JsonNode b = body(ctx);
            ctx.status(201).json(engine.scripts.save(null, text(b, "folder"), text(b, "name"), text(b, "content")));
        });
        app.put("/api/scripts/{id}", ctx -> {
            JsonNode b = body(ctx);
            ctx.json(engine.scripts.save(ctx.pathParam("id"), text(b, "folder"), text(b, "name"), text(b, "content")));
        });
        app.delete("/api/scripts/{id}", ctx -> {
            engine.scripts.delete(ctx.pathParam("id"));
            ctx.status(204);
        });

        // audit (NFR-AUD)
        app.get("/api/audit", ctx -> ctx.json(engine.audit.search(ctx.queryParam("connectionId"), ctx.queryParam("q"),
                ctx.queryParam("since"), intParam(ctx, "limit", 500))));
    }

    public record SaveRequest(ConnectionConfig connection, Map<String, String> secrets) {}

    private static String ddl(String op, JsonNode b) {
        return switch (op) {
            case "createKeyspace" -> DdlBuilder.createKeyspace(as(b, DdlBuilder.KeyspaceSpec.class));
            case "alterKeyspace" -> DdlBuilder.alterKeyspace(as(b, DdlBuilder.KeyspaceSpec.class));
            case "dropKeyspace" -> DdlBuilder.dropKeyspace(text(b, "keyspace"));
            case "createTable" -> DdlBuilder.createTable(as(b, DdlBuilder.TableSpec.class));
            case "alterTableOptions" -> DdlBuilder.alterTableOptions(text(b, "keyspace"), text(b, "table"),
                    Json.MAPPER.convertValue(b.get("options"), Json.MAPPER.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class)));
            case "addColumn" -> DdlBuilder.addColumn(text(b, "keyspace"), text(b, "table"), as(b.get("column"), DdlBuilder.ColumnSpec.class));
            case "dropColumn" -> DdlBuilder.dropColumn(text(b, "keyspace"), text(b, "table"), text(b, "column"));
            case "dropTable" -> DdlBuilder.dropTable(text(b, "keyspace"), text(b, "table"));
            case "truncate" -> DdlBuilder.truncate(text(b, "keyspace"), text(b, "table"));
            case "createType" -> DdlBuilder.createType(as(b, DdlBuilder.TypeSpec.class));
            case "dropType" -> DdlBuilder.dropType(text(b, "keyspace"), text(b, "name"));
            case "createIndex" -> DdlBuilder.createIndex(as(b, DdlBuilder.IndexSpec.class));
            case "dropIndex" -> DdlBuilder.dropIndex(text(b, "keyspace"), text(b, "name"));
            case "dropView" -> DdlBuilder.dropView(text(b, "keyspace"), text(b, "name"));
            default -> throw ApiException.notFound("DDL operation " + op);
        };
    }

    private static String rolesCql(String op, JsonNode b) {
        return switch (op) {
            case "createRole" -> RoleService.createRole(as(b, RoleService.RoleSpec.class));
            case "alterRole" -> RoleService.alterRole(as(b, RoleService.RoleSpec.class));
            case "dropRole" -> RoleService.dropRole(text(b, "name"));
            case "grantRole" -> RoleService.grantRole(text(b, "role"), text(b, "to"));
            case "revokeRole" -> RoleService.revokeRole(text(b, "role"), text(b, "from"));
            case "grantPermission", "revokePermission" -> RoleService.grantPermission(text(b, "permission"),
                    text(b, "resourceType"), text(b, "resourceName"), text(b, "role"), op.startsWith("revoke"));
            default -> throw ApiException.notFound("Role operation " + op);
        };
    }

    private static JsonNode body(Context ctx) {
        try {
            return Json.MAPPER.readTree(ctx.body().isBlank() ? "{}" : ctx.body());
        } catch (Exception e) {
            throw ApiException.badRequest("Body is not valid JSON");
        }
    }

    private static <T> T as(JsonNode n, Class<T> type) {
        if (n == null) throw ApiException.badRequest("Missing body");
        return Json.MAPPER.convertValue(n, type);
    }

    private static String text(JsonNode b, String field) {
        return b.hasNonNull(field) ? b.get(field).asText() : null;
    }

    private static int intParam(Context ctx, String name, int dflt) {
        String v = ctx.queryParam(name);
        try {
            return v == null ? dflt : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(name + " must be a number");
        }
    }

    @Override
    public void close() {
        app.stop();
    }
}
