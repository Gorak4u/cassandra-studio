package com.cassandrastudio.engine.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Studio's own SQLite database: connections, folders, history, audit.
 *
 * <p>Schema changes are numbered migrations applied in order and recorded in
 * {@code schema_version} (NFR-DATA). A database written by a newer Studio is
 * refused rather than silently misread, so a downgrade can never corrupt it.
 */
public final class Database implements AutoCloseable {

    /** Append only. Never edit a released migration; add a new one. */
    private static final List<String> MIGRATIONS = List.of(
            // 1: connections and folders
            """
            CREATE TABLE folders (
              id TEXT PRIMARY KEY,
              parent_id TEXT REFERENCES folders(id) ON DELETE CASCADE,
              name TEXT NOT NULL,
              position INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE connections (
              id TEXT PRIMARY KEY,
              folder_id TEXT REFERENCES folders(id) ON DELETE SET NULL,
              name TEXT NOT NULL,
              config_json TEXT NOT NULL,
              position INTEGER NOT NULL DEFAULT 0,
              updated_at TEXT NOT NULL
            );
            """,
            // 2: query history and audit log
            """
            CREATE TABLE query_history (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              connection_id TEXT NOT NULL,
              executed_at TEXT NOT NULL,
              statement TEXT NOT NULL,
              keyspace TEXT,
              node TEXT,
              duration_ms INTEGER,
              row_count INTEGER,
              error TEXT
            );
            CREATE INDEX query_history_conn ON query_history(connection_id, executed_at);
            CREATE TABLE audit_log (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              at TEXT NOT NULL,
              actor TEXT NOT NULL,
              connection_id TEXT,
              connection_name TEXT,
              environment TEXT,
              node TEXT,
              category TEXT NOT NULL,
              action TEXT NOT NULL,
              detail TEXT,
              outcome TEXT NOT NULL,
              error TEXT
            );
            CREATE INDEX audit_log_at ON audit_log(at);
            """,
            // 3: saved scripts and generic settings
            """
            CREATE TABLE saved_scripts (
              id TEXT PRIMARY KEY,
              folder TEXT NOT NULL DEFAULT '',
              name TEXT NOT NULL,
              content TEXT NOT NULL,
              updated_at TEXT NOT NULL
            );
            CREATE TABLE settings (
              key TEXT PRIMARY KEY,
              value TEXT NOT NULL
            );
            """,
            // 4: schedules (SRV-5) and their run history
            """
            CREATE TABLE schedules (
              id TEXT PRIMARY KEY,
              connection_id TEXT,
              type TEXT NOT NULL,
              name TEXT NOT NULL,
              every_minutes INTEGER NOT NULL,
              at_time TEXT,
              window_start TEXT,
              window_end TEXT,
              enabled INTEGER NOT NULL DEFAULT 1,
              params_json TEXT NOT NULL DEFAULT '{}',
              next_run_ms INTEGER,
              last_run_ms INTEGER,
              last_outcome TEXT,
              last_job_id TEXT
            );
            CREATE TABLE schedule_runs (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              schedule_id TEXT NOT NULL,
              started_ms INTEGER NOT NULL,
              job_id TEXT,
              outcome TEXT NOT NULL,
              detail TEXT
            );
            CREATE INDEX schedule_runs_schedule ON schedule_runs(schedule_id, id);
            """
    );

    /** Copies taken before a migration that are kept in {@code <data dir>/backups}; older ones are deleted. */
    static final int KEEP_BACKUPS = 5;
    public static final String FILE_NAME = "studio.db";

    private final Connection conn;
    /** Where pre-migration copies go; null for in-memory databases. */
    private final Path backupDir;
    private volatile Path lastBackup;

    private Database(Connection conn, Path backupDir) {
        this.conn = conn;
        this.backupDir = backupDir;
    }

    /**
     * Opens (creating if needed) {@code <dataDir>/studio.db} and migrates it to the latest version,
     * copying it to {@code <dataDir>/backups} first. A database written by a newer Studio is refused
     * before anything is written to it: it is probed through an immutable read-only handle, so not
     * even the journal mode or a lock file changes (NFR-DATA).
     */
    public static Database open(Path dataDir) {
        Path file = dataDir.resolve(FILE_NAME).toAbsolutePath();
        try {
            Files.createDirectories(dataDir);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot create data directory " + dataDir, e);
        }
        if (Files.exists(file)) refuseIfNewer(probeVersion(file));
        return open("jdbc:sqlite:" + file, dataDir.resolve("backups"));
    }

    public static Database inMemory() {
        return open("jdbc:sqlite::memory:", null);
    }

    private static Database open(String url, Path backupDir) {
        try {
            Connection c = DriverManager.getConnection(url);
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                st.execute("PRAGMA journal_mode = WAL");
                st.execute("PRAGMA busy_timeout = 5000");
            }
            Database db = new Database(c, backupDir);
            try {
                db.migrate();
            } catch (SQLException | RuntimeException e) {
                db.close();
                throw e;
            }
            return db;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot open Studio database " + url + ": " + e.getMessage(), e);
        }
    }

    /**
     * The schema version of an existing file, read without writing anything: {@code immutable=1}
     * creates no -wal/-shm files and takes no locks. When a -wal file is left over (a crash), a plain
     * read-only open is used instead so its committed pages are seen.
     */
    static int probeVersion(Path file) {
        Path wal = file.resolveSibling(file.getFileName() + "-wal");
        boolean walPending;
        try {
            walPending = Files.exists(wal) && Files.size(wal) > 0;
        } catch (java.io.IOException e) {
            walPending = true;
        }
        String url = "jdbc:sqlite:" + file.toUri() + (walPending ? "?mode=ro" : "?mode=ro&immutable=1");
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement();
             ResultSet t = st.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='schema_version'")) {
            if (!t.next()) return 0;
            try (ResultSet rs = st.executeQuery("SELECT MAX(version) FROM schema_version")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read Studio database " + file + ": " + e.getMessage()
                    + ". It is left unchanged; move it away to start with an empty one.", e);
        }
    }

    private static void refuseIfNewer(int version) {
        if (version > MIGRATIONS.size()) {
            throw new NewerDatabaseException("Studio database is version " + version
                    + " but this Studio only knows up to " + MIGRATIONS.size()
                    + ". It was written by a newer Studio; upgrade Studio instead of downgrading."
                    + " The database was not changed.");
        }
    }

    /** The database was written by a newer Studio; it was left untouched. */
    public static final class NewerDatabaseException extends IllegalStateException {
        NewerDatabaseException(String message) {
            super(message);
        }
    }

    /** The copy taken before the last migration in this process, or null. */
    public Path lastBackup() {
        return lastBackup;
    }

    /** The SQL of migration {@code version} (1-based); for tests that simulate an older file. */
    static String migration(int version) {
        return MIGRATIONS.get(version - 1);
    }

    public static int latestVersion() {
        return MIGRATIONS.size();
    }

    public synchronized int schemaVersion() {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
            try (ResultSet rs = st.executeQuery("SELECT MAX(version) FROM schema_version")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private synchronized void migrate() throws SQLException {
        int current = schemaVersion();
        refuseIfNewer(current);
        if (current > 0 && current < MIGRATIONS.size() && backupDir != null) backup(current);
        for (int v = current + 1; v <= MIGRATIONS.size(); v++) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                for (String sql : MIGRATIONS.get(v - 1).split(";")) {
                    if (!sql.isBlank()) st.execute(sql);
                }
                st.execute("INSERT INTO schema_version(version) VALUES (" + v + ")");
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new SQLException("Migration " + v + " failed", e);
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * A consistent copy of the database (including un-checkpointed WAL pages) before migrating from
     * {@code fromVersion}; a failed copy stops the upgrade rather than migrating without one.
     */
    private void backup(int fromVersion) throws SQLException {
        try {
            Files.createDirectories(backupDir);
            String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                    .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now());
            Path target = backupDir.resolve("studio-v" + fromVersion + "-" + stamp + ".db");
            for (int i = 1; Files.exists(target); i++) {
                target = backupDir.resolve("studio-v" + fromVersion + "-" + stamp + "-" + i + ".db");
            }
            try (PreparedStatement ps = conn.prepareStatement("VACUUM INTO ?")) {
                ps.setString(1, target.toAbsolutePath().toString());
                ps.execute();
            }
            lastBackup = target;
            pruneBackups();
        } catch (java.io.IOException e) {
            throw new SQLException("Cannot copy the database before upgrading it: " + e.getMessage(), e);
        }
    }

    private void pruneBackups() throws java.io.IOException {
        try (var files = Files.list(backupDir)) {
            List<Path> all = files.filter(p -> p.getFileName().toString().matches("studio-v\\d+-.*\\.db"))
                    .sorted(java.util.Comparator.comparing((Path p) -> {
                        try {
                            return Files.getLastModifiedTime(p);
                        } catch (java.io.IOException e) {
                            return java.nio.file.attribute.FileTime.fromMillis(0);
                        }
                    }).reversed()).toList();
            for (int i = KEEP_BACKUPS; i < all.size(); i++) Files.deleteIfExists(all.get(i));
        }
    }

    /** Runs an INSERT/UPDATE/DELETE; returns rows affected. */
    public synchronized int update(String sql, Object... params) {
        try (PreparedStatement ps = prepare(sql, params)) {
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** Runs a query; each row becomes a column-name to value map. */
    public synchronized List<Map<String, Object>> query(String sql, Object... params) {
        try (PreparedStatement ps = prepare(sql, params); ResultSet rs = ps.executeQuery()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            int cols = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= cols; i++) row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                rows.add(row);
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** Runs {@code work} in one transaction. */
    public synchronized void transaction(Runnable work) {
        try {
            conn.setAutoCommit(false);
            try {
                work.run();
                conn.commit();
            } catch (RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private PreparedStatement prepare(String sql, Object... params) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
        return ps;
    }

    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
            // closing on shutdown; nothing useful to do
        }
    }
}
