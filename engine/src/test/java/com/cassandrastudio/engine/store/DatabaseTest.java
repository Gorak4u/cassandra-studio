package com.cassandrastudio.engine.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
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
    void newerDatabaseIsRefusedWithoutTouchingTheFile() throws Exception {
        Path file = dir.resolve(Database.FILE_NAME);
        try (Database db = Database.open(dir)) {
            db.update("INSERT INTO settings(key, value) VALUES ('k', 'v')");
            db.update("INSERT INTO schema_version(version) VALUES (?)", Database.latestVersion() + 1);
        }
        // the newer Studio used another journal mode: a refusing open must not even switch it to WAL
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file);
             java.sql.Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode = DELETE");
        }
        byte[] before = Files.readAllBytes(file);
        FileTime modified = Files.getLastModifiedTime(file);
        List<String> filesBefore = listing();

        assertThatThrownBy(() -> Database.open(dir))
                .isInstanceOf(Database.NewerDatabaseException.class)
                .hasMessageContaining("newer Studio").hasMessageContaining("not changed");

        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(modified);
        assertThat(listing()).isEqualTo(filesBefore); // no -wal, -shm, journal or backup created
    }

    @Test
    void copiesTheDatabaseBeforeMigratingAndKeepsData() throws Exception {
        try (Database db = Database.open(dir)) {
            assertThat(db.lastBackup()).isNull(); // a new database needs no copy
            db.update("INSERT INTO folders(id, name) VALUES ('f1', 'acme')");
            // pretend this file is from the release before the latest migration
            db.update("DELETE FROM schema_version WHERE version = ?", Database.latestVersion());
            db.update("DROP TABLE saved_scripts");
            db.update("DROP TABLE settings");
        }
        try (Database db = Database.open(dir)) {
            assertThat(db.schemaVersion()).isEqualTo(Database.latestVersion());
            assertThat(db.query("SELECT name FROM folders")).singleElement().satisfies(r -> assertThat(r.get("name")).isEqualTo("acme"));
            Path copy = db.lastBackup();
            assertThat(copy).isNotNull().exists();
            assertThat(copy.getParent()).isEqualTo(dir.resolve("backups"));
            assertThat(copy.getFileName().toString()).startsWith("studio-v" + (Database.latestVersion() - 1) + "-");
            // the copy is the old version, readable on its own
            try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + copy);
                 java.sql.ResultSet rs = c.createStatement().executeQuery("SELECT MAX(version), (SELECT COUNT(*) FROM folders) FROM schema_version")) {
                assertThat(rs.getInt(1)).isEqualTo(Database.latestVersion() - 1);
                assertThat(rs.getInt(2)).isEqualTo(1);
            }
        }
        try (Database db = Database.open(dir)) {
            assertThat(db.lastBackup()).isNull(); // already current: no further copies
        }
    }

    @Test
    void keepsOnlyTheLatestBackups() throws Exception {
        for (int i = 0; i < Database.KEEP_BACKUPS + 3; i++) {
            try (Database db = Database.open(dir)) {
                db.update("DELETE FROM schema_version WHERE version = ?", Database.latestVersion());
                db.update("DROP TABLE saved_scripts");
                db.update("DROP TABLE settings");
            }
            try (Database db = Database.open(dir)) {
                assertThat(db.lastBackup()).isNotNull();
            }
        }
        try (var s = Files.list(dir.resolve("backups"))) {
            assertThat(s.count()).isEqualTo(Database.KEEP_BACKUPS);
        }
    }

    @Test
    void unreadableFileIsReportedAndLeftAlone() throws Exception {
        Path file = dir.resolve(Database.FILE_NAME);
        Files.writeString(file, "this is not a database");
        assertThatThrownBy(() -> Database.open(dir)).hasMessageContaining("left unchanged");
        assertThat(Files.readString(file)).isEqualTo("this is not a database");
    }

    private List<String> listing() throws Exception {
        try (var s = Files.walk(dir)) {
            return s.map(p -> dir.relativize(p).toString()).sorted().toList();
        }
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
