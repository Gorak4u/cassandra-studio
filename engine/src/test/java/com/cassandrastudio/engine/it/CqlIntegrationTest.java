package com.cassandrastudio.engine.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.cql.ClusterService;
import com.cassandrastudio.engine.cql.QueryService.QueryRequest;
import com.cassandrastudio.engine.cql.QueryService.ScriptResult;
import com.cassandrastudio.engine.cql.QueryService.StatementResult;
import com.cassandrastudio.engine.cql.RowEditService;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.Environment;
import com.cassandrastudio.engine.schema.SchemaService;
import com.cassandrastudio.engine.secrets.SecretStores;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.ApiException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.cassandra.CassandraContainer;

/** CQL, schema, DESCRIBE, paging, tracing, node pinning and grid edits against real Cassandra. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CqlIntegrationTest {
    private Engine engine;

    static Stream<String> versions() {
        return CassandraClusters.versions().stream();
    }

    @BeforeAll
    void setUp() {
        engine = new Engine(Database.inMemory(), SecretStores.inMemory(), "it");
    }

    @AfterAll
    void tearDown() {
        engine.close();
    }

    private String connect(String version, boolean readOnly) {
        CassandraContainer c = CassandraClusters.get(version);
        ConnectionConfig cfg = new ConnectionConfig(null, null, "it-" + version + (readOnly ? "-ro" : ""), Environment.DEV,
                null, readOnly, List.of(CassandraClusters.contactPoint(c)), "dc1", null, null, null, "ONE", 30_000, null,
                null, null, null, null, null);
        return engine.connections.save(cfg, null).id();
    }

    private static QueryRequest q(String cql, boolean confirmed) {
        return new QueryRequest(cql, null, null, null, null, null, null, false, null, true, null, confirmed, null);
    }

    private ScriptResult ok(String id, String cql) {
        ScriptResult r = engine.queries.execute(id, q(cql, true));
        for (StatementResult s : r.results()) assertThat(s.error()).as(s.statement()).isNull();
        return r;
    }

    private static String ks(String version) {
        return "it_" + version.replace('.', '_');
    }

    @ParameterizedTest
    @MethodSource("versions")
    void clusterInfoAndSchemaAgreement(String version) {
        String id = connect(version, false);
        ClusterService.ClusterInfo info = engine.clusters.info(id);
        assertThat(info.nodes()).hasSize(1);
        assertThat(info.nodes().get(0).version()).startsWith(version);
        assertThat(info.nodes().get(0).state()).isEqualTo("UP");
        assertThat(info.nodes().get(0).tokens()).isPositive();
        assertThat(info.datacenters()).containsExactly("dc1");
        assertThat(info.schemaAgreement()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("versions")
    void writesNeedConfirmationAndReadOnlyBlocksThem(String version) {
        String id = connect(version, false);
        assertThatThrownBy(() -> engine.queries.execute(id, q("CREATE KEYSPACE IF NOT EXISTS nope WITH replication = "
                + "{'class': 'SimpleStrategy', 'replication_factor': 1}", false)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(428));
        String ro = connect(version, true);
        assertThatThrownBy(() -> engine.queries.execute(ro, q("TRUNCATE system_auth.roles", true)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(403));
        assertThat(engine.queries.execute(ro, q("SELECT release_version FROM system.local", false)).results().get(0).rows())
                .hasSize(1);
    }

    @ParameterizedTest
    @MethodSource("versions")
    void scriptPagingTracingAndPinning(String version) {
        String id = connect(version, false);
        String ks = ks(version);
        ok(id, "CREATE KEYSPACE IF NOT EXISTS " + ks + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};"
                + "CREATE TABLE IF NOT EXISTS " + ks + ".events (tenant text, ts int, note text, PRIMARY KEY (tenant, ts));"
                + "BEGIN BATCH "
                + "INSERT INTO " + ks + ".events (tenant, ts, note) VALUES ('a', 1, 'one; with semicolon');"
                + "INSERT INTO " + ks + ".events (tenant, ts, note) VALUES ('a', 2, 'two');"
                + "INSERT INTO " + ks + ".events (tenant, ts, note) VALUES ('a', 3, 'three');"
                + "INSERT INTO " + ks + ".events (tenant, ts, note) VALUES ('a', 4, 'four');"
                + "INSERT INTO " + ks + ".events (tenant, ts, note) VALUES ('a', 5, 'five');"
                + "APPLY BATCH;");

        // USE then an unqualified query: per-request keyspace on v5, a keyspace session on older protocols.
        QueryRequest page1 = new QueryRequest("USE " + ks + "; SELECT ts, note FROM events WHERE tenant = 'a'", null, null,
                null, null, 2, null, true, null, true, null, false, null);
        ScriptResult r1 = engine.queries.execute(id, page1);
        StatementResult sel = r1.results().get(1);
        assertThat(r1.keyspace()).isEqualTo(ks);
        assertThat(sel.rows()).hasSize(2);
        assertThat(sel.rows().get(0)).containsExactly(1, "one; with semicolon");
        assertThat(sel.hasMore()).isTrue();
        assertThat(sel.trace()).isNotNull();
        assertThat(sel.coordinator()).isNotBlank();

        QueryRequest page2 = new QueryRequest("SELECT ts, note FROM events WHERE tenant = 'a'", ks, null, null, null, 2,
                sel.pagingState(), false, null, true, null, false, null);
        assertThat(engine.queries.execute(id, page2).results().get(0).rows()).extracting(row -> row.get(0)).containsExactly(3, 4);

        QueryRequest all = new QueryRequest("SELECT ts FROM " + ks + ".events WHERE tenant = 'a'", null, sel.coordinator(),
                null, null, 2, null, false, null, true, 1000, false, null);
        StatementResult fetched = engine.queries.execute(id, all).results().get(0);
        assertThat(fetched.rows()).hasSize(5);
        assertThat(fetched.coordinator()).isEqualTo(sel.coordinator());

        // Review finding: "fetch all" past maxRows must not drop rows. maxRows 3 with page size 2 stops at the page
        // boundary (4 rows) and the paging state continues with row 5.
        StatementResult capped = engine.queries.execute(id, new QueryRequest("SELECT ts FROM " + ks + ".events WHERE tenant = 'a'",
                null, null, null, null, 2, null, false, null, true, 3, false, null)).results().get(0);
        assertThat(capped.rows()).extracting(row -> row.get(0)).containsExactly(1, 2, 3, 4);
        StatementResult rest = engine.queries.execute(id, new QueryRequest("SELECT ts FROM " + ks + ".events WHERE tenant = 'a'",
                null, null, null, null, 2, capped.pagingState(), false, null, true, null, false, null)).results().get(0);
        assertThat(rest.rows()).extracting(row -> row.get(0)).containsExactly(5);
        assertThat(capped.keyspace()).isNull();
        assertThat(capped.consistency()).isEqualTo("ONE");

        // Review finding: an engine-side error mid-script is reported on that statement; the others still run.
        ScriptResult mixed = engine.queries.execute(id, new QueryRequest("SELECT ts FROM " + ks + ".events WHERE tenant = 'a';"
                + "CONSISTENCY NOPE; SELECT ts FROM " + ks + ".events WHERE tenant = 'a'", null, null, null, null, null, null,
                false, null, false, null, false, null));
        assertThat(mixed.results()).extracting(StatementResult::status).containsExactly("ok", "error", "ok");

        // Stop on error: the third statement is skipped.
        ScriptResult failing = engine.queries.execute(id, q("SELECT * FROM " + ks + ".events WHERE tenant = 'a';"
                + "SELECT * FROM " + ks + ".no_such_table;SELECT * FROM " + ks + ".events WHERE tenant = 'a'", false));
        assertThat(failing.results()).extracting(StatementResult::status).containsExactly("ok", "error", "skipped");

        assertThat(engine.queries.history(id, "events", 50)).isNotEmpty();
        assertThat(engine.audit.search(id, "INSERT", null, 50)).isNotEmpty();
    }

    @ParameterizedTest
    @MethodSource("versions")
    void typesDisplayAndGridEditRoundTrip(String version) {
        String id = connect(version, false);
        String ks = ks(version) + "_types";
        ok(id, "CREATE KEYSPACE IF NOT EXISTS " + ks + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};"
                + "CREATE TYPE IF NOT EXISTS " + ks + ".address (street text, zip int);"
                + "CREATE TABLE IF NOT EXISTS " + ks + ".t (id uuid, at timestamp, big bigint, amount decimal, tags set<text>, "
                + "attrs map<text, int>, data blob, home frozen<address>, PRIMARY KEY (id, at));"
                + "INSERT INTO " + ks + ".t (id, at, big, amount, tags, attrs, data, home) VALUES "
                + "(5132b130-ae79-11e4-ab27-0800200c9a66, '2024-03-01T10:00:00.000Z', 9007199254740993, 12.50, "
                + "{'x', 'y'}, {'a': 1}, 0xCAFE, {street: 'Main', zip: 12345});");
        StatementResult r = ok(id, "SELECT id, at, big, amount, tags, attrs, data, home FROM " + ks + ".t").results().get(0);
        List<Object> row = r.rows().get(0);
        assertThat(row.get(0)).isEqualTo("5132b130-ae79-11e4-ab27-0800200c9a66");
        assertThat((String) row.get(1)).startsWith("2024-03-01T10:00:00");
        assertThat(row.get(2)).isEqualTo("9007199254740993"); // beyond JS precision: kept as a string
        assertThat(row.get(3)).isEqualTo("12.50");
        assertThat(row.get(4)).isEqualTo("{'x','y'}");
        assertThat(row.get(5)).isEqualTo("{'a':1}");
        assertThat(row.get(6)).isEqualTo("0xcafe");
        assertThat((String) row.get(7)).contains("Main").contains("12345");

        // Edit using the displayed values, exactly as the grid would send them.
        String cql = engine.rowEdits.toCql(id, new RowEditService.RowEdit(ks, "t", RowEditService.Op.UPDATE,
                Map.of("id", (String) row.get(0), "at", (String) row.get(1)), Map.of("big", "42", "tags", "{'z'}")));
        assertThat(cql).startsWith("UPDATE " + ks + ".t SET").contains("WHERE id = 5132b130");
        ok(id, cql);
        List<Object> after = ok(id, "SELECT big, tags FROM " + ks + ".t").results().get(0).rows().get(0);
        assertThat(after).containsExactly("42", "{'z'}");

        assertThatThrownBy(() -> engine.rowEdits.toCql(id, new RowEditService.RowEdit(ks, "t", RowEditService.Op.UPDATE,
                Map.of("id", (String) row.get(0)), Map.of("big", "1"))))
                .hasMessageContaining("full primary key");
        assertThatThrownBy(() -> engine.rowEdits.toCql(id, new RowEditService.RowEdit(ks, "t", RowEditService.Op.UPDATE,
                Map.of("id", (String) row.get(0), "at", (String) row.get(1)), Map.of("big", "not-a-number"))))
                .hasMessageContaining("not a valid bigint");
    }

    @ParameterizedTest
    @MethodSource("versions")
    void schemaBrowserAndDescribe(String version) {
        String id = connect(version, false);
        String ks = ks(version) + "_schema";
        ok(id, "CREATE KEYSPACE IF NOT EXISTS " + ks + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};"
                + "CREATE TABLE IF NOT EXISTS " + ks + ".orders (customer text, day date, id timeuuid, owner text static, "
                + "total int, PRIMARY KEY ((customer, day), id)) WITH CLUSTERING ORDER BY (id DESC);"
                + "CREATE INDEX IF NOT EXISTS orders_total ON " + ks + ".orders (total);");
        SchemaService.Tree tree = engine.schema.tree(id, true);
        SchemaService.KeyspaceNode node = tree.keyspaces().stream().filter(k -> k.name().equals(ks)).findFirst().orElseThrow();
        assertThat(node.system()).isFalse();
        assertThat(node.tables()).extracting(SchemaService.ObjectRef::name).contains("orders");
        assertThat(node.indexes()).extracting(SchemaService.ObjectRef::name).contains("orders.orders_total");
        assertThat(tree.keyspaces()).anyMatch(k -> k.name().equals("system") && k.system());

        SchemaService.TableDetails t = engine.schema.table(id, ks, "orders");
        assertThat(t.partitionKey()).containsExactly("customer", "day");
        assertThat(t.clusteringColumns()).containsExactly("id");
        assertThat(t.columns()).anyMatch(c -> c.name().equals("owner") && c.kind().equals("static"));
        assertThat(t.columns()).anyMatch(c -> c.name().equals("id") && "DESC".equals(c.clusteringOrder()));
        assertThat(t.options()).containsKey("gc_grace_seconds");
        assertThat(t.ddl()).contains("CREATE TABLE " + ks + ".orders");

        StatementResult d = ok(id, "DESCRIBE TABLE " + ks + ".orders").results().get(0);
        assertThat((String) d.rows().get(0).get(0)).contains("PRIMARY KEY ((customer, day), id)");
        StatementResult dk = ok(id, "USE " + ks + "; DESC KEYSPACE").results().get(1);
        assertThat((String) dk.rows().get(0).get(0)).contains("CREATE KEYSPACE " + ks).contains("CREATE INDEX orders_total");
        assertThat(engine.schema.completions(id).get(ks)).containsKey("orders");
        assertThat(engine.schema.keyspace(id, ks).replication()).containsEntry("replication_factor", "1");
    }

    @ParameterizedTest
    @MethodSource("versions")
    void rolesViewReportsErrorsInsteadOfFailing(String version) {
        String id = connect(version, false);
        var view = engine.roles.list(id);
        // AllowAllAuthorizer in the stock image: permissions cannot be listed, roles still can.
        assertThat(view.rolesError()).isNull();
        assertThat(view.permissionsError()).isNotNull();
    }
}
