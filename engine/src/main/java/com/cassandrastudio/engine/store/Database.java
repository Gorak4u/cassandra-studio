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
            """
    );

    private final Connection conn;

    private Database(Connection conn) {
        this.conn = conn;
    }

    public static Database open(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
            return open("jdbc:sqlite:" + dataDir.resolve("studio.db").toAbsolutePath());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot create data directory " + dataDir, e);
        }
    }

    public static Database inMemory() {
        return open("jdbc:sqlite::memory:");
    }

    private static Database open(String url) {
        try {
            Connection c = DriverManager.getConnection(url);
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                st.execute("PRAGMA journal_mode = WAL");
                st.execute("PRAGMA busy_timeout = 5000");
            }
            Database db = new Database(c);
            db.migrate();
            return db;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot open Studio database " + url, e);
        }
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
        if (current > MIGRATIONS.size()) {
            throw new IllegalStateException("Studio database is version " + current
                    + " but this Studio only knows up to " + MIGRATIONS.size()
                    + ". It was written by a newer Studio; upgrade Studio instead of downgrading.");
        }
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
