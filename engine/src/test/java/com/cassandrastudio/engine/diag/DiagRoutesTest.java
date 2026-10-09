package com.cassandrastudio.engine.diag;

import static org.assertj.core.api.Assertions.assertThat;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.api.DiagRoutes;
import com.cassandrastudio.engine.model.ConnectionConfig;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Validation, settings and the dump store over HTTP (no cluster needed for these paths). */
class DiagRoutesTest {
    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    private Engine engine;
    private Javalin app;
    private String base;

    @BeforeEach
    void setUp() {
        engine = new Engine(Database.inMemory(), SecretStores.inMemory(), "test");
        String id = engine.connections.save(new ConnectionConfig(null, null, "c", null, null, false, List.of("127.0.0.1:1"),
                null, null, null, null, null, null, null, null, null, null, null, null), Map.of()).id();
        base = "/api/clusters/" + id + "/diag";
        app = Javalin.create(cfg -> {
            cfg.startup.showJavalinBanner = false;
            cfg.jsonMapper(new JavalinJackson(Json.MAPPER, false));
            cfg.routes.exception(ApiException.class, (e, ctx) -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("error", e.code());
                body.put("message", e.getMessage());
                ctx.status(e.status()).json(body);
            });
            DiagRoutes.register(cfg.routes, engine);
        });
        app.start("127.0.0.1", 0);
    }

    @AfterEach
    void tearDown() {
        app.stop();
        engine.close();
    }

    private HttpResponse<String> call(String method, String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + base + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json").build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> r) throws Exception {
        return Json.MAPPER.readTree(r.body());
    }

    @Test
    void validatesInput() throws Exception {
        assertThat(call("POST", "/threads/series", "{\"node\":\"n\",\"count\":50}").statusCode()).isEqualTo(400);
        assertThat(call("POST", "/threads/series", "{\"node\":\"n\",\"count\":\"x\"}").statusCode()).isEqualTo(400);
        assertThat(call("GET", "/threads/compare?a=x", null).statusCode()).isEqualTo(400);
        assertThat(call("GET", "/threads/top?node=n&limit=0", null).statusCode()).isEqualTo(400);
        HttpResponse<String> noTables = call("POST", "/partitions/hot", "{\"tables\":[]}");
        assertThat(noTables.statusCode()).isEqualTo(400);
        assertThat(json(noTables).get("message").asText()).contains("at least one table");
        assertThat(call("POST", "/partitions/hot", "{\"tables\":[\"no-dot\"]}").statusCode()).isEqualTo(400);
        assertThat(call("POST", "/partitions/hot", "{\"tables\":[\"ks.t\"],\"durationSec\":0}").statusCode()).isEqualTo(400);
        assertThat(call("POST", "/partitions/hot", "{\"tables\":\"ks.t\"}").statusCode()).isEqualTo(400);
        assertThat(call("GET", "/partitions/histograms?keyspace=bad;name", null).statusCode()).isEqualTo(400);
        assertThat(call("GET", "/partitions/warnings?limit=0", null).statusCode()).isEqualTo(400);
        assertThat(call("POST", "/partitions/tombstone-scan", "{\"node\":\"n\",\"table\":\"t\"}").statusCode()).isEqualTo(400);
    }

    @Test
    void dumpStoreAndSettings() throws Exception {
        assertThat(json(call("GET", "/threads/dumps", null)).size()).isZero();
        assertThat(call("GET", "/threads/dumps/nope", null).statusCode()).isEqualTo(404);
        assertThat(call("GET", "/threads/dumps/nope/text", null).statusCode()).isEqualTo(404);

        JsonNode s = json(call("GET", "/settings", null));
        assertThat(s.get("logPath").asText()).isEqualTo("/var/log/cassandra/system.log");
        assertThat(s.get("largePartitionMb").asLong()).isEqualTo(100);
        HttpResponse<String> put = call("PUT", "/settings", "{\"logPath\":\"/opt/cassandra/logs/system.log\",\"tombstonesP99\":500}");
        assertThat(put.statusCode()).isEqualTo(200);
        JsonNode after = json(call("GET", "/settings", null));
        assertThat(after.get("logPath").asText()).isEqualTo("/opt/cassandra/logs/system.log");
        assertThat(after.get("tombstonesP99").asDouble()).isEqualTo(500);
        assertThat(after.get("largePartitionMb").asLong()).isEqualTo(100);
        assertThat(call("PUT", "/settings", "{\"logPath\":\"rel\"}").statusCode()).isEqualTo(400);
    }
}
