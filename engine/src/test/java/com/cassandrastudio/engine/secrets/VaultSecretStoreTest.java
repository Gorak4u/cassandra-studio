package com.cassandrastudio.engine.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cassandrastudio.engine.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Against a small fake of Vault's KV v2 HTTP API. */
class VaultSecretStoreTest {
    private static final String TOKEN = "s.test-token";
    private HttpServer server;
    private final Map<String, String> kv = new ConcurrentHashMap<>();
    private final List<String> calls = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getRawPath();
        calls.add(ex.getRequestMethod() + " " + path + " ns=" + ex.getRequestHeaders().getFirst("X-Vault-Namespace"));
        if (!TOKEN.equals(ex.getRequestHeaders().getFirst("X-Vault-Token"))) {
            reply(ex, 403, "{\"errors\":[\"permission denied\"]}");
            return;
        }
        if (path.equals("/v1/auth/token/lookup-self")) {
            reply(ex, 200, "{\"data\":{\"id\":\"x\"}}");
            return;
        }
        String dataPrefix = "/v1/kv/data/studio/";
        String metaPrefix = "/v1/kv/metadata/studio/";
        switch (ex.getRequestMethod()) {
            case "POST" -> {
                JsonNode body = Json.MAPPER.readTree(ex.getRequestBody());
                kv.put(path.substring(dataPrefix.length()), body.path("data").path("value").asText());
                reply(ex, 200, "{\"data\":{\"version\":1}}");
            }
            case "GET" -> {
                String v = kv.get(path.substring(dataPrefix.length()));
                if (v == null) reply(ex, 404, "{\"errors\":[]}");
                else reply(ex, 200, Json.write(Map.of("data", Map.of("data", Map.of("value", v), "metadata", Map.of()))));
            }
            case "DELETE" -> {
                kv.remove(path.substring(metaPrefix.length()));
                ex.sendResponseHeaders(204, -1);
                ex.close();
            }
            default -> reply(ex, 405, "{}");
        }
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private String address() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @Test
    void readsWritesAndDeletesKvV2() {
        VaultSecretStore s = new VaultSecretStore(address(), TOKEN, "kv", "/studio/", "team-a", null);
        s.check();
        s.put("conn/abc/cqlPassword", "p@ss word");
        assertThat(kv).containsEntry("conn/abc/cqlPassword", "p@ss word");
        assertThat(s.get("conn/abc/cqlPassword")).contains("p@ss word");
        assertThat(s.get("conn/abc/sshPassphrase")).isEmpty();
        s.delete("conn/abc/cqlPassword");
        s.delete("conn/abc/cqlPassword"); // already gone: fine
        assertThat(kv).isEmpty();
        assertThat(calls).contains("POST /v1/kv/data/studio/conn/abc/cqlPassword ns=team-a",
                "DELETE /v1/kv/metadata/studio/conn/abc/cqlPassword ns=team-a");
        assertThat(s.description()).contains("Vault").contains("kv/studio");
    }

    @Test
    void namesAreEncodedPerSegment() {
        VaultSecretStore s = new VaultSecretStore(address(), TOKEN, "kv", "studio", null, null);
        s.put("conn/a b?c/x", "v");
        assertThat(calls).anyMatch(c -> c.startsWith("POST /v1/kv/data/studio/conn/a%20b%3Fc/x"));
        assertThat(s.get("conn/a b?c/x")).contains("v");
    }

    @Test
    void badTokenAndUnreachableVaultFailClearly() {
        assertThatThrownBy(() -> new VaultSecretStore(address(), "wrong", "kv", "studio", null, null).check())
                .hasMessageContaining("refused the token").hasMessageContaining("permission denied");
        assertThatThrownBy(() -> new VaultSecretStore(address(), "wrong", "kv", "studio", null, null).put("a", "b"))
                .hasMessageContaining("403");
        assertThatThrownBy(() -> new VaultSecretStore("http://127.0.0.1:1", TOKEN, null, null, null, null).get("a"))
                .hasMessageContaining("not reachable");
        assertThatThrownBy(() -> new VaultSecretStore(address(), " ", null, null, null, null)).hasMessageContaining("token");
    }
}
