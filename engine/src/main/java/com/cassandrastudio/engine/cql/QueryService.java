package com.cassandrastudio.engine.cql;

import com.cassandrastudio.engine.audit.AuditLog;
import com.cassandrastudio.engine.conn.ConnectionRepository;
import com.cassandrastudio.engine.guard.ActionGuard;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.schema.Describer;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.ApiException;
import com.cassandrastudio.engine.util.Masking;
import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.ColumnDefinition;
import com.datastax.oss.driver.api.core.cql.ExecutionInfo;
import com.datastax.oss.driver.api.core.cql.QueryTrace;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.cql.SimpleStatementBuilder;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.type.codec.registry.CodecRegistry;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs CQL for the editor (CQL-2 ... CQL-8). */
public final class QueryService {
    private static final int HARD_MAX_ROWS = 100_000;
    private static final Pattern USE = Pattern.compile("(?is)^USE\\s+(\"(?:[^\"]|\"\")+\"|\\w+)\\s*$");
    private static final Pattern CONSISTENCY = Pattern.compile("(?is)^(SERIAL\\s+)?CONSISTENCY(?:\\s+(\\w+))?\\s*$");
    private static final Pattern TRACING = Pattern.compile("(?is)^TRACING(?:\\s+(ON|OFF))?\\s*$");

    private final ConnectionRepository connections;
    private final SessionManager sessions;
    private final ActionGuard guard;
    private final AuditLog audit;
    private final Database db;

    public QueryService(ConnectionRepository connections, SessionManager sessions, ActionGuard guard, AuditLog audit, Database db) {
        this.connections = connections;
        this.sessions = sessions;
        this.guard = guard;
        this.audit = audit;
        this.db = db;
    }

    public record QueryRequest(String cql, String keyspace, String node, String consistency, String serialConsistency,
                               Integer pageSize, String pagingState, Boolean tracing, Integer timeoutMs,
                               Boolean stopOnError, Integer maxRows, Boolean confirmed, String confirmName) {}

    public record Column(String name, String type, String keyspace, String table) {}

    public record TraceEvent(String source, long elapsedMicros, String thread, String activity) {}

    public record Trace(String traceId, String coordinator, String requestType, int durationMicros,
                        Map<String, String> parameters, List<TraceEvent> events) {}

    public record StatementResult(int index, int line, String statement, String kind, String status, String error,
                                  List<Column> columns, List<List<Object>> rows, int rowCount, boolean hasMore,
                                  String pagingState, List<String> serverWarnings, List<String> clientWarnings,
                                  String coordinator, long durationMs, Trace trace, Map<String, String> settings,
                                  String message, String keyspace, String consistency) {

        /** The keyspace and consistency the statement ran with, so "next page" repeats them exactly. */
        StatementResult in(String ks, String cl) {
            return new StatementResult(index, line, statement, kind, status, error, columns, rows, rowCount, hasMore,
                    pagingState, serverWarnings, clientWarnings, coordinator, durationMs, trace, settings, message, ks, cl);
        }
    }

    public record ScriptResult(List<StatementResult> results, String keyspace, String consistency, boolean tracing) {}

    public ScriptResult execute(String connectionId, QueryRequest req) {
        if (req.cql() == null || req.cql().isBlank()) throw ApiException.badRequest("Nothing to run");
        ConnectionConfig conn = connections.get(connectionId);
        List<CqlScript.Statement> statements = CqlScript.split(req.cql());
        if (statements.isEmpty()) throw ApiException.badRequest("Nothing to run (only comments?)");

        // One confirmation for the whole script, listing every statement that changes something.
        List<String> changing = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean destructive = false;
        for (CqlScript.Statement st : statements) {
            StatementClassifier.Classification c = StatementClassifier.classify(st.text());
            if (c.changesSomething()) {
                changing.add(Masking.mask(st.text()));
                warnings.addAll(c.warnings());
                destructive |= c.destructive();
            }
        }
        if (!changing.isEmpty()) {
            String summary = changing.size() == 1 ? "run 1 statement that changes data, schema or access"
                    : "run " + changing.size() + " statements that change data, schema or access";
            guard.check(conn, new ActionGuard.Action("cql", summary, changing, warnings, destructive, req.node()),
                    new ActionGuard.Confirmation(Boolean.TRUE.equals(req.confirmed()), req.confirmName()));
        }

        String keyspace = blankToNull(req.keyspace());
        String consistency = req.consistency() == null || req.consistency().isBlank() ? conn.defaultConsistency() : req.consistency();
        String serial = blankToNull(req.serialConsistency());
        boolean tracing = Boolean.TRUE.equals(req.tracing());
        boolean stopOnError = req.stopOnError() == null || req.stopOnError();
        List<StatementResult> results = new ArrayList<>();
        boolean failed = false;
        for (int i = 0; i < statements.size(); i++) {
            CqlScript.Statement st = statements.get(i);
            if (failed && stopOnError) {
                results.add(skipped(i, st));
                continue;
            }
            StatementClassifier.Classification c = StatementClassifier.classify(st.text());
            StatementResult r;
            Matcher m;
            try {
            if ((m = USE.matcher(st.text())).matches()) {
                keyspace = unquote(m.group(1));
                r = setting(i, st, Map.of("keyspace", keyspace), "Now using keyspace " + keyspace);
            } else if ((m = CONSISTENCY.matcher(st.text())).matches()) {
                boolean isSerial = m.group(1) != null;
                String level = m.group(2);
                if (level == null) {
                    r = setting(i, st, Map.of(), (isSerial ? "Serial consistency" : "Consistency") + " is "
                            + (isSerial ? (serial == null ? "SERIAL" : serial) : consistency));
                } else {
                    String upper = parseConsistency(level).name();
                    if (isSerial) serial = upper; else consistency = upper;
                    r = setting(i, st, Map.of(isSerial ? "serialConsistency" : "consistency", upper),
                            (isSerial ? "Serial consistency" : "Consistency") + " set to " + upper);
                }
            } else if ((m = TRACING.matcher(st.text())).matches()) {
                if (m.group(1) != null) tracing = m.group(1).equalsIgnoreCase("ON");
                r = setting(i, st, Map.of("tracing", tracing ? "ON" : "OFF"), "Tracing is " + (tracing ? "ON" : "OFF"));
            } else if (c.verb().equals("DESCRIBE") || c.verb().equals("DESC")) {
                r = describe(i, st, connectionId, keyspace);
            } else {
                r = run(i, st, c, conn, req, keyspace, consistency, serial, tracing);
            }
            } catch (ApiException e) {
                // e.g. unknown consistency level, a pinned node that left, a keyspace session that cannot open:
                // report it on this statement and keep the results of the ones that already ran.
                r = new StatementResult(i, st.line(), st.text(), c.kind().name(), "error", e.getMessage(), List.of(),
                        List.of(), 0, false, null, List.of(), c.warnings(), null, 0, null, null, null, null, null);
            }
            r = r.in(keyspace, consistency);
            failed |= r.status().equals("error");
            results.add(r);
        }
        return new ScriptResult(results, keyspace, consistency, tracing);
    }

    private StatementResult run(int index, CqlScript.Statement st, StatementClassifier.Classification c,
                                ConnectionConfig conn, QueryRequest req, String keyspace, String consistency,
                                String serial, boolean tracing) {
        long start = System.nanoTime();
        String nodeRef = blankToNull(req.node());
        try {
            SessionManager.SessionAndKeyspace sk = sessions.sessionFor(conn.id(), keyspace);
            CqlSession session = sk.session();
            SimpleStatementBuilder b = SimpleStatement.builder(st.text())
                    .setConsistencyLevel(parseConsistency(consistency))
                    .setTracing(tracing)
                    .setTimeout(Duration.ofMillis(req.timeoutMs() == null ? conn.requestTimeoutMs() : req.timeoutMs()));
            if (serial != null) b.setSerialConsistencyLevel(parseConsistency(serial));
            if (req.pageSize() != null && req.pageSize() > 0) b.setPageSize(req.pageSize());
            if (req.pagingState() != null && !req.pagingState().isBlank()) {
                b.setPagingState(ByteBuffer.wrap(Base64.getDecoder().decode(req.pagingState())));
            }
            if (sk.perRequestKeyspace() != null) b.setKeyspace(sk.perRequestKeyspace());
            if (nodeRef != null) b.setNode(SessionManager.findNode(session, nodeRef));
            SimpleStatement stmt = b.build();

            long timeout = (req.timeoutMs() == null ? conn.requestTimeoutMs() : req.timeoutMs()) + 5_000L;
            AsyncResultSet rs = session.executeAsync(stmt).toCompletableFuture().get(timeout, TimeUnit.MILLISECONDS);
            CodecRegistry registry = session.getContext().getCodecRegistry();

            List<Column> columns = new ArrayList<>();
            for (ColumnDefinition cd : rs.getColumnDefinitions()) {
                columns.add(new Column(cd.getName().asInternal(), cd.getType().asCql(false, true),
                        cd.getKeyspace().asInternal(), cd.getTable().asInternal()));
            }
            // "Fetch all" stops at the first page boundary at or past maxRows. Rows are never dropped, so the
            // paging state always continues exactly after the last returned row.
            int maxRows = Math.min(req.maxRows() == null ? 0 : Math.max(req.maxRows(), 1), HARD_MAX_ROWS);
            List<List<Object>> rows = new ArrayList<>();
            List<String> serverWarnings = new ArrayList<>(rs.getExecutionInfo().getWarnings());
            AsyncResultSet page = rs;
            while (true) {
                for (Row row : page.currentPage()) {
                    List<Object> cells = new ArrayList<>(columns.size());
                    for (int k = 0; k < columns.size(); k++) {
                        cells.add(CellCodec.toDisplay(row.getType(k), row.getObject(k), registry));
                    }
                    rows.add(cells);
                }
                if (!page.hasMorePages() || rows.size() >= maxRows) break;
                page = page.fetchNextPage().toCompletableFuture().get(timeout, TimeUnit.MILLISECONDS);
                serverWarnings.addAll(page.getExecutionInfo().getWarnings());
            }
            ExecutionInfo info = page.getExecutionInfo();
            ByteBuffer ps = info.getPagingState();
            Trace trace = tracing ? trace(rs.getExecutionInfo()) : null;
            long ms = (System.nanoTime() - start) / 1_000_000;
            String coordinator = SessionManager.address(info.getCoordinator());
            history(conn, st.text(), keyspace, coordinator, ms, rows.size(), null);
            if (c.changesSomething()) {
                audit.record(conn, coordinator, "cql", c.verb(), Masking.mask(st.text()), AuditLog.Outcome.SUCCESS, null);
            }
            return new StatementResult(index, st.line(), st.text(), c.kind().name(), "ok", null, columns, rows, rows.size(),
                    page.hasMorePages(), ps == null ? null : Base64.getEncoder().encodeToString(copy(ps)),
                    serverWarnings, c.warnings(), coordinator, ms, trace, null,
                    columns.isEmpty() ? "Done" : null, null, null);
        } catch (Exception e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            String err = e instanceof ApiException ? e.getMessage() : Errors.describe(e);
            history(conn, st.text(), keyspace, nodeRef, ms, 0, err);
            if (c.changesSomething()) audit.record(conn, nodeRef, "cql", c.verb(), Masking.mask(st.text()), AuditLog.Outcome.FAILED, err);
            return new StatementResult(index, st.line(), st.text(), c.kind().name(), "error", err, List.of(), List.of(), 0,
                    false, null, List.of(), c.warnings(), nodeRef, ms, null, null, null, null, null);
        }
    }

    private static byte[] copy(ByteBuffer b) {
        byte[] out = new byte[b.remaining()];
        b.duplicate().get(out);
        return out;
    }

    private StatementResult describe(int index, CqlScript.Statement st, String connectionId, String keyspace) {
        long start = System.nanoTime();
        try {
            CqlSession s = sessions.session(connectionId);
            String text = Describer.describe(s, st.text(), keyspace);
            List<List<Object>> rows = new ArrayList<>();
            rows.add(List.of(text));
            return new StatementResult(index, st.line(), st.text(), "READ", "ok", null,
                    List.of(new Column("describe", "text", null, null)), rows, 1, false, null, List.of(), List.of(),
                    null, (System.nanoTime() - start) / 1_000_000, null, null, null, null, null);
        } catch (ApiException e) {
            return new StatementResult(index, st.line(), st.text(), "READ", "error", e.getMessage(), List.of(), List.of(), 0,
                    false, null, List.of(), List.of(), null, (System.nanoTime() - start) / 1_000_000, null, null, null, null, null);
        }
    }

    private static StatementResult setting(int index, CqlScript.Statement st, Map<String, String> settings, String message) {
        return new StatementResult(index, st.line(), st.text(), "CLIENT", "ok", null, List.of(), List.of(), 0, false, null,
                List.of(), List.of(), null, 0, null, settings, message, null, null);
    }

    private static StatementResult skipped(int index, CqlScript.Statement st) {
        return new StatementResult(index, st.line(), st.text(), StatementClassifier.classify(st.text()).kind().name(),
                "skipped", null, List.of(), List.of(), 0, false, null, List.of(), List.of(), null, 0, null, null,
                "Skipped: an earlier statement failed", null, null);
    }

    private static Trace trace(ExecutionInfo info) {
        if (info.getTracingId() == null) return null;
        try {
            QueryTrace t = info.getQueryTrace();
            List<TraceEvent> events = new ArrayList<>();
            t.getEvents().forEach(e -> events.add(new TraceEvent(
                    e.getSourceAddress() == null ? null : e.getSourceAddress().getAddress().getHostAddress(),
                    e.getSourceElapsedMicros(), e.getThreadName(), e.getActivity())));
            return new Trace(t.getTracingId().toString(),
                    t.getCoordinatorAddress() == null ? null : t.getCoordinatorAddress().getAddress().getHostAddress(),
                    t.getRequestType(), t.getDurationMicros(), new LinkedHashMap<>(t.getParameters()), events);
        } catch (RuntimeException e) {
            return new Trace(info.getTracingId().toString(), null, null, -1, Map.of("error", Errors.describe(e)), List.of());
        }
    }

    private void history(ConnectionConfig conn, String statement, String keyspace, String node, long ms, int rows, String error) {
        db.update("""
                INSERT INTO query_history(connection_id, executed_at, statement, keyspace, node, duration_ms, row_count, error)
                VALUES (?,?,?,?,?,?,?,?)""", conn.id(), Instant.now().toString(), Masking.mask(statement), keyspace, node, ms, rows, error);
    }

    public record HistoryEntry(long id, String executedAt, String statement, String keyspace, String node,
                               Long durationMs, Integer rowCount, String error) {}

    public List<HistoryEntry> history(String connectionId, String text, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM query_history WHERE connection_id = ?");
        List<Object> params = new ArrayList<>(List.of(connectionId));
        if (text != null && !text.isBlank()) { sql.append(" AND statement LIKE ?"); params.add("%" + text + "%"); }
        sql.append(" ORDER BY id DESC LIMIT ?");
        params.add(Math.max(1, Math.min(limit, 5_000)));
        List<HistoryEntry> out = new ArrayList<>();
        for (Map<String, Object> r : db.query(sql.toString(), params.toArray())) {
            out.add(new HistoryEntry(((Number) r.get("id")).longValue(), (String) r.get("executed_at"), (String) r.get("statement"),
                    (String) r.get("keyspace"), (String) r.get("node"),
                    r.get("duration_ms") == null ? null : ((Number) r.get("duration_ms")).longValue(),
                    r.get("row_count") == null ? null : ((Number) r.get("row_count")).intValue(), (String) r.get("error")));
        }
        return out;
    }

    public void clearHistory(String connectionId) {
        db.update("DELETE FROM query_history WHERE connection_id = ?", connectionId);
    }

    static ConsistencyLevel parseConsistency(String level) {
        try {
            return DefaultConsistencyLevel.valueOf(level.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown consistency level '" + level + "'");
        }
    }

    private static String unquote(String id) {
        if (id.startsWith("\"") && id.endsWith("\"")) return id.substring(1, id.length() - 1).replace("\"\"", "\"");
        return id.toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** For tests and the UI: which node addresses exist. */
    public static List<String> nodeAddresses(CqlSession s) {
        List<String> out = new ArrayList<>();
        for (Node n : s.getMetadata().getNodes().values()) out.add(SessionManager.address(n));
        return out;
    }
}
