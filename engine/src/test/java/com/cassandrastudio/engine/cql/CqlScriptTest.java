package com.cassandrastudio.engine.cql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CqlScriptTest {

    private static List<String> texts(String script) {
        return CqlScript.split(script).stream().map(CqlScript.Statement::text).toList();
    }

    @Test
    void splitsOnSemicolons() {
        assertThat(texts("SELECT * FROM a; SELECT * FROM b;")).containsExactly("SELECT * FROM a", "SELECT * FROM b");
    }

    @Test
    void lastStatementNeedsNoSemicolon() {
        assertThat(texts("SELECT 1 FROM a;\nSELECT 2 FROM b")).containsExactly("SELECT 1 FROM a", "SELECT 2 FROM b");
    }

    @Test
    void semicolonInsideStringDoesNotSplit() {
        assertThat(texts("INSERT INTO t (k, v) VALUES (1, 'a;b''c;');")).containsExactly("INSERT INTO t (k, v) VALUES (1, 'a;b''c;')");
    }

    @Test
    void semicolonInsideQuotedIdentifierDoesNotSplit() {
        assertThat(texts("SELECT \"we;ird\" FROM t;")).containsExactly("SELECT \"we;ird\" FROM t");
    }

    @Test
    void dollarQuotedFunctionBody() {
        String fn = "CREATE FUNCTION ks.f(x int) RETURNS NULL ON NULL INPUT RETURNS int LANGUAGE java AS $$ return x; $$";
        assertThat(texts(fn + "; SELECT 1 FROM t;")).containsExactly(fn, "SELECT 1 FROM t");
    }

    @Test
    void commentsAreRemovedAndDoNotSplit() {
        assertThat(texts("-- a comment; with semicolon\nSELECT 1 FROM t; // another;\n/* block; */ SELECT 2 FROM t;"))
                .containsExactly("SELECT 1 FROM t", "SELECT 2 FROM t");
    }

    @Test
    void batchKeepsInnerStatements() {
        String batch = "BEGIN BATCH\n INSERT INTO t (k) VALUES (1);\n INSERT INTO t (k) VALUES (2);\nAPPLY BATCH";
        assertThat(texts(batch + ";\nSELECT * FROM t;")).containsExactly(batch, "SELECT * FROM t");
    }

    @Test
    void unloggedBatch() {
        String batch = "begin unlogged batch insert into t (k) values (1); apply batch";
        assertThat(texts(batch + ";")).containsExactly(batch);
    }

    @Test
    void reportsStartLine() {
        List<CqlScript.Statement> s = CqlScript.split("SELECT 1 FROM a;\n\n-- note\nSELECT 2\nFROM b;");
        assertThat(s).extracting(CqlScript.Statement::line).containsExactly(1, 4);
    }

    @Test
    void emptyAndCommentOnlyScripts() {
        assertThat(texts("  ;; -- nothing\n")).isEmpty();
    }
}
