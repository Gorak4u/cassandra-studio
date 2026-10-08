package com.cassandrastudio.engine.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cassandrastudio.engine.schema.DdlBuilder.ClusteringSpec;
import com.cassandrastudio.engine.schema.DdlBuilder.ColumnSpec;
import com.cassandrastudio.engine.schema.DdlBuilder.IndexSpec;
import com.cassandrastudio.engine.schema.DdlBuilder.KeyspaceSpec;
import com.cassandrastudio.engine.schema.DdlBuilder.TableSpec;
import com.cassandrastudio.engine.util.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DdlBuilderTest {

    @Test
    void quotesIdentifiersOnlyWhenNeeded() {
        assertThat(DdlBuilder.id("users")).isEqualTo("users");
        assertThat(DdlBuilder.id("Users")).isEqualTo("\"Users\"");
        assertThat(DdlBuilder.id("select")).isEqualTo("\"select\"");
        assertThat(DdlBuilder.id("we\"ird")).isEqualTo("\"we\"\"ird\"");
    }

    @Test
    void networkTopologyKeyspace() {
        Map<String, Integer> dcs = new LinkedHashMap<>();
        dcs.put("dc_east", 3);
        dcs.put("dc_west", 2);
        assertThat(DdlBuilder.createKeyspace(new KeyspaceSpec("shop", "NetworkTopologyStrategy", null, dcs, null, true)))
                .isEqualTo("CREATE KEYSPACE IF NOT EXISTS shop\n  WITH replication = {'class': 'NetworkTopologyStrategy', "
                        + "'dc_east': '3', 'dc_west': '2'};");
    }

    @Test
    void simpleStrategyKeyspaceWithoutDurableWrites() {
        assertThat(DdlBuilder.createKeyspace(new KeyspaceSpec("t", "SimpleStrategy", 1, null, false, false)))
                .isEqualTo("CREATE KEYSPACE t\n  WITH replication = {'class': 'SimpleStrategy', 'replication_factor': '1'}"
                        + "\n  AND durable_writes = false;");
    }

    @Test
    void createTableWithCompositeKeyClusteringAndOptions() {
        Map<String, String> opts = new LinkedHashMap<>();
        opts.put("default_time_to_live", "86400");
        opts.put("compaction", "{'class': 'TimeWindowCompactionStrategy'}");
        String cql = DdlBuilder.createTable(new TableSpec("ks", "events",
                List.of(new ColumnSpec("tenant", "text", false), new ColumnSpec("day", "date", false),
                        new ColumnSpec("ts", "timestamp", false), new ColumnSpec("owner", "text", true),
                        new ColumnSpec("payload", "blob", false)),
                List.of("tenant", "day"), List.of(new ClusteringSpec("ts", "desc")), opts, false));
        assertThat(cql).isEqualTo("""
                CREATE TABLE ks.events (
                  tenant text,
                  day date,
                  ts timestamp,
                  owner text STATIC,
                  payload blob,
                  PRIMARY KEY ((tenant, day), ts)
                ) WITH CLUSTERING ORDER BY (ts DESC)
                  AND default_time_to_live = 86400
                  AND compaction = {'class': 'TimeWindowCompactionStrategy'};""");
    }

    @Test
    void rejectsInjectionInTypesAndOptions() {
        assertThatThrownBy(() -> DdlBuilder.createTable(new TableSpec("ks", "t",
                List.of(new ColumnSpec("k", "int); DROP KEYSPACE ks; --", false)), List.of("k"), null, null, false)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> DdlBuilder.alterTableOptions("ks", "t", Map.of("comment", "'x'; DROP TABLE t")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> DdlBuilder.alterTableOptions("ks", "t", Map.of("bad name", "1")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void validatesKeys() {
        assertThatThrownBy(() -> DdlBuilder.createTable(new TableSpec("ks", "t",
                List.of(new ColumnSpec("k", "int", false)), List.of("missing"), null, null, false)))
                .hasMessageContaining("missing");
        assertThatThrownBy(() -> DdlBuilder.createTable(new TableSpec("ks", "t",
                List.of(new ColumnSpec("k", "int", false), new ColumnSpec("s", "int", true)), List.of("k"), null, null, false)))
                .hasMessageContaining("Static");
    }

    @Test
    void indexes() {
        assertThat(DdlBuilder.createIndex(new IndexSpec("ks", "t", "t_v", "v", null, null)))
                .isEqualTo("CREATE INDEX t_v ON ks.t (v);");
        assertThat(DdlBuilder.createIndex(new IndexSpec("ks", "t", null, "m", "keys", "sai")))
                .isEqualTo("CREATE INDEX ON ks.t (KEYS(m)) USING 'sai';");
    }

    @Test
    void literalsEscapeQuotes() {
        assertThat(DdlBuilder.literal("it's")).isEqualTo("'it''s'");
    }
}
