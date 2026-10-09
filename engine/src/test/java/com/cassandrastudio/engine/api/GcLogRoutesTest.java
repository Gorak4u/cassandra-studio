package com.cassandrastudio.engine.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.secrets.SecretStores;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The GC log API over HTTP with an uploaded log (no cluster needed). */
class GcLogRoutesTest {
    private static final String TOKEN = "test-token";
    private Engine engine;
    private EngineServer server;
    private String connId;
    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    @BeforeEach
    void start() throws Exception {
        engine = new Engine(Database.inMemory(), SecretStores.inMemory(), "tester");
        server = new EngineServer(engine, new EngineServer.Options("127.0.0.1", 0, TOKEN, null, false));
        HttpResponse<String> c = call("POST", "/api/connections", """
                {"connection": {"name": "gc", "environment": "DEV", "contactPoints": ["127.0.0.1:1"]}}""".getBytes(StandardCharsets.UTF_8));
        connId = Json.MAPPER.readTree(c.body()).get("id").asText();
    }

    @AfterEach
    void stop() {
        server.close();
        engine.close();
    }

    private HttpResponse<String> call(String method, String path, byte[] body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Authorization", "Bearer " + TOKEN);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] fixture(String name) throws Exception {
        try (InputStream in = GcLogRoutesTest.class.getResourceAsStream("/gclog/" + name)) {
            return in.readAllBytes();
        }
    }

    @Test
    void uploadAnalyseWindowAndDelete() throws Exception {
        String base = "/api/clusters/" + connId + "/gclog";
        HttpResponse<String> up = call("POST", base + "/upload?name=gc.log", fixture("java8-cms-failures.log"));
        assertThat(up.statusCode()).isEqualTo(201);
        JsonNode info = Json.MAPPER.readTree(up.body());
        String aid = info.get("id").asText();
        assertThat(info.get("collector").asText()).isEqualTo("CMS");
        assertThat(info.get("sourceKind").asText()).isEqualTo("upload");

        JsonNode list = Json.MAPPER.readTree(call("GET", base + "/analyses", null).body());
        assertThat(list).hasSize(1);

        HttpResponse<String> rep = call("GET", base + "/analyses/" + aid, null);
        assertThat(rep.statusCode()).isEqualTo(200);
        JsonNode r = Json.MAPPER.readTree(rep.body());
        assertThat(r.get("summary").get("pauses").get("count").asInt()).isEqualTo(8);
        assertThat(r.get("findings").size()).isGreaterThan(3);
        assertThat(r.get("findings").get(0).get("severity").asText()).isEqualTo("critical");
        assertThat(r.get("events").get(0).has("dateStamp")).isFalse();
        assertThat(r.get("events").get(0).has("edenBefore")).isFalse();
        assertThat(r.get("series").get("gcTimePct").size()).isPositive();
        assertThat(r.get("log").get("timeAxis").asText()).isEqualTo("wall");

        JsonNode win = Json.MAPPER.readTree(call("GET", base + "/analyses/" + aid + "?fromX=0&toX=4.5", null).body());
        assertThat(win.get("summary").get("pauses").get("count").asInt()).isEqualTo(2);
        assertThat(call("GET", base + "/analyses/" + aid + "?fromX=abc", null).statusCode()).isEqualTo(400);
        assertThat(call("GET", base + "/analyses/" + aid + "?fromX=9&toX=1", null).statusCode()).isEqualTo(400);

        assertThat(call("DELETE", base + "/analyses/" + aid, null).statusCode()).isEqualTo(204);
        assertThat(call("GET", base + "/analyses/" + aid, null).statusCode()).isEqualTo(404);
    }

    @Test
    void rejectsWhatIsNotAGcLog() throws Exception {
        String base = "/api/clusters/" + connId + "/gclog";
        HttpResponse<String> r = call("POST", base + "/upload?name=notes.txt", "just some text\n".getBytes(StandardCharsets.UTF_8));
        assertThat(r.statusCode()).isEqualTo(422);
        assertThat(Json.MAPPER.readTree(r.body()).get("message").asText()).contains("No GC events");
        assertThat(call("POST", base + "/upload?name=empty", new byte[0]).statusCode()).isEqualTo(400);
        assertThat(call("POST", base + "/upload", "x".getBytes(StandardCharsets.UTF_8)).statusCode()).isEqualTo(400);
        assertThat(call("POST", "/api/clusters/nope/gclog/upload?name=a", "x".getBytes(StandardCharsets.UTF_8)).statusCode())
                .isEqualTo(404);
    }

    @Test
    void sshRoutesNeedSshAndValidInput() throws Exception {
        String base = "/api/clusters/" + connId + "/gclog";
        HttpResponse<String> f = call("POST", base + "/fetch",
                "{\"node\":\"10.0.0.1\",\"paths\":[\"/var/log/cassandra/gc.log\"]}".getBytes(StandardCharsets.UTF_8));
        // the cluster is not reachable, so the node cannot be checked
        assertThat(f.statusCode()).isEqualTo(502);
        assertThat(Json.MAPPER.readTree(f.body()).get("error").asText()).isEqualTo("connect_failed");
        assertThat(call("POST", base + "/fetch", "{\"node\":\"10.0.0.1\",\"paths\":[\"/etc/passwd\"]}"
                .getBytes(StandardCharsets.UTF_8)).statusCode()).isEqualTo(400);
        assertThat(call("POST", base + "/fetch", "{\"node\":\"10.0.0.1\",\"paths\":[\"/x/gc.log\"],\"maxMB\":0}"
                .getBytes(StandardCharsets.UTF_8)).statusCode()).isEqualTo(400);
        assertThat(call("GET", base + "/files", null).statusCode()).isEqualTo(400);
    }
}
