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

    public List<Entry> search(String connectionId, String text, String since, int limit) {
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
        params.add(Math.max(1, Math.min(limit, 10_000)));
        List<Entry> out = new ArrayList<>();
        for (Map<String, Object> r : db.query(sql.toString(), params.toArray())) {
            out.add(new Entry(((Number) r.get("id")).longValue(), (String) r.get("at"), (String) r.get("actor"),
                    (String) r.get("connection_id"), (String) r.get("connection_name"), (String) r.get("environment"),
                    (String) r.get("node"), (String) r.get("category"), (String) r.get("action"), (String) r.get("detail"),
                    (String) r.get("outcome"), (String) r.get("error")));
        }
        return out;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
