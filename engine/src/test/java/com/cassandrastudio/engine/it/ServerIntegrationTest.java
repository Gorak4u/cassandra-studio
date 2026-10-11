package com.cassandrastudio.engine.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.audit.AuditChain;
import com.cassandrastudio.engine.audit.AuditLog;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.sched.Schedule;
import com.cassandrastudio.engine.secrets.DbSecretStore;
import com.cassandrastudio.engine.server.ServerConfig;
import com.cassandrastudio.engine.server.TokenStore;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * Studio Server on PostgreSQL (test-env profile "server": 127.0.0.1:15432) - SRV-1, SRV-6, SRV-7:
 * the store runs every migration and statement on PostgreSQL; two server processes (ports 18811 and
 * 18821) share it, one leads, and when the leader is killed the other takes over and fires the due
 * schedule exactly once; requests run as the token's user against acme-core (keyspace srv_it); the
 * audit chain holds across both instances and reaches a syslog/JSON listener. Each run uses its own
 * srv_it_* database, dropped at the end. Skipped when PostgreSQL is not running.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServerIntegrationTest {
    private static final String PG = "jdbc:postgresql://127.0.0.1:15432/";
    private static final String USER = "studio";
    private static final String PASSWORD = "studio-test";
    private final String dbName = "srv_it_" + UUID.randomUUID().toString().substring(0, 8);
    private final List<String> dropAtEnd = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    @TempDir
    Path tmp;

    private static boolean listening(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @BeforeAll
    void setUp() throws Exception {
        assumeTrue(listening("127.0.0.1", 15432), "test-env profile \"server\" (PostgreSQL on 15432) is not running");
        createDatabase(dbName);
    }

    @AfterAll
    void tearDown() throws Exception {
        if (!listening("127.0.0.1", 15432)) return;
        try (Connection c = DriverManager.getConnection(PG + "studio", USER, PASSWORD); Statement st = c.createStatement()) {
            for (String d : dropAtEnd) st.execute("DROP DATABASE IF EXISTS " + d + " WITH (FORCE)");
        }
    }

    private void createDatabase(String name) throws Exception {
        try (Connection c = DriverManager.getConnection(PG + "studio", USER, PASSWORD); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        }
        dropAtEnd.add(name);
    }

    private static Database open(String name) {
        return Database.connect(PG + name, USER, PASSWORD);
    }

    // ---- the store on PostgreSQL (SRV-7) ---------------------------------------------

    @Test
    void everyMigrationAndStatementRunsOnPostgres() throws Exception {
        String name = dbName + "_store";
        createDatabase(name);
        // two instances starting at once: one migrates, the other waits and finds it done
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        List<Database> dbs = new CopyOnWriteArrayList<>();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> starters = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            starters.add(Thread.ofVirtual().start(() -> {
                try {
                    go.await();
                    dbs.add(open(name));
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }
        go.countDown();
        for (Thread t : starters) t.join();
        assertThat(errors).isEmpty();
        Database db = dbs.get(0);
        Database other = dbs.get(1);
        try {
            assertThat(db.dialect()).isEqualTo(Database.Dialect.POSTGRESQL);
            assertThat(db.schemaVersion()).isEqualTo(Database.latestVersion());
            assertThat(((Number) db.query("SELECT COUNT(*) AS n FROM schema_version").get(0).get("n")).intValue())
                    .isEqualTo(Database.latestVersion());

            Engine engine = new Engine(db, new DbSecretStore(db, DbSecretStore.decodeKey(DbSecretStore.newKey())), "it");
            // connections and secrets
            ConnectionConfig saved = engine.connections.save(new ConnectionConfig(null, null, "srv-pg", ConnectionConfig.Environment.DEV,
                    null, false, List.of("127.0.0.1:19042"), "dc_east", null, null, null, null, null, null, null, null, List.of(), null,
                    null), Map.of("password", "pw"));
            assertThat(engine.connections.list()).extracting(ConnectionConfig::name).containsExactly("srv-pg");
            assertThat(engine.connections.secret(saved.id(), "password")).contains("pw");
            // INSERT OR REPLACE (used by several features) becomes an upsert
            db.update("INSERT OR REPLACE INTO settings(key, value) VALUES (?, ?)", "srv.k", "1");
            db.update("INSERT OR REPLACE INTO settings(key, value) VALUES (?, ?)", "srv.k", "2");
            assertThat(db.query("SELECT value FROM settings WHERE key = ?", "srv.k")).singleElement()
                    .satisfies(r -> assertThat(r.get("value")).isEqualTo("2"));
            // schedules: upsert, 64-bit epoch milliseconds
            engine.schedules.register("noop", new com.cassandrastudio.engine.sched.ScheduledTask() {
                public com.cassandrastudio.engine.jobs.JobService.Spec spec(Schedule s) {
                    return new com.cassandrastudio.engine.jobs.JobService.Spec(null, "noop", "noop", null, null, false);
                }

                public com.cassandrastudio.engine.jobs.JobTask task(Schedule s) {
                    return ctx -> null;
                }
            });
            Schedule s = engine.schedules.save(new Schedule(null, saved.id(), "noop", "srv nightly", 1440, "02:00", null, null, true,
                    Map.of("keyspace", "srv_it"), null, null, null, null));
            engine.schedules.save(new Schedule(s.id(), saved.id(), "noop", "srv nightly 2", 1440, "02:00", null, null, true,
                    Map.of(), null, null, null, null));
            assertThat(engine.schedules.list(null)).singleElement().satisfies(x -> {
                assertThat(x.name()).isEqualTo("srv nightly 2");
                assertThat(x.nextRunMs()).isGreaterThan(System.currentTimeMillis());
            });
            // audit: chain, case-insensitive search as on SQLite, appends from two instances interleaved
            AuditLog second = new AuditLog(other, "it-2");
            List<Thread> writers = new ArrayList<>();
            for (AuditLog a : List.of(engine.audit, second)) {
                writers.add(Thread.ofVirtual().start(() -> {
                    for (int i = 0; i < 25; i++) a.record(saved, null, "test", "Srv Action " + i, "detail", AuditLog.Outcome.SUCCESS, null);
                }));
            }
            for (Thread t : writers) t.join();
            assertThat(engine.audit.search(null, "srv action", null, 100)).hasSize(50);
            AuditChain.Verification v = AuditChain.verify(db);
            assertThat(v.ok()).as(v.toString()).isTrue();
            assertThat(v.checked()).isEqualTo(50);
            // query history (AUTOINCREMENT ids) and LIKE
            db.update("INSERT INTO query_history(connection_id, executed_at, statement) VALUES (?,?,?)", saved.id(), "now", "SELECT 1");
            assertThat(engine.queries.history(saved.id(), "select", 10)).hasSize(1);

            // a lost connection (database restart, failover) is reopened once
            try (Connection admin = DriverManager.getConnection(PG + "studio", USER, PASSWORD); Statement st = admin.createStatement()) {
                st.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + name + "' AND pid <> pg_backend_pid()");
            }
            assertThat(db.ping()).isTrue();
            assertThat(engine.connections.list()).hasSize(1);

            // a store written by a newer Studio is refused
            db.update("INSERT INTO schema_version(version) VALUES (?)", Database.latestVersion() + 1);
            assertThatThrownBy(() -> open(name)).isInstanceOf(Database.NewerDatabaseException.class).hasMessageContaining("newer Studio");
            db.update("DELETE FROM schema_version WHERE version = ?", Database.latestVersion() + 1);
            engine.close();
        } finally {
            other.close();
            db.close();
        }
    }

    // ---- two server processes on one store (SRV-1, SRV-6, SRV-7) ----------------------

    private record Instance(String id, int port, Process process, Path log) {}

    private Instance launch(String id, int port, Path conf) throws Exception {
        Path log = tmp.resolve(id + ".log");
        ProcessBuilder pb = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), "-Dcom.sun.jndi.rmiURLParsing=legacy",
                "com.cassandrastudio.engine.server.ServerMain", "--config", conf.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        Process p = pb.start();
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(log) && Files.readString(log).contains("STUDIO_SERVER_READY")) return new Instance(id, port, p, log);
            if (!p.isAlive()) break;
            Thread.sleep(200);
        }
        throw new IllegalStateException("Server " + id + " did not start:\n" + (Files.exists(log) ? Files.readString(log) : ""));
    }

    private HttpResponse<String> call(int port, String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json").timeout(java.time.Duration.ofSeconds(60));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String role(Instance i) {
        try {
            return Json.read(call(i.port(), "GET", "/api/health", null, null).body(), JsonNode.class).get("role").asText();
        } catch (Exception e) {
            return "down";
        }
    }

    @Test
    void twoInstancesShareTheStoreAndTheSurvivorFiresAfterTheLeaderIsKilled() throws Exception {
        assumeTrue(!listening("127.0.0.1", 18811) && !listening("127.0.0.1", 18821), "ports 18811/18821 are in use");
        // SIEM listeners: syslog over TCP (octet counting) and JSON lines over TCP
        List<String> syslog = new CopyOnWriteArrayList<>();
        List<String> jsonl = new CopyOnWriteArrayList<>();
        try (ServerSocket sys = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
             ServerSocket js = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> acceptAll(sys, true, syslog));
            Thread.ofVirtual().start(() -> acceptAll(js, false, jsonl));

            String key = DbSecretStore.newKey();
            Path confA = conf("it-a", 18811, key, sys.getLocalPort(), js.getLocalPort());
            Path confB = conf("it-b", 18821, key, sys.getLocalPort(), js.getLocalPort());

            // the CLI against PostgreSQL
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            java.io.PrintStream ps = new java.io.PrintStream(out, true, StandardCharsets.UTF_8);
            assertThat(com.cassandrastudio.engine.server.ServerMain.run(new String[] {"token", "add", "alice", "--config", confA.toString()},
                    ps, ps, Map.of())).isZero();
            String alice = out.toString(StandardCharsets.UTF_8).lines().filter(l -> l.startsWith(TokenStore.PREFIX)).findFirst().orElseThrow();

            Instance a = launch("it-a", 18811, confA);
            Instance b = null;
            try {
                // a starts first and leads; b joins as standby
                waitFor(() -> role(a).equals("leader"), 30_000, "it-a to lead");
                b = launch("it-b", 18821, confB);
                Instance bb = b;
                assertThat(role(b)).isEqualTo("standby");
                assertThat(call(18821, "GET", "/api/health/active", null, null).statusCode()).isEqualTo(503);
                assertThat(call(18811, "GET", "/api/health/active", null, null).statusCode()).isEqualTo(200);
                JsonNode status = Json.read(call(18821, "GET", "/api/server/status", null, alice).body(), JsonNode.class);
                assertThat(status.get("lease").get("holder").asText()).isEqualTo("it-a");
                assertThat(status.get("instances")).hasSize(2);

                // SRV-1: cluster access through the server, as alice (catalogue shared by both instances)
                assumeTrue(listening("127.0.0.1", 19042), "acme-core is not running");
                String conn = Json.read(call(18811, "POST", "/api/connections", "{\"connection\":{\"name\":\"srv-acme\",\"environment\":\"DEV\","
                        + "\"contactPoints\":[\"127.0.0.1:19042\"],\"localDatacenter\":\"dc_east\"}}", alice).body(), JsonNode.class)
                        .get("id").asText();
                assertThat(Json.read(call(18821, "GET", "/api/connections", null, alice).body(), JsonNode.class))
                        .anySatisfy(c -> assertThat(c.get("id").asText()).isEqualTo(conn));
                for (String cql : List.of(
                        "CREATE KEYSPACE IF NOT EXISTS srv_it WITH replication = {'class': 'NetworkTopologyStrategy', 'dc_east': 1}",
                        "CREATE TABLE IF NOT EXISTS srv_it.events (id text PRIMARY KEY, who text)",
                        "INSERT INTO srv_it.events (id, who) VALUES ('" + dbName + "', 'alice')")) {
                    HttpResponse<String> r = call(18821, "POST", "/api/clusters/" + conn + "/query",
                            Json.write(Map.of("cql", cql, "confirmed", true)), alice);
                    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
                    assertThat(r.body()).as(cql).doesNotContain("\"status\":\"error\"");
                }
                JsonNode rows = Json.read(call(18811, "POST", "/api/clusters/" + conn + "/query",
                        Json.write(Map.of("cql", "SELECT who FROM srv_it.events WHERE id = '" + dbName + "'")), alice).body(), JsonNode.class);
                assertThat(rows.toString()).contains("alice");
                JsonNode audit = Json.read(call(18811, "GET", "/api/audit?q=srv_it.events&limit=10", null, alice).body(), JsonNode.class);
                assertThat(audit).isNotEmpty().allSatisfy(e -> assertThat(e.get("actor").asText()).isEqualTo("alice"));

                // a schedule, created through the standby; due while the leader is killed
                JsonNode sched = Json.read(call(18821, "PUT", "/api/schedules", "{\"type\":\"audit-verify\",\"name\":\"srv-ha-check\","
                        + "\"everyMinutes\":60,\"enabled\":true,\"params\":{}}", alice).body(), JsonNode.class);
                String scheduleId = sched.get("id").asText();
                try (Database db = open(dbName)) {
                    a.process().destroyForcibly().waitFor(30, TimeUnit.SECONDS); // a crash: the lease is not given up
                    long killedAt = System.currentTimeMillis();
                    db.update("UPDATE schedules SET next_run_ms = ? WHERE id = ?", killedAt - 1_000, scheduleId);
                    waitFor(() -> role(bb).equals("leader"), 60_000, "it-b to take over");
                    waitFor(() -> runs(db, scheduleId) >= 1, 60_000, "it-b to fire the due schedule");
                    long tookMs = System.currentTimeMillis() - killedAt;
                    System.out.println("ServerIntegrationTest: it-b took over and fired " + tookMs + " ms after it-a was killed");
                    assertThat(tookMs).isLessThan(60_000);
                    // fired once, not again on the next ticks
                    Thread.sleep(35_000);
                    assertThat(runs(db, scheduleId)).isEqualTo(1);
                    assertThat(db.query("SELECT outcome FROM schedule_runs WHERE schedule_id = ?", scheduleId).get(0).get("outcome"))
                            .isIn("STARTED", "SUCCEEDED");
                    assertThat(((Number) db.query("SELECT next_run_ms FROM schedules WHERE id = ?", scheduleId).get(0).get("next_run_ms"))
                            .longValue()).isGreaterThan(killedAt + 50 * 60_000L);
                }

                // SRV-6: the chain holds across both instances; the SIEM got entries from both, with their hashes
                JsonNode verify = Json.read(call(18821, "GET", "/api/audit/verify", null, alice).body(), JsonNode.class);
                assertThat(verify.get("ok").asBoolean()).as(verify.toString()).isTrue();
                waitFor(() -> syslog.stream().anyMatch(m -> m.contains("actor=\"alice\"") && m.contains("srv_it.events")), 20_000,
                        "the syslog listener to get alice's CQL");
                assertThat(syslog).anyMatch(m -> m.contains("instance it-a")).anyMatch(m -> m.contains("instance it-b"));
                assertThat(syslog).allMatch(m -> m.matches("^<\\d+>1 \\S+ \\S+ \\S+ \\d+ audit \\[studio@32473 .*"));
                waitFor(() -> jsonl.size() >= 3, 20_000, "JSON lines");
                String head = verify.get("lastHash").asText();
                waitFor(() -> jsonl.stream().anyMatch(l -> l.contains(head)), 20_000, "the newest hash in the JSON export");
            } finally {
                a.process().destroyForcibly();
                if (b != null) {
                    b.process().destroy(); // clean stop: gives the lease up
                    b.process().waitFor(30, TimeUnit.SECONDS);
                }
            }
        }
    }

    private static long runs(Database db, String scheduleId) {
        return ((Number) db.query("SELECT COUNT(*) AS n FROM schedule_runs WHERE schedule_id = ?", scheduleId).get(0).get("n")).longValue();
    }

    private Path conf(String id, int port, String key, int syslogPort, int jsonPort) throws IOException {
        Path f = tmp.resolve(id + ".conf");
        Files.writeString(f, String.join("\n",
                "server.host=127.0.0.1",
                "server.port=" + port,
                "server.instanceId=" + id,
                "server.dataDir=" + tmp.resolve(id).toString().replace("\\", "/"),
                "store.url=" + PG + dbName,
                "store.user=" + USER,
                "store.password=" + PASSWORD,
                "secrets.key=" + key,
                "ha.leaseSeconds=10",
                "audit.syslog.host=127.0.0.1",
                "audit.syslog.port=" + syslogPort,
                "audit.syslog.protocol=tcp",
                "audit.json.host=127.0.0.1",
                "audit.json.port=" + jsonPort, ""));
        ServerConfig.load(f, Map.of()).validate();
        return f;
    }

    private interface Check {
        boolean ok() throws Exception;
    }

    private static void waitFor(Check c, long timeoutMs, String what) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (c.ok()) return;
            Thread.sleep(250);
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    private static void acceptAll(ServerSocket server, boolean octetCounting, List<String> out) {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                Thread.ofVirtual().start(() -> {
                    try (s) {
                        if (octetCounting) {
                            DataInputStream in = new DataInputStream(s.getInputStream());
                            while (true) {
                                StringBuilder len = new StringBuilder();
                                int ch;
                                while ((ch = in.read()) != ' ') {
                                    if (ch < 0) return;
                                    len.append((char) ch);
                                }
                                byte[] msg = new byte[Integer.parseInt(len.toString())];
                                in.readFully(msg);
                                out.add(new String(msg, StandardCharsets.UTF_8));
                            }
                        } else {
                            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                            String line;
                            while ((line = r.readLine()) != null) out.add(line);
                        }
                    } catch (IOException e) {
                        // peer gone
                    }
                });
            } catch (IOException e) {
                return;
            }
        }
    }
}
