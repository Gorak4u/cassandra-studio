package com.cassandrastudio.engine.cql;

import static org.assertj.core.api.Assertions.assertThat;

import com.cassandrastudio.engine.cql.StatementClassifier.Kind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class StatementClassifierTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "SELECT * FROM t WHERE k = 1 | READ",
            "select * from t where k=1 | READ",
            "LIST ROLES | READ",
            "DESCRIBE KEYSPACES | READ",
            "INSERT INTO t (k) VALUES (1) | WRITE",
            "UPDATE t SET v = 1 WHERE k = 1 | WRITE",
            "DELETE FROM t WHERE k = 1 | WRITE",
            "BEGIN BATCH INSERT INTO t (k) VALUES (1); APPLY BATCH | WRITE",
            "TRUNCATE t | WRITE",
            "CREATE TABLE t (k int PRIMARY KEY) | DDL",
            "ALTER TABLE t ADD v int | DDL",
            "DROP KEYSPACE ks | DDL",
            "CREATE ROLE bob WITH PASSWORD = 'x' | DCL",
            "ALTER USER bob WITH PASSWORD 'x' | DCL",
            "GRANT SELECT ON ALL KEYSPACES TO bob | DCL",
            "USE ks | CLIENT",
            "CONSISTENCY QUORUM | CLIENT",
            "FROBNICATE | UNKNOWN",
    })
    void classifies(String cql, Kind kind) {
        assertThat(StatementClassifier.classify(cql).kind()).isEqualTo(kind);
    }

    @Test
    void readsDoNotChangeAnything() {
        assertThat(StatementClassifier.classify("SELECT * FROM t WHERE k = 1").changesSomething()).isFalse();
        assertThat(StatementClassifier.classify("INSERT INTO t (k) VALUES (1)").changesSomething()).isTrue();
        assertThat(StatementClassifier.classify("FROBNICATE").changesSomething()).isTrue();
    }

    @Test
    void warnsOnAllowFilteringAndFullScans() {
        assertThat(StatementClassifier.classify("SELECT * FROM t WHERE v = 1 ALLOW FILTERING").warnings())
                .anyMatch(w -> w.contains("ALLOW FILTERING"));
        assertThat(StatementClassifier.classify("SELECT * FROM t").warnings()).anyMatch(w -> w.contains("full table scan"));
        assertThat(StatementClassifier.classify("SELECT * FROM t WHERE k = 1").warnings()).isEmpty();
    }

    @Test
    void keywordsInsideStringsAreIgnored() {
        var c = StatementClassifier.classify("SELECT * FROM t WHERE k = 'ALLOW FILTERING'");
        assertThat(c.warnings()).noneMatch(w -> w.contains("ALLOW FILTERING"));
    }

    @Test
    void leadingCommentsAreIgnored() {
        assertThat(StatementClassifier.classify("-- drop it\n/* really */ DROP TABLE t").kind()).isEqualTo(Kind.DDL);
    }

    @Test
    void destructiveStatements() {
        assertThat(StatementClassifier.classify("TRUNCATE t").destructive()).isTrue();
        assertThat(StatementClassifier.classify("DROP TABLE t").destructive()).isTrue();
        assertThat(StatementClassifier.classify("ALTER TABLE t ADD v int").destructive()).isFalse();
    }
}
