package com.cassandrastudio.engine.backup;

import static org.assertj.core.api.Assertions.assertThat;

import com.cassandrastudio.engine.api.BackupRoutes;
import com.cassandrastudio.engine.audit.AuditLog;
import com.cassandrastudio.engine.conn.ConnectionRepository;
import com.cassandrastudio.engine.cql.ClusterService.ClusterInfo;
import com.cassandrastudio.engine.cql.ClusterService.NodeInfo;
import com.cassandrastudio.engine.guard.ActionGuard;
import com.cassandrastudio.engine.jobs.JobService;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.Environment;
import com.cassandrastudio.engine.secrets.SecretStores;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.ApiException;
import com.cassandrastudio.engine.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The routes over a real Javalin with the server's error mapping: JSON shapes, 400/409/428. */
class BackupRoutesTest {
    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    private Database db;
    private JobService jobs;
    private BackupService svc;
    private Javalin app;
    private String base;

    @BeforeEach
    void setUp() {
        db = Database.inMemory();
        ConnectionRepository repo = new ConnectionRepository(db, SecretStores.inMemory());
        String id = repo.save(new ConnectionConfig(null, null, "prod-core", Environment.PROD, null, false,
                List.of("10.0.0.1"), "dc1", null, null, null, null, null, null, null, null, List.of(), null, null), Map.of()).id();
        AuditLog audit = new AuditLog(db, "t");
        jobs = new JobService(repo, audit);
        NodeInfo n = new NodeInfo("h1", "10.0.0.1", 9042, "dc1", "r1", "4.1.5", "UP", 16, "v1", 1);
        ClusterInfo info = new ClusterInfo("c", "M", List.of("dc1"), List.of(n), true, List.of("4.1.5"), "V5");
        svc = new BackupService(db, repo, new ActionGuard(audit), jobs, c -> info, new BackupService.Nodes() {
            @Override
            public String exec(ConnectionConfig cfg, String host, String command, Duration timeout) {
                throw new IllegalStateException("no SSH here");
            }

            @Override
            public <T> T jmx(ConnectionConfig cfg, NodeInfo node, BackupService.JmxCall<T> call) {
                throw new IllegalStateException("no JMX here");
            }
        });
        app = Javalin.create(cfg -> {
            cfg.startup.showJavalinBanner = false;
            cfg.jsonMapper(new JavalinJackson(Json.MAPPER, false));
            cfg.routes.exception(ApiException.class, (e, ctx) -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("error", e.code());
                body.put("message", e.getMessage());
                if (!e.details().isEmpty()) body.put("details", e.details());
                ctx.status(e.status()).json(body);
            });
            BackupRoutes.register(cfg.routes, svc);
        });
        app.start("127.0.0.1", 0);
        base = "http://127.0.0.1:" + app.port() + "/api/clusters/" + id + "/backup";
    }

    @AfterEach
    void tearDown() {
        app.stop();
        svc.close();
        jobs.close();
        db.close();
    }

    private HttpResponse<String> call(String method, String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json").build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void settingsCatalogueAndGuardedRun() throws Exception {
        assertThat(Json.MAPPER.readTree(call("GET", "/settings", null).body()).path("provider").isTextual()).isFalse();
        assertThat(call("GET", "/catalogue", null).statusCode()).isEqualTo(409);
        assertThat(call("PUT", "/settings", "{\"provider\":\"NOPE\"}").statusCode()).isEqualTo(400);
        assertThat(call("PUT", "/settings", "{\"provider\":\"ESTATE\",\"scriptDir\":\"/x; reboot\"}").statusCode()).isEqualTo(400);
        HttpResponse<String> saved = call("PUT", "/settings", "{\"provider\":\"ESTATE\",\"privilege\":\"SUDO\"}");
        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(Json.MAPPER.readTree(saved.body()).get("scriptDir").asText()).isEqualTo("/usr/local/bin");

        assertThat(call("POST", "/run", "{\"scope\":\"EVERYWHERE\"}").statusCode()).isEqualTo(400);
        assertThat(call("POST", "/run", "{\"scope\":\"CLUSTER\",\"concurrency\":\"x\"}").statusCode()).isEqualTo(400);
        // PROD: confirmed=true is not enough, the typed name is required; preview is the exact command.
        HttpResponse<String> r = call("POST", "/run", "{\"scope\":\"cluster\",\"mode\":\"full\",\"confirmed\":true}");
        assertThat(r.statusCode()).isEqualTo(428);
        JsonNode d = Json.MAPPER.readTree(r.body()).get("details");
        assertThat(d.get("requireTypedName").asBoolean()).isTrue();
        assertThat(d.get("preview").get(0).asText()).isEqualTo("10.0.0.1: sudo -n '/usr/local/bin/full-backup-to-s3.sh'");

        // Catalogue with the node unreachable: 200 with the node's error, not a failure.
        JsonNode cat = Json.MAPPER.readTree(call("GET", "/catalogue", null).body());
        assertThat(cat.get("nodes").get(0).get("ok").asBoolean()).isFalse();
        assertThat(cat.get("nodes").get(0).get("error").asText()).contains("no SSH here");
        assertThat(call("GET", "/runs/nope", null).statusCode()).isEqualTo(404);
    }
}
