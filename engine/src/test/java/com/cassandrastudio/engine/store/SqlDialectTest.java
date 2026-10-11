package com.cassandrastudio.engine.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class SqlDialectTest {
    private final SqlDialect pg = new SqlDialect(Database.Dialect.POSTGRESQL,
            t -> t.equals("settings") ? List.of("key") : t.equals("pairs") ? List.of("a", "b") : List.of());
    private final SqlDialect lite = new SqlDialect(Database.Dialect.SQLITE, t -> List.of());

    @Test
    void sqliteIsUnchanged() {
        String s = "INSERT OR REPLACE INTO settings(key, value) VALUES (?, ?)";
        assertThat(lite.dml(s)).isSameAs(s);
        assertThat(lite.ddl("CREATE TABLE t (id INTEGER PRIMARY KEY AUTOINCREMENT)")).isEqualTo("CREATE TABLE t (id INTEGER PRIMARY KEY AUTOINCREMENT)");
    }

    @Test
    void ddlTypesForPostgres() {
        assertThat(pg.ddl("CREATE TABLE t (id INTEGER PRIMARY KEY AUTOINCREMENT, n INTEGER NOT NULL, r REAL, b BLOB, s TEXT)"))
                .isEqualTo("CREATE TABLE t (id BIGSERIAL PRIMARY KEY, n BIGINT NOT NULL, r DOUBLE PRECISION, b BYTEA, s TEXT)");
        assertThat(pg.ddl("CREATE TABLE c (id integer primary key autoincrement)")).isEqualTo("CREATE TABLE c (id BIGSERIAL PRIMARY KEY)");
    }

    @Test
    void insertOrReplaceBecomesUpsertOnThePrimaryKey() {
        assertThat(pg.dml("INSERT OR REPLACE INTO settings(key, value) VALUES (?, ?)"))
                .isEqualTo("INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value");
        assertThat(pg.dml("insert or replace into pairs (a, b, c, d) values (?,?,?,?)"))
                .isEqualTo("INSERT INTO pairs (a, b, c, d) values (?,?,?,?) ON CONFLICT (a, b) DO UPDATE SET c = EXCLUDED.c, d = EXCLUDED.d");
        assertThat(pg.dml("INSERT OR REPLACE INTO pairs(a, b) VALUES (?, ?)"))
                .isEqualTo("INSERT INTO pairs (a, b) VALUES (?, ?) ON CONFLICT (a, b) DO NOTHING");
    }

    @Test
    void insertOrIgnoreAndLike() {
        assertThat(pg.dml("INSERT OR IGNORE INTO settings(key, value) VALUES (?, ?)"))
                .isEqualTo("INSERT INTO settings(key, value) VALUES (?, ?) ON CONFLICT DO NOTHING");
        assertThat(pg.dml("SELECT * FROM audit_log WHERE action LIKE ? OR detail NOT LIKE ?"))
                .isEqualTo("SELECT * FROM audit_log WHERE action ILIKE ? OR detail NOT ILIKE ?");
        assertThat(pg.dml("SELECT likes, unlike FROM t")).isEqualTo("SELECT likes, unlike FROM t");
    }

    @Test
    void unsupportedFormsFailClearly() {
        assertThatThrownBy(() -> pg.dml("INSERT OR REPLACE INTO settings VALUES (?, ?)")).hasMessageContaining("name the columns");
        assertThatThrownBy(() -> pg.dml("INSERT OR REPLACE INTO nokey(a) VALUES (?)")).hasMessageContaining("primary key");
    }
}
