package com.cassandrastudio.engine.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.cassandrastudio.engine.api.MonitoringRoutes;
import com.cassandrastudio.engine.conn.ConnectionRepository;
import com.cassandrastudio.engine.model.ConnectionConfig.JmxMethod;
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
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The routes over a real Javalin on a random port, with the same error mapping as EngineServer. */
class MonitoringRoutesTest {
    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    private Database db;
    private MonitoringService svc;
    private Javalin app;
    private String id;

    @BeforeEach
    void setUp() {
        db = Database.inMemory();
        ConnectionRepository repo = new ConnectionRepository(db, SecretStores.inMemory());
        id = repo.save(MonitoringServiceTest.conn("c1", JmxMethod.DIRECT), Map.of()).id();
        FakeNodes.Jmx jmx = new FakeNodes.Jmx().add(FakeNodes.cassandra41G1("10.0.0.1", "h1"));
        svc = new MonitoringService(db, repo, jmx, new FakeNodes.Topo());
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
            MonitoringRoutes.register(cfg.routes, svc);
        });
        app.start("127.0.0.1", 0);
    }

    @AfterEach
    void tearDown() {
        app.stop();
        svc.close();
        db.close();
    }

    private HttpResponse<String> call(String method, String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json").build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> r) throws Exception {
        return Json.MAPPER.readTree(r.body());
    }

    @Test
    void lifecycleAndReads() throws Exception {
        String base = "/api/clusters/" + id + "/monitoring";
        HttpResponse<String> notStarted = call("GET", base + "/snapshot", null);
        assertThat(notStarted.statusCode()).isEqualTo(409);
        assertThat(json(notStarted).get("error").asText()).isEqualTo("not_started");

        assertThat(call("POST", base + "/start", "{\"intervalSec\": 1}").statusCode()).isEqualTo(400);
        assertThat(call("POST", base + "/start", "{\"intervalSec\": \"x\"}").statusCode()).isEqualTo(400);
        HttpResponse<String> started = call("POST", base + "/start", "{\"intervalSec\": 30}");
        assertThat(started.statusCode()).isEqualTo(200);
        assertThat(json(started).get("polling").asBoolean()).isTrue();
        assertThat(json(started).get("pollIntervalSec").asInt()).isEqualTo(30);
        assertThat(call("POST", base + "/start", "").statusCode()).isEqualTo(200); // default interval

        JsonNode snap = json(call("GET", base + "/snapshot", null));
        assertThat(snap.get("nodes")).hasSize(3);
        assertThat(snap.get("health").get("level").asText()).isEqualTo("RED");
        assertThat(json(call("GET", base + "/status", null)).get("method").asText()).isEqualTo("DIRECT");
        assertThat(json(call("GET", base + "/alerts", null)).isArray()).isTrue();

        JsonNode series = json(call("GET", base + "/series?metric=heap.used&node=10.0.0.1", null));
        assertThat(series.get("unit").asText()).isEqualTo("bytes");
        assertThat(series.get("pointsByNode").get("10.0.0.1").get(0)).hasSize(2);
        HttpResponse<String> bad = call("GET", base + "/series?metric=nope", null);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json(bad).get("details").get("metrics")).isNotEmpty();
        assertThat(call("GET", base + "/series?metric=heap.used&fromMs=abc", null).statusCode()).isEqualTo(400);

        JsonNode ring = json(call("GET", base + "/ring?keyspace=shop", null));
        assertThat(ring.get("keyspace").asText()).isEqualTo("shop");
        assertThat(ring.get("datacenters").get(0).get("nodes")).hasSize(3);
        JsonNode tables = json(call("GET", base + "/tables", null));
        assertThat(tables.get(0).get("table").asText()).isEqualTo("orders");

        assertThat(json(call("GET", base + "/thresholds", null)).get("heap.high.yellowPct").asDouble()).isEqualTo(85.0);
        HttpResponse<String> put = call("PUT", base + "/thresholds", "{\"heap.high.yellowPct\": 70, \"heap.high.redPct\": null}");
        assertThat(put.statusCode()).isEqualTo(200);
        assertThat(json(put).get("heap.high.yellowPct").asDouble()).isEqualTo(70.0);
        assertThat(json(put).get("heap.high.redPct").asDouble()).isEqualTo(95.0);
        assertThat(call("PUT", base + "/thresholds", "{\"heap.high.yellowPct\": 99, \"heap.high.redPct\": 90}").statusCode()).isEqualTo(400);
        assertThat(call("PUT", base + "/thresholds", "[1]").statusCode()).isEqualTo(400);
        JsonNode effective = json(call("GET", base + "/thresholds", null));
        assertThat(effective.get("heap.high.yellowPct").asDouble()).isEqualTo(70.0);
        assertThat(effective.size()).isEqualTo(9);
        assertThat(effective.get("hints.backlog.polls").asInt()).isEqualTo(3);
        // PUT sends the full map: null clears that override
        assertThat(json(call("PUT", base + "/thresholds", "{\"heap.high.yellowPct\": null}"))
                .get("heap.high.yellowPct").asDouble()).isEqualTo(85.0);

        assertThat(call("POST", base + "/stop", null).statusCode()).isEqualTo(204);
        assertThat(call("GET", base + "/snapshot", null).statusCode()).isEqualTo(409);
        assertThat(call("GET", "/api/clusters/unknown/monitoring/status", null).statusCode()).isEqualTo(404);
    }
}
