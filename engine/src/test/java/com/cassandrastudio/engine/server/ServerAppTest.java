package com.cassandrastudio.engine.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cassandrastudio.engine.net.TestCerts;
import com.cassandrastudio.engine.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Studio Server in-process on SQLite with TLS: tokens, per-user requests, health, audit chain, CLI. */
class ServerAppTest {
    @TempDir
    static Path certDir;
    private static TestCerts certs;
    @TempDir
    Path dir;
    private ServerApp app;
    private HttpClient http;
    private Map<String, String> settings;

    @org.junit.jupiter.api.BeforeAll
    static void makeCerts() throws Exception {
        certs = new TestCerts(certDir);
    }

    @BeforeEach
    void start() throws Exception {
        settings = new HashMap<>(Map.of("server.host", "127.0.0.1", "server.port", "0", "server.dataDir", dir.resolve("data").toString(),
                "tls.keystore", certs.serverP12.toString(), "tls.keystorePassword", TestCerts.PASSWORD, "ha.leaseSeconds", "4"));
        app = ServerApp.start(ServerConfig.of(settings));
        http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).sslContext(clientContext(certs)).build();
    }

    @AfterEach
    void stop() {
        if (app != null) app.close();
    }

    private HttpResponse<String> call(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("https://localhost:" + app.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Trusts the test CA that signed the server certificate. */
    static javax.net.ssl.SSLContext clientContext(TestCerts certs) throws Exception {
        javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
        ctx.init(null, com.cassandrastudio.engine.net.CaTrust.load(certs.caPem.toString(), false).trustManagers(null), null);
        return ctx;
    }

    private static JsonNode json(HttpResponse<String> r) {
        return Json.read(r.body(), JsonNode.class);
    }

    @Test
    void namedTokensIdentifyTheUserOfEveryRequest() throws Exception {
        String alice = app.tokens.add("alice", null).secret();
        String bob = app.tokens.add("bob", null).secret();

        assertThat(call("GET", "/api/info", null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/api/info", null, "cst_wrong").statusCode()).isEqualTo(401);
        assertThat(json(call("GET", "/api/info", null, alice)).get("actor").asText()).isEqualTo("alice");
        assertThat(json(call("GET", "/api/info?x=1", null, bob)).get("actor").asText()).isEqualTo("bob");
        // ?token= for downloads; and a server ignores the Host header check (it sits behind proxies and DNS names)
        assertThat(json(call("GET", "/api/server/info?token=" + alice, null, null)).get("user").asText()).isEqualTo("alice");

        // an audited action records the user who did it
        String conn = json(call("POST", "/api/connections", "{\"connection\":{\"name\":\"srv-unit\",\"environment\":\"DEV\","
                + "\"contactPoints\":[\"127.0.0.1:1\"]},\"secrets\":{\"password\":\"pw\"}}", alice)).get("id").asText();
        String schedule = "{\"schedule\":{\"connectionId\":\"" + conn + "\",\"type\":\"audit-verify\",\"name\":\"check\","
                + "\"everyMinutes\":60,\"enabled\":true,\"params\":{}},\"confirmed\":true}";
        assertThat(call("PUT", "/api/schedules", schedule, bob).statusCode()).isEqualTo(200);
        JsonNode audit = json(call("GET", "/api/audit?limit=10", null, alice));
        assertThat(audit.get(0).get("actor").asText()).isEqualTo("bob");
        assertThat(audit.get(0).get("category").asText()).isEqualTo("schedule");

        // connection secrets live encrypted in the server's store
        assertThat(app.engine.connections.secret(conn, "password")).contains("pw");
        assertThat(app.db.query("SELECT value FROM server_secrets").toString()).doesNotContain("\"pw\"").contains("v1:");

        app.tokens.revoke("bob");
        assertThat(call("GET", "/api/info", null, bob).statusCode()).isEqualTo(401);
    }

    @Test
    void healthIsPublicAndReportsTheRole() throws Exception {
        HttpResponse<String> h = call("GET", "/api/health", null, null);
        assertThat(h.statusCode()).isEqualTo(200);
        assertThat(json(h).get("status").asText()).isEqualTo("UP");
        for (int i = 0; i < 50 && !app.leader.isLeader(); i++) Thread.sleep(100);
        assertThat(call("GET", "/api/health/active", null, null).statusCode()).isEqualTo(200);
        assertThat(json(call("GET", "/api/health", null, null)).get("role").asText()).isEqualTo("leader");
        String t = app.tokens.add("ops", null).secret();
        JsonNode status = json(call("GET", "/api/server/status", null, t));
        assertThat(status.get("leader").asBoolean()).isTrue();
        assertThat(status.get("instances")).hasSize(1);
        JsonNode info = json(call("GET", "/api/server/info", null, t));
        assertThat(info.get("mode").asText()).isEqualTo("server");
        assertThat(info.get("tls").asBoolean()).isTrue();
        assertThat(info.get("store").asText()).isEqualTo("sqlite");
    }

    @Test
    void auditVerifyReportsTheFirstBrokenLink() throws Exception {
        String t = app.tokens.add("auditor", null).secret();
        JsonNode ok = json(call("GET", "/api/audit/verify", null, t));
        assertThat(ok.get("ok").asBoolean()).isTrue();
        assertThat(ok.get("checked").asLong()).isGreaterThanOrEqualTo(1); // the server start entry
        app.db.update("UPDATE audit_log SET actor = 'someone else' WHERE id = 1");
        JsonNode broken = json(call("GET", "/api/audit/verify", null, t));
        assertThat(broken.get("ok").asBoolean()).isFalse();
        assertThat(broken.get("brokenAt").asLong()).isEqualTo(1);
    }

    @Test
    void cliManagesTokensInTheSameStore() throws Exception {
        app.close();
        app = null;
        Path conf = dir.resolve("server.conf");
        StringBuilder sb = new StringBuilder();
        settings.forEach((k, v) -> sb.append(k).append('=').append(v.replace("\\", "\\\\")).append('\n'));
        Files.writeString(conf, sb.toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream o = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream e = new PrintStream(err, true, StandardCharsets.UTF_8);
        assertThat(ServerMain.run(new String[] {"token", "add", "carol", "--label", "ci", "--config", conf.toString()}, o, e, Map.of())).isZero();
        String token = out.toString(StandardCharsets.UTF_8).strip();
        assertThat(token).startsWith(TokenStore.PREFIX);
        out.reset();
        assertThat(ServerMain.run(new String[] {"token", "list", "--config", conf.toString()}, o, e, Map.of())).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("carol").contains("ci").contains("active").doesNotContain(token);
        out.reset();
        assertThat(ServerMain.run(new String[] {"audit", "verify", "--config", conf.toString()}, o, e, Map.of())).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("\"ok\":true");

        app = ServerApp.start(ServerConfig.load(conf, Map.of()));
        http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).sslContext(clientContext(certs)).build();
        assertThat(json(call("GET", "/api/info", null, token)).get("actor").asText()).isEqualTo("carol");
        JsonNode audit = json(call("GET", "/api/audit?q=token", null, token));
        assertThat(audit.get(0).get("action").asText()).isEqualTo("token add");
        assertThat(audit.get(0).get("actor").asText()).startsWith("cli: ");

        assertThat(ServerMain.run(new String[] {"token", "revoke", "carol", "--config", conf.toString()}, o, e, Map.of())).isZero();
        Thread.sleep(TokenStore.CACHE_MS + 200);
        assertThat(call("GET", "/api/info", null, token).statusCode()).isEqualTo(401);
        assertThat(ServerMain.run(new String[] {"token", "revoke", "nobody", "--config", conf.toString()}, o, e, Map.of())).isEqualTo(1);
    }

    @Test
    void plainHttpOnAllInterfacesIsRefusedUnlessAsked() {
        app.close();
        app = null;
        Map<String, String> plain = Map.of("server.host", "0.0.0.0", "server.port", "0", "server.dataDir", dir.resolve("p").toString());
        assertThatThrownBy(() -> ServerApp.start(ServerConfig.of(plain))).hasMessageContaining("without TLS");
        Map<String, String> insecure = new HashMap<>(plain);
        insecure.put("server.insecureHttp", "true");
        app = ServerApp.start(ServerConfig.of(insecure));
        assertThat(app.tls()).isFalse();
    }
}
