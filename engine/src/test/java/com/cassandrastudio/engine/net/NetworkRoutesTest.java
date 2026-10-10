package com.cassandrastudio.engine.net;

import static org.assertj.core.api.Assertions.assertThat;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.api.EngineServer;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.ProxyType;
import com.cassandrastudio.engine.secrets.SecretStore;
import com.cassandrastudio.engine.secrets.SecretStores;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The settings API end to end: defaults, save (password to the secret store), validation, offline update check. */
class NetworkRoutesTest {
    private static final String TOKEN = "t";
    private Engine engine;
    private EngineServer server;
    private SecretStore secrets;
    private Database db;
    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    @BeforeEach
    void start() {
        db = Database.inMemory();
        secrets = SecretStores.inMemory();
        engine = new Engine(db, secrets, "tester");
        server = new EngineServer(engine, new EngineServer.Options("127.0.0.1", 0, TOKEN, null, false));
    }

    @AfterEach
    void stop() {
        server.close();
        engine.close();
        Net.reset();
    }

    private HttpResponse<String> call(String method, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Authorization", "Bearer " + TOKEN).header("Content-Type", "application/json").build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void defaultsSaveAndReload() throws Exception {
        JsonNode d = Json.MAPPER.readTree(call("GET", "/api/settings/network", null).body());
        assertThat(d.at("/settings/checkForUpdates").asBoolean()).isTrue();
        assertThat(d.at("/settings/offline").asBoolean()).isFalse();
        assertThat(d.at("/settings/proxyMode").asText()).isEqualTo("SYSTEM");
        assertThat(d.get("proxyPasswordSet").asBoolean()).isFalse();

        HttpResponse<String> saved = call("PUT", "/api/settings/network", """
                {"proxyMode":"MANUAL","proxyHost":"proxy.corp","proxyPort":8080,"proxyUsername":"alice",
                 "proxyPassword":"s3cret","noProxy":["*.internal","10.0.0.0/8"],"checkForUpdates":false}""");
        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(saved.body()).doesNotContain("s3cret");
        JsonNode s = Json.MAPPER.readTree(saved.body());
        assertThat(s.get("proxyPasswordSet").asBoolean()).isTrue();
        assertThat(secrets.get(NetworkService.PROXY_PASSWORD_KEY)).contains("s3cret");
        assertThat(Net.settings().proxyHost()).isEqualTo("proxy.corp");
        assertThat(Net.resolver().forUri(URI.create("https://api.github.com/")).orElseThrow().password()).isEqualTo("s3cret");
        assertThat(db.query("SELECT value FROM settings WHERE key='net.settings'").get(0).get("value").toString())
                .doesNotContain("s3cret");

        // a new engine on the same database applies the stored settings at start
        Net.reset();
        new NetworkService(db, secrets, new UpdateChecker("1.0.0")).start();
        assertThat(Net.settings().proxyMode()).isEqualTo(NetworkSettings.ProxyMode.MANUAL);
        assertThat(Net.settings().noProxy()).containsExactly("*.internal", "10.0.0.0/8");

        // password unchanged when omitted, removed with ""
        call("PUT", "/api/settings/network", "{\"proxyMode\":\"MANUAL\",\"proxyHost\":\"proxy.corp\",\"proxyPort\":3128}");
        assertThat(secrets.get(NetworkService.PROXY_PASSWORD_KEY)).contains("s3cret");
        call("PUT", "/api/settings/network", "{\"proxyMode\":\"NONE\",\"proxyPassword\":\"\"}");
        assertThat(secrets.get(NetworkService.PROXY_PASSWORD_KEY)).isEmpty();
    }

    @Test
    void validationErrors(@TempDir Path tmp) throws Exception {
        HttpResponse<String> r = call("PUT", "/api/settings/network", "{\"proxyMode\":\"MANUAL\",\"proxyPort\":99999}");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).contains("Proxy host is required").contains("Proxy port");
        HttpResponse<String> ca = call("PUT", "/api/settings/network",
                "{\"caBundlePath\":" + Json.write(tmp.resolve("missing.pem").toString()) + "}");
        assertThat(ca.statusCode()).isEqualTo(400);
        assertThat(ca.body()).contains("Cannot read CA bundle");
        assertThat(call("PUT", "/api/settings/network", "{\"proxyMode\":\"WRONG\"}").statusCode()).isEqualTo(400);
        assertThat(Net.settings()).isEqualTo(NetworkSettings.DEFAULTS);
    }

    @Test
    void offlineModeTurnsTheUpdateCheckOff() throws Exception {
        call("PUT", "/api/settings/network", "{\"offline\":true}");
        JsonNode u = Json.MAPPER.readTree(call("GET", "/api/updates?force=true", null).body());
        assertThat(u.get("state").asText()).isEqualTo("offline");
        call("PUT", "/api/settings/network", "{\"checkForUpdates\":false}");
        assertThat(Json.MAPPER.readTree(call("GET", "/api/updates", null).body()).get("state").asText()).isEqualTo("disabled");
    }

    @Test
    void savedConnectionsWithoutAProxyStillLoadAndNewOnesRoundTrip() {
        String old = """
                {"id":"c1","name":"old","contactPoints":["10.0.0.1"],"ssh":{"username":"studio","port":2222,"auth":"KEY",
                 "keyPath":"~/.ssh/id","jumpHost":null,"jumpPort":22,"strictHostKeyChecking":true}}""";
        ConnectionConfig c = Json.read(old, ConnectionConfig.class);
        assertThat(c.ssh().port()).isEqualTo(2222);
        assertThat(c.ssh().proxy()).isNull();

        String withProxy = old.replace("\"strictHostKeyChecking\":true", "\"strictHostKeyChecking\":true,"
                + "\"proxy\":{\"type\":\"SOCKS5\",\"host\":\"socks.corp\"}");
        ConnectionConfig p = Json.read(withProxy, ConnectionConfig.class);
        assertThat(p.ssh().proxy().type()).isEqualTo(ProxyType.SOCKS5);
        assertThat(p.ssh().proxy().port()).isEqualTo(1080);
        assertThat(Json.read(Json.write(p), ConnectionConfig.class)).isEqualTo(p);

        String blankProxy = old.replace("\"strictHostKeyChecking\":true", "\"strictHostKeyChecking\":true,"
                + "\"proxy\":{\"type\":\"HTTP\",\"host\":\"\"}");
        assertThat(Json.read(blankProxy, ConnectionConfig.class).ssh().proxy()).isNull();
        assertThat(ConnectionConfig.SecretKeys.ALL).contains("sshProxyPassword");
    }
}
