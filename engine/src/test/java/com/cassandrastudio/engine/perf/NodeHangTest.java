package com.cassandrastudio.engine.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.api.EngineServer;
import com.cassandrastudio.engine.jmx.DefaultJmxAccess;
import com.cassandrastudio.engine.metrics.MonitoringModel.ClusterSnapshot;
import com.cassandrastudio.engine.metrics.ScaleHarness;
import com.cassandrastudio.engine.secrets.SecretStores;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.ApiException;
import com.cassandrastudio.engine.util.Json;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * NFR-RELI without stopping real nodes: a "node" that accepts TCP connections and never answers (a
 * hung process or a black-holed host) and a port that refuses. Every engine route the UI uses for a
 * cluster must answer within its own timeout, and the rest of the API must stay responsive meanwhile.
 */
class NodeHangTest {

    /** Accepts connections and never writes a byte; closing it drops them. */
    static final class Blackhole implements AutoCloseable {
        final ServerSocket server;
        final List<Socket> held = new CopyOnWriteArrayList<>();

        Blackhole() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread.ofVirtual().start(() -> {
                while (!server.isClosed()) {
                    try {
                        held.add(server.accept());
                    } catch (IOException e) {
                        return;
                    }
                }
            });
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (Socket s : held) s.close();
        }
    }

    static int closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    @Test
    void monitoringOverRealJmxToAHungNodeAnswersInTime() throws Exception {
        try (Blackhole hole = new Blackhole(); DefaultJmxAccess jmx = new DefaultJmxAccess();
             ScaleHarness h = new ScaleHarness(1, 1, jmx, hole.port())) {
            long t0 = System.nanoTime();
            long timeoutMs = h.start(10_000);
            long pollMs = ms(t0);
            ClusterSnapshot s = h.monitoring.snapshot(h.connectionId);
            assertThat(s.nodes()).singleElement().satisfies(n -> {
                assertThat(n.error()).isNotBlank();
                assertThat(n.address()).isEqualTo("127.0.0.1");
            });
            t0 = System.nanoTime();
            assertThatThrownBy(() -> h.monitoring.tables(h.connectionId, null))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(503));
            long tablesMs = ms(t0);
            t0 = System.nanoTime();
            h.monitoring.ring(h.connectionId, null); // falls back to the driver's (here: no) tokens
            long ringMs = ms(t0);
            System.out.printf("PERF hung JMX port (real JMX client): poll %d ms (budget %d), tables %d ms, ring %d ms; error: %s%n",
                    pollMs, timeoutMs, tablesMs, ringMs, s.nodes().get(0).error());
            assertThat(pollMs).isLessThan(timeoutMs + 2_000);
            assertThat(tablesMs).isLessThan(21_000);
            assertThat(ringMs).isLessThan(16_000);
        }
    }

    @Test
    void uiRoutesAnswerWithinTheirTimeoutsWhenTheClusterHangsOrRefuses() throws Exception {
        try (Blackhole hole = new Blackhole()) {
            Engine engine = new Engine(Database.inMemory(), SecretStores.inMemory(), "tester");
            EngineServer server = new EngineServer(engine, new EngineServer.Options("127.0.0.1", 0, "t", null, false));
            HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
            try {
                String hung = create(http, server, "hung", hole.port());
                String refused = create(http, server, "refused", closedPort());
                Map<String, CompletableFuture<long[]>> calls = new LinkedHashMap<>();
                for (String id : List.of(hung, refused)) {
                    String c = id.equals(hung) ? "hung" : "refused";
                    calls.put(c + " connect", timed(http, server, "POST", "/api/connections/" + id + "/connect"));
                    calls.put(c + " test", timed(http, server, "POST", "/api/connections/test",
                            "{\"connection\":" + Json.write(engine.connections.get(id)) + "}"));
                    calls.put(c + " info", timed(http, server, "GET", "/api/clusters/" + id + "/info"));
                    calls.put(c + " schema", timed(http, server, "GET", "/api/clusters/" + id + "/schema"));
                    CompletableFuture<long[]> start = timed(http, server, "POST", "/api/clusters/" + id + "/monitoring/start", "{}");
                    calls.put(c + " monitoring start", start);
                    calls.put(c + " monitoring snapshot", start.thenCompose(x ->
                            timed(http, server, "GET", "/api/clusters/" + id + "/monitoring/snapshot")));
                    calls.put(c + " monitoring status", start.thenCompose(x ->
                            timed(http, server, "GET", "/api/clusters/" + id + "/monitoring/status")));
                    calls.put(c + " ops view", timed(http, server, "GET", "/api/clusters/" + id + "/ops/views/info"));
                    calls.put(c + " snapshots", timed(http, server, "GET", "/api/clusters/" + id + "/ops/snapshots"));
                }
                // meanwhile the rest of the UI keeps working
                Thread.sleep(500);
                long t0 = System.nanoTime();
                HttpResponse<String> list = send(http, server, "GET", "/api/connections", null);
                long listMs = ms(t0);
                assertThat(list.statusCode()).isEqualTo(200);
                assertThat(listMs).isLessThan(1_000);

                StringBuilder report = new StringBuilder("PERF unreachable cluster routes (ms/status):");
                long worst = 0;
                for (var e : calls.entrySet()) {
                    long[] r = e.getValue().get();
                    worst = Math.max(worst, r[0]);
                    report.append(' ').append(e.getKey()).append('=').append(r[0]).append('/').append(r[1]).append(';');
                }
                System.out.println(report + " other API during it " + listMs + " ms");
                for (var e : calls.entrySet()) {
                    long[] r = e.getValue().get();
                    assertThat(r[1]).as(e.getKey()).isNotEqualTo(-1L); // answered, not cut off by the client
                    assertThat(r[1]).as(e.getKey() + " status").isBetween(200L, 599L);
                }
                // the driver gives up within its connect/init timeouts (10 s each); nothing waits forever
                assertThat(worst).isLessThan(45_000);
            } finally {
                server.close();
                engine.close();
            }
        }
    }

    private static String create(HttpClient http, EngineServer server, String name, int port) throws Exception {
        String body = "{\"connection\":{\"name\":\"" + name + "\",\"environment\":\"PROD\",\"contactPoints\":[\"127.0.0.1:" + port
                + "\"],\"localDatacenter\":\"dc1\",\"jmx\":{\"method\":\"DIRECT\",\"port\":" + port + "}}}";
        return Json.MAPPER.readTree(send(http, server, "POST", "/api/connections", body).body()).get("id").asText();
    }

    private static CompletableFuture<long[]> timed(HttpClient http, EngineServer server, String method, String path) {
        return timed(http, server, method, path, null);
    }

    private static CompletableFuture<long[]> timed(HttpClient http, EngineServer server, String method, String path, String body) {
        long t0 = System.nanoTime();
        return http.sendAsync(request(server, method, path, body), HttpResponse.BodyHandlers.ofString())
                .handle((r, e) -> new long[] {ms(t0), e == null ? r.statusCode() : -1});
    }

    private static HttpResponse<String> send(HttpClient http, EngineServer server, String method, String path, String body)
            throws Exception {
        return http.send(request(server, method, path, body), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest request(EngineServer server, String method, String path, String body) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(90))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json").header("Authorization", "Bearer t").build();
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
