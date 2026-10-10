package com.cassandrastudio.engine.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.cassandrastudio.engine.audit.AuditLog.Outcome;
import com.cassandrastudio.engine.store.Database;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuditLogTest {
    @Test
    void exportUsesTheSearchFiltersWithoutTheViewsRowCap() {
        try (Database db = Database.inMemory()) {
            AuditLog log = new AuditLog(db, "alice");
            db.transaction(() -> {
                for (int i = 0; i < 10_050; i++) log.record(null, null, "cql", "INSERT " + i, "d", Outcome.SUCCESS, null);
            });
            log.record(null, "10.0.0.1", "ops", "flush", "nodetool flush shop", Outcome.FAILED, "refused");
            assertThat(log.search(null, null, null, 1_000_000)).hasSize(10_000);
            assertThat(log.export(null, null, null, 1_000_000)).hasSize(10_051);
            assertThat(log.export(null, "flush", null, 100)).singleElement().satisfies(e -> {
                assertThat(e.node()).isEqualTo("10.0.0.1");
                assertThat(e.outcome()).isEqualTo("FAILED");
            });
            assertThat(log.export(null, null, "2999-01-01", 100)).isEmpty();
        }
    }

    @Test
    void csvQuotesAndDefusesFormulas() {
        AuditLog.Entry e = new AuditLog.Entry(7, "2026-10-10T00:00:00Z", "alice", "c1", "core, prod", "PROD", null, "cql",
                "=HYPERLINK(\"x\")", "line1\nline2 \"q\"", "SUCCESS", null);
        String csv = AuditLog.toCsv(List.of(e));
        String[] lines = csv.split("\r\n", 2);
        assertThat(lines[0]).isEqualTo("id,at,actor,connection_id,connection_name,environment,node,category,action,detail,outcome,error");
        assertThat(lines[1]).isEqualTo("7,2026-10-10T00:00:00Z,alice,c1,\"core, prod\",PROD,,cql,\"'=HYPERLINK(\"\"x\"\")\","
                + "\"line1\nline2 \"\"q\"\"\",SUCCESS,\r\n");
        assertThat(AuditLog.csvCell("-1")).isEqualTo("'-1");
        assertThat(AuditLog.csvCell(-1)).isEqualTo("-1");
    }
}
