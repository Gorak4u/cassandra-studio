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
 * Studio's own database: connections, folders, history, audit. SQLite for the desktop app and a single
 * Studio Server; PostgreSQL ({@code jdbc:postgresql://...}) for Studio Server with several instances
 * (SRV-7). Migrations and statements are written once in plain SQL; {@link SqlDialect} adapts the
 * SQLite-only bits (AUTOINCREMENT, 64-bit INTEGER, INSERT OR REPLACE/IGNORE, case-insensitive LIKE)
 * for PostgreSQL.
 *
 * <p>Schema changes are numbered migrations applied in order and recorded in
 * {@code schema_version} (NFR-DATA). A database written by a newer Studio is
 * refused rather than silently misread, so a downgrade can never corrupt it.
 * On SQLite a copy is taken before migrating; on PostgreSQL the operator's own
 * backup (pg_dump, snapshots) covers upgrades (docs/guide/server.md).
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
            """,
            // 5: T1 Studio Server: tamper-evident audit chain (SRV-6), API tokens (SRV-1), leader lease and
            //    instances (SRV-7), encrypted connection secrets shared by all instances (SRV-3)
            """
            CREATE TABLE audit_chain (
              id INTEGER PRIMARY KEY,
              prev_hash TEXT NOT NULL,
              hash TEXT NOT NULL
            );
            CREATE TABLE server_tokens (
              id TEXT PRIMARY KEY,
              user_name TEXT NOT NULL,
              label TEXT,
              token_hash TEXT NOT NULL UNIQUE,
              created_at TEXT NOT NULL,
              last_used_at TEXT,
              revoked_at TEXT
            );
            CREATE TABLE server_leader (
              name TEXT PRIMARY KEY,
              holder TEXT NOT NULL,
              since_ms INTEGER NOT NULL,
              expires_ms INTEGER NOT NULL
            );
            CREATE TABLE server_instances (
              id TEXT PRIMARY KEY,
              host TEXT,
              version TEXT,
              started_ms INTEGER NOT NULL,
              heartbeat_ms INTEGER NOT NULL
            );
            CREATE TABLE server_secrets (
              name TEXT PRIMARY KEY,
              value TEXT NOT NULL,
              updated_at TEXT NOT NULL
            );
            """
    );

    /** Copies taken before a migration that are kept in {@code <data dir>/backups}; older ones are deleted. */
    static final int KEEP_BACKUPS = 5;
    public static final String FILE_NAME = "studio.db";

    /** Which SQL engine holds the store. */
    public enum Dialect { SQLITE, POSTGRESQL }

    /** Advisory lock held on PostgreSQL while one instance migrates, so two starting instances never both do. */
    private static final long MIGRATION_LOCK = 0x5354_5544_494f_0001L;

    private Connection conn;
    /** Where pre-migration copies go; null for in-memory databases and PostgreSQL. */
    private final Path backupDir;
    private volatile Path lastBackup;
    private final Dialect dialect;
    private final String url;
    private final java.util.Properties props;
    private final SqlDialect sql;

    private Database(Connection conn, Path backupDir, Dialect dialect, String url, java.util.Properties props) {
        this.conn = conn;
        this.backupDir = backupDir;
        this.dialect = dialect;
        this.url = url;
        this.props = props;
        this.sql = new SqlDialect(dialect, this::primaryKey);
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

    /**
     * Opens a store by JDBC URL (Studio Server): {@code jdbc:postgresql://host:5432/db} for a shared,
     * highly available store, or {@code jdbc:sqlite:/path/studio.db} for a single server. User and password
     * apply to PostgreSQL. A SQLite file gets the same downgrade refusal and pre-migration copy as the desktop.
     */
    public static Database connect(String jdbcUrl, String user, String password) {
        if (jdbcUrl.startsWith("jdbc:sqlite:")) {
            String path = jdbcUrl.substring("jdbc:sqlite:".length());
            if (path.isBlank() || path.startsWith(":memory:")) return inMemory();
            Path file = Path.of(path).toAbsolutePath();
            Path dir = file.getParent();
            try {
                Files.createDirectories(dir);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Cannot create data directory " + dir, e);
            }
            if (Files.exists(file)) refuseIfNewer(probeVersion(file));
            return open("jdbc:sqlite:" + file, dir.resolve("backups"));
        }
        if (!jdbcUrl.startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException("Store URL must start with jdbc:postgresql: or jdbc:sqlite:, got " + jdbcUrl);
        }
        java.util.Properties p = new java.util.Properties();
        if (user != null && !user.isBlank()) p.setProperty("user", user);
        if (password != null) p.setProperty("password", password);
        // Strings bound to numeric columns are typed by the server, as SQLite does; TCP keepalive notices a dead peer.
        p.setProperty("stringtype", "unspecified");
        p.setProperty("tcpKeepAlive", "true");
        p.setProperty("ApplicationName", "cassandra-studio");
        p.setProperty("connectTimeout", "10");
        try {
            Connection c = DriverManager.getConnection(jdbcUrl, p);
            Database db = new Database(c, null, Dialect.POSTGRESQL, jdbcUrl, p);
            try {
                db.migrate();
            } catch (SQLException | RuntimeException e) {
                db.close();
                throw e;
            }
            return db;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot open Studio store " + jdbcUrl + ": " + e.getMessage(), e);
        }
    }

    private static Database open(String url, Path backupDir) {
        try {
            Connection c = DriverManager.getConnection(url);
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                st.execute("PRAGMA journal_mode = WAL");
                st.execute("PRAGMA busy_timeout = 5000");
            }
            Database db = new Database(c, backupDir, Dialect.SQLITE, url, null);
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

    public Dialect dialect() {
        return dialect;
    }

    /** Short description for status pages: "PostgreSQL (jdbc:postgresql://db:5432/studio)" or "SQLite". */
    public String description() {
        return dialect == Dialect.POSTGRESQL ? "PostgreSQL (" + url.replaceAll("[?;].*$", "") + ")" : "SQLite";
    }

    /**
     * Inside {@link #transaction}: serialises this transaction with every other one taking the same lock,
     * across all Studio Server instances (PostgreSQL advisory transaction lock). SQLite has one writer at a
     * time already, so this is a no-op there.
     */
    public void lockInTransaction(long key) {
        if (dialect == Dialect.POSTGRESQL) query("SELECT 1 AS locked FROM (SELECT pg_advisory_xact_lock(?)) l", key);
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
        if (dialect == Dialect.POSTGRESQL) {
            try (Statement st = conn.createStatement()) {
                st.execute("SELECT pg_advisory_lock(" + MIGRATION_LOCK + ")");
            }
            try {
                migrateLocked();
            } finally {
                try (Statement st = conn.createStatement()) {
                    st.execute("SELECT pg_advisory_unlock(" + MIGRATION_LOCK + ")");
                }
            }
        } else {
            migrateLocked();
        }
    }

    private void migrateLocked() throws SQLException {
        int current = schemaVersion();
        refuseIfNewer(current);
        if (current > 0 && current < MIGRATIONS.size() && backupDir != null) backup(current);
        for (int v = current + 1; v <= MIGRATIONS.size(); v++) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                for (String stmt : MIGRATIONS.get(v - 1).split(";")) {
                    if (!stmt.isBlank()) st.execute(sql.ddl(stmt));
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
        return withRetry(() -> {
            try (PreparedStatement ps = prepare(sql, params)) {
                return ps.executeUpdate();
            }
        });
    }

    /** Runs a query (or an INSERT ... RETURNING); each row becomes a column-name to value map. */
    public synchronized List<Map<String, Object>> query(String sql, Object... params) {
        return withRetry(() -> {
            try (PreparedStatement ps = prepare(sql, params); ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                int cols = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= cols; i++) row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                    rows.add(row);
                }
                return rows;
            }
        });
    }

    private interface SqlWork<T> {
        T run() throws SQLException;
    }

    /**
     * PostgreSQL only: a connection lost outside a transaction (database restart or failover) is
     * reopened once and the statement retried; inside a transaction the error is passed on.
     */
    private <T> T withRetry(SqlWork<T> work) {
        try {
            return work.run();
        } catch (SQLException e) {
            if (dialect == Dialect.POSTGRESQL && isConnectionLost(e) && inAutoCommit()) {
                try {
                    reconnect();
                    return work.run();
                } catch (SQLException again) {
                    throw new IllegalStateException(again.getMessage(), again);
                }
            }
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private boolean inAutoCommit() {
        try {
            return conn.isClosed() || conn.getAutoCommit();
        } catch (SQLException e) {
            return true;
        }
    }

    static boolean isConnectionLost(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s && s.getSQLState() != null && s.getSQLState().startsWith("08")) return true;
            if (t instanceof java.io.IOException) return true;
        }
        return false;
    }

    private void reconnect() throws SQLException {
        try {
            conn.close();
        } catch (SQLException ignored) {
            // already broken
        }
        conn = DriverManager.getConnection(url, props);
    }

    /** True when a trivial statement succeeds (health checks). */
    public synchronized boolean ping() {
        try {
            query("SELECT 1 AS ok");
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The primary-key columns of a table, for PostgreSQL's ON CONFLICT target. */
    private List<String> primaryKey(String table) {
        java.util.TreeMap<Integer, String> bySeq = new java.util.TreeMap<>();
        try (ResultSet rs = conn.getMetaData().getPrimaryKeys(null, null, table.toLowerCase(java.util.Locale.ROOT))) {
            while (rs.next()) bySeq.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the primary key of " + table + ": " + e.getMessage(), e);
        }
        return List.copyOf(bySeq.values());
    }

    /** Runs {@code work} in one transaction; a transaction already open on this thread is joined. */
    public synchronized void transaction(Runnable work) {
        if (!inAutoCommit()) {
            work.run();
            return;
        }
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

    private PreparedStatement prepare(String statement, Object... params) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(sql.dml(statement));
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
