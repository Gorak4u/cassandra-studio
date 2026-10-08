package com.cassandrastudio.engine.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.secrets.SecretStores;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EngineServerTest {
    private static final String TOKEN = "test-token";
    private Engine engine;
    private EngineServer server;
    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    @BeforeEach
    void start() {
        engine = new Engine(Database.inMemory(), SecretStores.inMemory(), "tester");
        server = new EngineServer(engine, new EngineServer.Options("127.0.0.1", 0, TOKEN, null, false));
    }

    @AfterEach
    void stop() {
        server.close();
        engine.close();
    }

    private HttpResponse<String> call(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void rejectsMissingOrWrongToken() throws Exception {
        assertThat(call("GET", "/api/info", null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/api/info", null, "nope").statusCode()).isEqualTo(401);
        HttpResponse<String> ok = call("GET", "/api/info", null, TOKEN);
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(Json.MAPPER.readTree(ok.body()).get("actor").asText()).isEqualTo("tester");
        assertThat(ok.headers().firstValue("Cache-Control")).contains("no-store");
    }

    @Test
    void rejectsForeignHostHeader() throws Exception {
        try (Socket s = new Socket("127.0.0.1", server.port())) {
            OutputStream out = s.getOutputStream();
            out.write(("GET /api/info HTTP/1.1\r\nHost: evil.example.com\r\nAuthorization: Bearer " + TOKEN
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String status = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            assertThat(status).contains("403");
        }
    }

    @Test
    void connectionLifecycleOverHttp() throws Exception {
        String body = """
                {"connection": {"name": "core-dev", "environment": "DEV", "contactPoints": ["127.0.0.1:1"]},
                 "secrets": {"password": "pw"}}""";
        HttpResponse<String> created = call("POST", "/api/connections", body, TOKEN);
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode c = Json.MAPPER.readTree(created.body());
        String id = c.get("id").asText();
        assertThat(c.get("secretsSet").get("password").asBoolean()).isTrue();
        assertThat(created.body()).doesNotContain("\"pw\"");

        assertThat(Json.MAPPER.readTree(call("GET", "/api/connections", null, TOKEN).body())).hasSize(1);
        String export = call("GET", "/api/connections/export", null, TOKEN).body();
        assertThat(export).contains("core-dev").doesNotContain("\"pw\"");

        // Nothing listens on port 1: a clean error, not a hang or a 500.
        HttpResponse<String> connect = call("POST", "/api/connections/" + id + "/connect", null, TOKEN);
        assertThat(connect.statusCode()).isEqualTo(502);
        assertThat(Json.MAPPER.readTree(connect.body()).get("error").asText()).isEqualTo("connect_failed");

        assertThat(call("DELETE", "/api/connections/" + id, null, TOKEN).statusCode()).isEqualTo(204);
        assertThat(call("GET", "/api/connections/" + id, null, TOKEN).statusCode()).isEqualTo(404);
    }

    @Test
    void ddlPreview() throws Exception {
        HttpResponse<String> r = call("POST", "/api/ddl/dropTable", "{\"keyspace\":\"shop\",\"table\":\"Orders\"}", TOKEN);
        assertThat(Json.MAPPER.readTree(r.body()).get("cql").asText()).isEqualTo("DROP TABLE shop.\"Orders\";");
        assertThat(call("POST", "/api/ddl/explode", "{}", TOKEN).statusCode()).isEqualTo(404);
    }

    @Test
    void badJsonIsA400() throws Exception {
        assertThat(call("POST", "/api/folders", "{nope", TOKEN).statusCode()).isEqualTo(400);
    }
}
