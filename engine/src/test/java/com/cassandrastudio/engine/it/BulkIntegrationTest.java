package com.cassandrastudio.engine.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.bulk.BulkService;
import com.cassandrastudio.engine.guard.ActionGuard;
import com.cassandrastudio.engine.jobs.Job;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.Environment;
import com.cassandrastudio.engine.secrets.SecretStores;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.ApiException;
import com.cassandrastudio.engine.util.Json;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Bulk unload/load (BLK-1, BLK-2) against the running test-env (not Testcontainers): acme-core
 * 4.1 (127.0.0.1:19042), legacy-311 3.11 (127.0.0.1:29042) and secure-50 5.0 with TLS and login
 * (127.0.0.1:39042). Uses keyspace t6_bulk only. Each cluster is skipped when its port is down.
 * Round trip of every CQL type through CSV and gzipped JSON lines, rejects and max errors,
 * the guard, and throughput on 200k rows (printed; set STUDIO_IT_BULK_ROWS to change).
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BulkIntegrationTest {
    private static final String KS = "t6_bulk";
    private static final Path PEM = Path.of(env("STUDIO_IT_PEM", "/home/user/cassandra-studio/test-env/certs/node.pem"));
    private Engine engine;
    private BulkService bulk;
    @TempDir
    Path dir;

    record Target(String name, String host, int port, String dc, boolean secure) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Target> targets() {
        return Stream.of(new Target("acme-core-4.1", "127.0.0.1", 19042, "dc_east", false),
                new Target("legacy-311", "127.0.0.1", 29042, "dc1", false),
                new Target("secure-50", "127.0.0.1", 39042, null, true));
    }

    private static String env(String k, String dflt) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? dflt : v;
    }

    @BeforeAll
    void setUp() {
        engine = new Engine(Database.inMemory(), SecretStores.inMemory(), "it");
        bulk = new BulkService(engine);
    }

    @AfterAll
    void tearDown() {
        engine.close();
    }

    private static boolean up(Target t) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(t.host(), t.port()), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String connect(Target t) {
        assumeTrue(up(t), t + " is not running");
        if (t.secure()) assumeTrue(Files.exists(PEM), "no certificate " + PEM);
        ConnectionConfig.Tls tls = t.secure() ? new ConnectionConfig.Tls(true, PEM.toString(), "PEM", null, null, false) : null;
        ConnectionConfig cfg = new ConnectionConfig(null, null, "bulk-" + t.name(), Environment.DEV, null, false,
                List.of(t.host() + ":" + t.port()), t.dc(), t.secure() ? "cassandra" : null, tls, null, "LOCAL_ONE", 60_000, null,
                null, null, null, null, null);
        String id = engine.connections.save(cfg, t.secure() ? Map.of("password", "cassandra") : null).id();
        CqlSession s = engine.sessions.session(id);
        // Replicas in the local DC, so LOCAL_* consistency works on the multi-DC cluster too.
        String repl = t.dc() == null ? "{'class': 'SimpleStrategy', 'replication_factor': 1}"
                : "{'class': 'NetworkTopologyStrategy', '" + t.dc() + "': 1}";
        exec(s, "CREATE KEYSPACE IF NOT EXISTS " + KS + " WITH replication = " + repl);
        exec(s, "ALTER KEYSPACE " + KS + " WITH replication = " + repl);
        exec(s, "CREATE TYPE IF NOT EXISTS " + KS + ".address (street text, zip int, tags set<text>)");
        for (String table : List.of("all_types", "all_types_copy", "all_types_json")) {
            exec(s, "DROP TABLE IF EXISTS " + KS + "." + table);
            exec(s, "CREATE TABLE " + KS + "." + table + " (id int, ck text, t text, a ascii, bi bigint, si smallint, ti tinyint,"
                    + " vi varint, dec decimal, f float, d double, b boolean, u uuid, tu timeuuid, ts timestamp, dt date, tm time,"
                    + " ip inet, bl blob, du duration, l list<text>, s set<int>, m map<text, timestamp>, addr frozen<address>,"
                    + " tup tuple<int, text>, nested map<int, frozen<list<text>>>, PRIMARY KEY (id, ck))");
        }
        exec(s, "CREATE TABLE IF NOT EXISTS " + KS + ".hits (id int PRIMARY KEY, n counter)");
        return id;
    }

    private static void exec(CqlSession s, String cql) {
        s.execute(SimpleStatement.newInstance(cql).setTimeout(Duration.ofSeconds(60)));
    }

    private void fillAllTypes(CqlSession s, int rows) {
        PreparedStatement ins = s.prepare("INSERT INTO " + KS + ".all_types (id, ck, t, a, bi, si, ti, vi, dec, f, d, b, u, tu, ts, dt, tm,"
                + " ip, bl, du, l, s, m, addr, tup, nested) VALUES (?, ?, ?, 'asc', ?, ?, ?, ?, ?, ?, ?, ?, uuid(), now(),"
                + " ?, ?, ?, '10.1.2.3', ?, 1h30m, ?, ?, ?, {street: ?, zip: ?, tags: {'x', 'y'}}, (?, ?), {1: ['a', 'b']})");
        for (int i = 0; i < rows; i++) {
            if (i % 10 == 9) {
                // a row of nulls (only the key) and an empty string
                s.execute(SimpleStatement.newInstance("INSERT INTO " + KS + ".all_types (id, ck, t) VALUES (?, ?, '')", i, "k" + i));
                continue;
            }
            s.execute(ins.bind(i, "k" + i, "text " + i + ", with \"quotes\"\nand a line break", (long) i * 1_000_000_007L,
                    (short) i, (byte) (i % 100), new java.math.BigInteger("123456789012345678901234567890").add(java.math.BigInteger.valueOf(i)),
                    new java.math.BigDecimal("12345.678900").add(java.math.BigDecimal.valueOf(i)), i / 3f, i / 7.0, i % 2 == 0,
                    java.time.Instant.ofEpochMilli(1_700_000_000_123L + i), java.time.LocalDate.of(2024, 1, 1).plusDays(i),
                    java.time.LocalTime.ofNanoOfDay(i * 1_000_000_123L), java.nio.ByteBuffer.wrap(new byte[] {(byte) i, 0, (byte) 0xff}),
                    List.of("a" + i, "it's"), java.util.Set.of(i, i + 1), Map.of("when", java.time.Instant.ofEpochSecond(i)),
                    "Street " + i, 10_000 + i, i, "t" + i));
        }
    }

    private Job await(Job j) throws InterruptedException {
        return engine.jobs.await(j.id(), 600_000);
    }

    private static ObjectNode body(Object... kv) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) n.set((String) kv[i], Json.MAPPER.valueToTree(kv[i + 1]));
        return n;
    }

    private static Map<String, List<Object>> rows(CqlSession s, String table) {
        Map<String, List<Object>> out = new HashMap<>();
        for (Row r : s.execute(SimpleStatement.newInstance("SELECT * FROM " + KS + "." + table).setPageSize(1000))) {
            List<Object> v = new ArrayList<>();
            for (int i = 0; i < r.getColumnDefinitions().size(); i++) v.add(r.getObject(i));
            out.put(r.getInt("id") + "/" + r.getString("ck"), v);
        }
        return out;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    void roundTripsEveryTypeThroughCsvAndJson(Target t) throws Exception {
        String id = connect(t);
        CqlSession s = engine.sessions.session(id);
        fillAllTypes(s, 300);
        Map<String, List<Object>> original = rows(s, "all_types");
        assertThat(original).hasSize(300);

        Path csv = dir.resolve(t.name() + ".csv");
        Job u = await(bulk.unload(id, body("keyspace", KS, "table", "all_types", "path", csv.toString(), "concurrency", 4)));
        assertThat(u.state()).as(u.error()).isEqualTo(Job.State.SUCCEEDED);
        assertThat(((Map<?, ?>) u.result()).get("rowsWritten")).isEqualTo(300L);
        assertThatThrownBy(() -> bulk.unload(id, body("keyspace", KS, "table", "all_types", "path", csv.toString())))
                .isInstanceOf(ApiException.class).hasMessageContaining("already exists");

        ObjectNode load = body("keyspace", KS, "table", "all_types_copy", "path", csv.toString());
        assertThatThrownBy(() -> bulk.load(id, load, ActionGuard.Confirmation.NONE))
                .isInstanceOf(ApiException.class).satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(428));
        Job l = await(bulk.load(id, load, new ActionGuard.Confirmation(true, null)));
        assertThat(l.state()).as(l.error()).isEqualTo(Job.State.SUCCEEDED);
        assertThat(rows(s, "all_types_copy")).isEqualTo(original);

        Path json = dir.resolve(t.name() + ".jsonl.gz");
        u = await(bulk.unload(id, body("keyspace", KS, "table", "all_types", "path", json.toString(), "format", "json", "compression", "gzip")));
        assertThat(u.state()).as(u.error()).isEqualTo(Job.State.SUCCEEDED);
        l = await(bulk.load(id, body("keyspace", KS, "table", "all_types_json", "path", json.toString(), "batchSize", 1),
                new ActionGuard.Confirmation(true, null)));
        assertThat(l.state()).as(l.error()).isEqualTo(Job.State.SUCCEEDED);
        assertThat(rows(s, "all_types_json")).isEqualTo(original);

        // Query mode, max rows, a column subset.
        Path q = dir.resolve(t.name() + "-q.csv");
        u = await(bulk.unload(id, body("mode", "query", "keyspace", KS, "query", "SELECT id, ck, t FROM all_types WHERE id = 3",
                "path", q.toString())));
        assertThat(u.state()).as(u.error()).isEqualTo(Job.State.SUCCEEDED);
        assertThat(Files.readAllLines(q).get(0)).isEqualTo("id,ck,t");
        Path some = dir.resolve(t.name() + "-some.csv");
        u = await(bulk.unload(id, body("keyspace", KS, "table", "all_types", "columns", List.of("id", "ck"), "maxRows", 25, "path", some.toString())));
        assertThat(((Map<?, ?>) u.result()).get("rowsWritten")).isEqualTo(25L);
        assertThat(Files.readAllLines(some)).hasSize(26);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    void rejectsBadRowsAndStopsAfterMaxErrors(Target t) throws Exception {
        String id = connect(t);
        CqlSession s = engine.sessions.session(id);
        Path csv = dir.resolve(t.name() + "-bad.csv");
        Files.writeString(csv, "id,ck,bi,ts\n1,a,5,2024-01-01T00:00:00Z\nx,b,5,2024-01-01\n2,c,notanumber,2024-01-01\n3,,1,2024-01-01\n4,d,7,1700000000000\n");
        ObjectNode b = body("keyspace", KS, "table", "all_types_copy", "path", csv.toString(), "nullString", "");
        var preview = bulk.preview(id, b);
        assertThat(preview.get("fileColumns")).isEqualTo(List.of("id", "ck", "bi", "ts"));
        assertThat((List<?>) preview.get("sampleRows")).hasSize(5);

        Job dry = await(bulk.load(id, body("keyspace", KS, "table", "all_types_copy", "path", csv.toString(), "dryRun", true),
                ActionGuard.Confirmation.NONE));
        assertThat(dry.state()).as(dry.error()).isEqualTo(Job.State.SUCCEEDED);
        assertThat(((Map<?, ?>) dry.result()).get("rowsWritten")).isEqualTo(2L);
        assertThat(((Map<?, ?>) dry.result()).get("rejected")).isEqualTo(3L);
        Path rejected = dir.resolve(t.name() + "-bad.rejected.csv");
        assertThat(Files.readAllLines(rejected)).hasSize(4);
        assertThat(Files.readString(dir.resolve(t.name() + "-bad.rejected.log")))
                .contains("line 3: column id: 'x' is not a valid int").contains("line 5: primary key column ck is empty");

        Job stopped = await(bulk.load(id, body("keyspace", KS, "table", "all_types_copy", "path", csv.toString(), "maxErrors", 1,
                "concurrency", 1, "batchSize", 1), new ActionGuard.Confirmation(true, null)));
        assertThat(stopped.state()).isEqualTo(Job.State.FAILED);
        assertThat(stopped.error()).contains("max errors 1");

        Job full = await(bulk.load(id, body("keyspace", KS, "table", "all_types_copy", "path", csv.toString(), "maxErrors", 10),
                new ActionGuard.Confirmation(true, null)));
        assertThat(full.state()).as(full.error()).isEqualTo(Job.State.SUCCEEDED);
        assertThat(((Map<?, ?>) full.result()).get("rejected")).isEqualTo(3L);

        assertThatThrownBy(() -> bulk.load(id, body("keyspace", KS, "table", "hits", "path", csv.toString()), ActionGuard.Confirmation.NONE))
                .hasMessageContaining("counter table");
        assertThat(s.execute("SELECT bi FROM " + KS + ".all_types_copy WHERE id = 4 AND ck = 'd'").one()).isNotNull();
    }

    /** Throughput on a simple table; prints rows/s (the target is ~10k/s load, ~20k/s unload). */
    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    void throughput(Target t) throws Exception {
        String id = connect(t);
        CqlSession s = engine.sessions.session(id);
        int n = Integer.parseInt(env("STUDIO_IT_BULK_ROWS", "200000"));
        exec(s, "DROP TABLE IF EXISTS " + KS + ".simple");
        exec(s, "DROP TABLE IF EXISTS " + KS + ".simple_copy");
        exec(s, "CREATE TABLE " + KS + ".simple (id int PRIMARY KEY, name text, v double, ts timestamp)");
        exec(s, "CREATE TABLE " + KS + ".simple_copy (id int PRIMARY KEY, name text, v double, ts timestamp)");
        Path src = dir.resolve(t.name() + "-simple.csv");
        try (BufferedWriter w = Files.newBufferedWriter(src, StandardCharsets.UTF_8)) {
            w.write("id,name,v,ts\n");
            for (int i = 0; i < n; i++) w.write(i + ",name-" + i + "," + (i / 3.0) + ",2024-01-01T00:00:" + String.format("%02d", i % 60) + "Z\n");
        }
        Job load = await(bulk.load(id, body("keyspace", KS, "table", "simple", "path", src.toString(), "concurrency", 64),
                new ActionGuard.Confirmation(true, null)));
        assertThat(load.state()).as(load.error()).isEqualTo(Job.State.SUCCEEDED);
        Map<?, ?> lr = (Map<?, ?>) load.result();
        Path out = dir.resolve(t.name() + "-simple-out.csv");
        Job unload = await(bulk.unload(id, body("keyspace", KS, "table", "simple", "path", out.toString(), "concurrency", 16)));
        assertThat(unload.state()).as(unload.error()).isEqualTo(Job.State.SUCCEEDED);
        Map<?, ?> ur = (Map<?, ?>) unload.result();
        System.out.printf("BULK %s: load %d rows at %s rows/s (%s ms); unload %d rows at %s rows/s (%s ms, %s ranges)%n", t.name(),
                lr.get("rowsWritten"), lr.get("rowsPerSecond"), lr.get("elapsedMs"), ur.get("rowsWritten"), ur.get("rowsPerSecond"),
                ur.get("elapsedMs"), ur.get("rangesTotal"));
        assertThat(lr.get("rowsWritten")).isEqualTo((long) n);
        assertThat(ur.get("rowsWritten")).isEqualTo((long) n);
        Job copy = await(bulk.load(id, body("keyspace", KS, "table", "simple_copy", "path", out.toString(), "concurrency", 64),
                new ActionGuard.Confirmation(true, null)));
        assertThat(copy.state()).as(copy.error()).isEqualTo(Job.State.SUCCEEDED);
        assertThat(count(s, "simple_copy")).isEqualTo(n);
        Row a = s.execute("SELECT * FROM " + KS + ".simple WHERE id = 12345").one();
        Row b = s.execute("SELECT * FROM " + KS + ".simple_copy WHERE id = 12345").one();
        assertThat(b.getString("name")).isEqualTo(a.getString("name"));
        assertThat(b.getDouble("v")).isEqualTo(a.getDouble("v"));
        assertThat(b.getInstant("ts")).isEqualTo(a.getInstant("ts"));
    }

    private long count(CqlSession s, String table) throws Exception {
        // Count by token range: a plain COUNT(*) times out on the busy shared test-env. Each part is retried.
        long total = 0;
        long step = Long.MAX_VALUE / 32 * 2;
        for (long start = Long.MIN_VALUE; ; ) {
            long end = start > Long.MAX_VALUE - step ? Long.MAX_VALUE : start + step;
            String cql = "SELECT count(*) FROM " + KS + "." + table + " WHERE token(id) >= ? AND token(id) "
                    + (end == Long.MAX_VALUE ? "<=" : "<") + " ?";
            for (int attempt = 1; ; attempt++) {
                try {
                    total += s.execute(SimpleStatement.newInstance(cql, start, end).setTimeout(Duration.ofSeconds(60))).one().getLong(0);
                    break;
                } catch (RuntimeException e) {
                    if (attempt == 3) throw e;
                    Thread.sleep(1000);
                }
            }
            if (end == Long.MAX_VALUE) break;
            start = end;
        }
        return total;
    }
}
