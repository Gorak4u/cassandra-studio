package com.cassandrastudio.engine.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.bulk.BulkService;
import com.cassandrastudio.engine.guard.ActionGuard;
import com.cassandrastudio.engine.jobs.Job;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.Environment;
import com.cassandrastudio.engine.secrets.SecretStores;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.Json;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * NFR-PERF bulk throughput on the test-env's acme-core 4.1 (keyspace t3h_perf): load and unload of
 * 200k rows (STUDIO_IT_BULK_ROWS), printed as {@code PERF bulk ...} for docs/perf.md. Load is
 * measured at the default concurrency and at 64.
 */
@Tag("integration")
class BulkThroughputIntegrationTest {
    private static final String KS = "t3h_perf";
    @TempDir
    Path dir;

    @Test
    void loadAndUnload200kRowsOnCassandra41() throws Exception {
        assumeTrue(up("127.0.0.1", 19042), "acme-core 4.1 is not running");
        int n = Integer.parseInt(System.getenv().getOrDefault("STUDIO_IT_BULK_ROWS", "200000"));
        try (Engine engine = new Engine(Database.inMemory(), SecretStores.inMemory(), "perf")) {
            BulkService bulk = new BulkService(engine);
            String id = engine.connections.save(new ConnectionConfig(null, null, "perf-41", Environment.DEV, null, false,
                    List.of("127.0.0.1:19042"), "dc_east", null, null, null, "LOCAL_ONE", 60_000, null, null, null, null, null, null),
                    null).id();
            CqlSession s = engine.sessions.session(id);
            exec(s, "CREATE KEYSPACE IF NOT EXISTS " + KS + " WITH replication = {'class': 'NetworkTopologyStrategy', 'dc_east': 1}");
            Path src = dir.resolve("simple.csv");
            try (BufferedWriter w = Files.newBufferedWriter(src, StandardCharsets.UTF_8)) {
                w.write("id,name,v,ts\n");
                for (int i = 0; i < n; i++) {
                    w.write(i + ",name-" + i + "," + (i / 3.0) + ",2024-01-01T00:00:" + String.format("%02d", i % 60) + "Z\n");
                }
            }
            for (Integer concurrency : new Integer[] {null, 64}) {
                exec(s, "DROP TABLE IF EXISTS " + KS + ".simple");
                exec(s, "CREATE TABLE " + KS + ".simple (id int PRIMARY KEY, name text, v double, ts timestamp)");
                ObjectNode body = body("keyspace", KS, "table", "simple", "path", src.toString());
                if (concurrency != null) body.put("concurrency", concurrency);
                Job load = engine.jobs.await(bulk.load(id, body, new ActionGuard.Confirmation(true, null)).id(), 600_000);
                assertThat(load.state()).as(load.error()).isEqualTo(Job.State.SUCCEEDED);
                Map<?, ?> r = (Map<?, ?>) load.result();
                System.out.printf("PERF bulk load 4.1 (concurrency %s): %s rows at %s rows/s in %s ms%n",
                        concurrency == null ? "default" : concurrency, r.get("rowsWritten"), r.get("rowsPerSecond"), r.get("elapsedMs"));
                assertThat(r.get("rowsWritten")).isEqualTo((long) n);
            }
            Path out = dir.resolve("out.csv");
            Job unload = engine.jobs.await(bulk.unload(id, body("keyspace", KS, "table", "simple", "path", out.toString())).id(), 600_000);
            assertThat(unload.state()).as(unload.error()).isEqualTo(Job.State.SUCCEEDED);
            Map<?, ?> u = (Map<?, ?>) unload.result();
            System.out.printf("PERF bulk unload 4.1: %s rows at %s rows/s in %s ms%n", u.get("rowsWritten"), u.get("rowsPerSecond"),
                    u.get("elapsedMs"));
            exec(s, "DROP TABLE IF EXISTS " + KS + ".simple");
        }
    }

    private static void exec(CqlSession s, String cql) {
        s.execute(SimpleStatement.newInstance(cql).setTimeout(Duration.ofSeconds(60)));
    }

    private static ObjectNode body(Object... kv) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) n.set((String) kv[i], Json.MAPPER.valueToTree(kv[i + 1]));
        return n;
    }

    private static boolean up(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
