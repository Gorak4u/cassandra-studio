package com.cassandrastudio.engine.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.audit.AuditLog.Outcome;
import com.cassandrastudio.engine.secrets.SecretStores;
import com.cassandrastudio.engine.store.CrashLog;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StudioRoutesTest {
    private static final String TOKEN = "t";
    private Engine engine;
    private EngineServer server;
    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    @TempDir
    Path dataDir;

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

    private HttpResponse<String> call(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + TOKEN);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void uiStateRoundTrip() throws Exception {
        assertThat(call("GET", "/api/ui-state", null).body()).isEqualTo("{}");
        String state = "{\"open\":[\"c1\"],\"active\":\"c1\",\"workspaces\":{\"c1\":{\"tab\":\"monitoring\"}},\"sidebarWidth\":320}";
        assertThat(call("PUT", "/api/ui-state", state).statusCode()).isEqualTo(204);
        JsonNode back = Json.MAPPER.readTree(call("GET", "/api/ui-state", null).body());
        assertThat(back.get("workspaces").get("c1").get("tab").asText()).isEqualTo("monitoring");
        assertThat(back.get("sidebarWidth").asInt()).isEqualTo(320);
        assertThat(call("PUT", "/api/ui-state", "[1]").statusCode()).isEqualTo(400);
        assertThat(call("PUT", "/api/ui-state", "{\"x\":\"" + "a".repeat(300_000) + "\"}").statusCode()).isEqualTo(400);
    }

    @Test
    void backupAndRestoreOverHttp() throws Exception {
        String created = call("POST", "/api/connections", """
                {"connection": {"name": "core", "environment": "PROD", "contactPoints": ["127.0.0.1:1"]},
                 "secrets": {"password": "pw-123"}}""").body();
        String id = Json.MAPPER.readTree(created).get("id").asText();
        HttpResponse<String> plain = call("POST", "/api/studio/backup", "{}");
        assertThat(plain.statusCode()).isEqualTo(200);
        assertThat(plain.headers().firstValue("Content-Disposition").orElse("")).contains("cassandra-studio-settings-");
        assertThat(plain.body()).doesNotContain("pw-123").contains("\"core\"");
        assertThat(call("POST", "/api/studio/backup", "{\"includeSecrets\":true}").statusCode()).isEqualTo(400);
        String withSecrets = call("POST", "/api/studio/backup", "{\"includeSecrets\":true,\"passphrase\":\"long enough\"}").body();
        assertThat(withSecrets).doesNotContain("pw-123").contains("PBKDF2");

        JsonNode preview = Json.MAPPER.readTree(call("POST", "/api/studio/restore",
                "{\"dryRun\":true,\"file\":" + withSecrets + "}").body());
        assertThat(preview.get("connections").get("conflicting").asInt()).isEqualTo(1);
        assertThat(preview.get("hasSecrets").asBoolean()).isTrue();

        JsonNode r = Json.MAPPER.readTree(call("POST", "/api/studio/restore",
                "{\"conflict\":\"keep_both\",\"passphrase\":\"long enough\",\"file\":" + withSecrets + "}").body());
        assertThat(r.get("connections").asInt()).isEqualTo(1);
        assertThat(r.get("secrets").asInt()).isEqualTo(1);
        assertThat(engine.connections.list()).hasSize(2);
        assertThat(engine.connections.list().stream().filter(c -> !c.id().equals(id)).findFirst().orElseThrow()
                .secretsSet()).containsEntry("password", true);

        assertThat(call("POST", "/api/studio/restore", "{\"conflict\":\"nope\",\"file\":" + plain.body() + "}").statusCode()).isEqualTo(400);
        assertThat(call("POST", "/api/studio/restore", "{}").statusCode()).isEqualTo(400);
        assertThat(call("POST", "/api/studio/restore", "{\"file\":{\"format\":\"x\"}}").statusCode()).isEqualTo(400);
        assertThat(engine.audit.search(null, "restore settings", null, 10)).hasSize(1);
    }

    @Test
    void auditExportCsvAndJsonWithFilters() throws Exception {
        engine.audit.record(null, null, "cql", "INSERT INTO shop.orders", "x", Outcome.SUCCESS, null);
        engine.audit.record(null, "10.0.0.1", "ops", "flush", "nodetool flush", Outcome.FAILED, "boom");
        HttpResponse<String> csv = call("GET", "/api/audit/export?format=csv&q=flush", null);
        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.headers().firstValue("Content-Type").orElse("")).startsWith("text/csv");
        assertThat(csv.headers().firstValue("Content-Disposition").orElse("")).contains(".csv");
        assertThat(csv.body().split("\r\n")).hasSize(2);
        assertThat(csv.body()).contains("flush").doesNotContain("shop.orders");
        JsonNode json = Json.MAPPER.readTree(call("GET", "/api/audit/export?format=json", null).body());
        assertThat(json).hasSize(2);
        assertThat(call("GET", "/api/audit/export?format=xml", null).statusCode()).isEqualTo(400);
    }

    @Test
    void diagnosticsAndUiErrors() throws Exception {
        CrashLog log = CrashLog.install(dataDir);
        assertThat(call("POST", "/api/diagnostics/ui-error",
                "{\"message\":\"TypeError: boom password=abc\",\"stack\":\"at X (x.tsx:1)\"}").statusCode()).isEqualTo(204);
        JsonNode d = Json.MAPPER.readTree(call("GET", "/api/diagnostics", null).body());
        assertThat(d.get("studioVersion").asText()).isNotBlank();
        assertThat(d.get("java").asText()).isNotBlank();
        assertThat(d.get("os").asText()).isNotBlank();
        assertThat(d.get("dbVersion").asInt()).isEqualTo(Database.latestVersion());
        assertThat(d.get("recentErrors").toString()).contains("TypeError: boom").doesNotContain("abc");
        assertThat(Files.readString(log.file())).contains("[ui] TypeError: boom").contains("x.tsx:1");
        for (int i = 0; i < StudioRoutes.UI_ERRORS_PER_MINUTE + 10; i++) {
            call("POST", "/api/diagnostics/ui-error", "{\"message\":\"loop\"}");
        }
        assertThat(Files.readAllLines(log.file()).stream().filter(l -> l.contains("[ui] loop")).count())
                .isLessThanOrEqualTo(StudioRoutes.UI_ERRORS_PER_MINUTE);
    }
}
