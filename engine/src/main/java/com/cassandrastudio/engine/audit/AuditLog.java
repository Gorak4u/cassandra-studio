package com.cassandrastudio.engine.audit;

import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.store.Database;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Local audit log of every write, DDL, access change and operation: who, when,
 * cluster, node, statement, result (NFR-AUD).
 */
public final class AuditLog {

    public record Entry(long id, String at, String actor, String connectionId, String connectionName,
                        String environment, String node, String category, String action, String detail,
                        String outcome, String error) {}

    public enum Outcome { SUCCESS, FAILED, BLOCKED }

    private final Database db;
    private final String actor;

    public AuditLog(Database db, String actor) {
        this.db = db;
        this.actor = actor;
    }

    public String actor() {
        return actor;
    }

    public void record(ConnectionConfig conn, String node, String category, String action, String detail,
                       Outcome outcome, String error) {
        db.update("""
                INSERT INTO audit_log(at, actor, connection_id, connection_name, environment, node, category, action,
                                      detail, outcome, error) VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
                Instant.now().toString(), actor, conn == null ? null : conn.id(), conn == null ? null : conn.name(),
                conn == null ? null : conn.environment().name(), node, category, action, truncate(detail, 20_000),
                outcome.name(), truncate(error, 4_000));
    }

    /** Most rows one export returns. */
    public static final int MAX_EXPORT = 100_000;

    public List<Entry> search(String connectionId, String text, String since, int limit) {
        return query(connectionId, text, since, Math.max(1, Math.min(limit, 10_000)));
    }

    /** Same filters as {@link #search}, for files (NFR-AUD export): up to {@value #MAX_EXPORT} rows, newest first. */
    public List<Entry> export(String connectionId, String text, String since, int limit) {
        return query(connectionId, text, since, Math.max(1, Math.min(limit, MAX_EXPORT)));
    }

    private List<Entry> query(String connectionId, String text, String since, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM audit_log WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (connectionId != null && !connectionId.isBlank()) { sql.append(" AND connection_id = ?"); params.add(connectionId); }
        if (since != null && !since.isBlank()) { sql.append(" AND at >= ?"); params.add(since); }
        if (text != null && !text.isBlank()) {
            sql.append(" AND (action LIKE ? OR detail LIKE ? OR connection_name LIKE ?)");
            String like = "%" + text + "%";
            params.add(like); params.add(like); params.add(like);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        params.add(limit);
        List<Entry> out = new ArrayList<>();
        for (Map<String, Object> r : db.query(sql.toString(), params.toArray())) {
            out.add(new Entry(((Number) r.get("id")).longValue(), (String) r.get("at"), (String) r.get("actor"),
                    (String) r.get("connection_id"), (String) r.get("connection_name"), (String) r.get("environment"),
                    (String) r.get("node"), (String) r.get("category"), (String) r.get("action"), (String) r.get("detail"),
                    (String) r.get("outcome"), (String) r.get("error")));
        }
        return out;
    }

    static final String[] CSV_COLUMNS = {"id", "at", "actor", "connection_id", "connection_name", "environment", "node",
        "category", "action", "detail", "outcome", "error"};

    /**
     * RFC 4180 CSV with a header row. Cells that a spreadsheet would run as a formula (=, +, -, @,
     * tab, CR) are prefixed with an apostrophe.
     */
    public static String toCsv(List<Entry> entries) {
        StringBuilder sb = new StringBuilder(String.join(",", CSV_COLUMNS)).append("\r\n");
        for (Entry e : entries) {
            Object[] cells = {e.id(), e.at(), e.actor(), e.connectionId(), e.connectionName(), e.environment(), e.node(),
                e.category(), e.action(), e.detail(), e.outcome(), e.error()};
            for (int i = 0; i < cells.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(csvCell(cells[i]));
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    static String csvCell(Object v) {
        if (v == null) return "";
        String s = v.toString();
        if (!(v instanceof Number) && !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
