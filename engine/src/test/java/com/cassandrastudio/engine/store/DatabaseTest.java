package com.cassandrastudio.engine.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseTest {
    @TempDir
    Path dir;

    @Test
    void migratesToLatestAndIsIdempotent() {
        try (Database db = Database.open(dir)) {
            assertThat(db.schemaVersion()).isEqualTo(Database.latestVersion());
        }
        try (Database db = Database.open(dir)) {
            assertThat(db.schemaVersion()).isEqualTo(Database.latestVersion());
            assertThat(db.query("SELECT COUNT(*) AS n FROM schema_version").get(0).get("n")).isEqualTo(Database.latestVersion());
        }
    }

    @Test
    void refusesDatabaseFromNewerStudio() {
        try (Database db = Database.open(dir)) {
            db.update("INSERT INTO schema_version(version) VALUES (?)", Database.latestVersion() + 5);
        }
        assertThatThrownBy(() -> Database.open(dir)).hasMessageContaining("newer Studio");
    }

    @Test
    void transactionRollsBack() {
        try (Database db = Database.inMemory()) {
            assertThatThrownBy(() -> db.transaction(() -> {
                db.update("INSERT INTO settings(key, value) VALUES ('a', '1')");
                throw new IllegalStateException("boom");
            })).hasMessage("boom");
            assertThat(db.query("SELECT * FROM settings")).isEmpty();
        }
    }
}
