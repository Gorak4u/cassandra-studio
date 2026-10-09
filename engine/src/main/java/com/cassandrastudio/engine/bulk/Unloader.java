package com.cassandrastudio.engine.bulk;

import com.cassandrastudio.engine.cql.Errors;
import com.cassandrastudio.engine.cql.StatementClassifier;
import com.cassandrastudio.engine.jobs.JobContext;
import com.cassandrastudio.engine.util.ApiException;
import com.cassandrastudio.engine.util.Json;
import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ColumnDefinition;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.datastax.oss.driver.api.core.metadata.TokenMap;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.datastax.oss.driver.api.core.metadata.token.Token;
import com.datastax.oss.driver.api.core.metadata.token.TokenRange;
import com.datastax.oss.driver.api.core.servererrors.QueryValidationException;
import com.datastax.oss.driver.api.core.type.DataType;
import com.datastax.oss.driver.api.core.type.codec.TypeCodec;
import com.datastax.oss.driver.internal.core.metadata.token.ByteOrderedTokenFactory;
import com.datastax.oss.driver.internal.core.metadata.token.Murmur3TokenFactory;
import com.datastax.oss.driver.internal.core.metadata.token.RandomTokenFactory;
import com.datastax.oss.driver.internal.core.metadata.token.TokenFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * BLK-1: unloads a table (token-range parallel) or a query (one stream) to a CSV or JSON-lines
 * file. Each range is read with {@code token(pk) > ? AND token(pk) <= ?}, paged by hand so a
 * failed page is retried from its paging state without duplicating rows. Pages are formatted
 * on the reading thread and appended to one unordered writer.
 */
public final class Unloader {
    static final int ATTEMPTS = 3;
    private static final long PROGRESS_EVERY_NANOS = 250_000_000L;

    private final CqlSession session;
    private final String perRequestKeyspace;
    private final BulkOptions.Unload o;
    private final BulkStats stats;
    private final ValueConverter conv;
    private final TableMetadata table;
    private final List<ColumnMetadata> columns;
    private volatile boolean stop;
    private volatile long lastProgress;
    private final AtomicLong remaining;
    private Writer errors;

    /** Validates the request against the schema: 400 for an unknown table/column or a non-SELECT query. */
    public Unloader(CqlSession session, String perRequestKeyspace, BulkOptions.Unload o, BulkStats stats) {
        this.session = session;
        this.perRequestKeyspace = perRequestKeyspace;
        this.o = o;
        this.stats = stats;
        BulkOptions.TextOptions t = o.text();
        this.conv = new ValueConverter(session.getContext().getCodecRegistry(), t.timestampFormat(), t.dateFormat(), t.zone(), t.blobFormat());
        this.remaining = new AtomicLong(o.maxRows() > 0 ? o.maxRows() : Long.MAX_VALUE);
        if (o.tableMode()) {
            table = findTable(session, o.keyspace(), o.table());
            columns = new ArrayList<>();
            if (o.columns().isEmpty()) {
                columns.addAll(table.getColumns().values());
            } else {
                for (String c : o.columns()) columns.add(findColumn(table, c));
            }
        } else {
            table = null;
            columns = List.of();
            String q = o.query().trim();
            var c = StatementClassifier.classify(q);
            if (c.kind() != StatementClassifier.Kind.READ || !q.regionMatches(true, 0, "SELECT", 0, 6)) {
                throw ApiException.badRequest("Only a SELECT query can be unloaded");
            }
        }
    }

    public static TableMetadata findTable(CqlSession s, String ks, String table) {
        var meta = s.getMetadata();
        var keyspace = meta.getKeyspace(CqlIdentifier.fromInternal(ks)).or(() -> meta.getKeyspace(CqlIdentifier.fromCql(ks)));
        if (keyspace.isEmpty()) throw ApiException.badRequest("Keyspace '" + ks + "' does not exist");
        Optional<TableMetadata> t = keyspace.get().getTable(CqlIdentifier.fromInternal(table))
                .or(() -> keyspace.get().getTable(CqlIdentifier.fromCql(table)));
        if (t.isEmpty()) throw ApiException.badRequest("Table '" + ks + "." + table + "' does not exist");
        return t.get();
    }

    public static ColumnMetadata findColumn(TableMetadata t, String name) {
        return t.getColumn(CqlIdentifier.fromInternal(name)).or(() -> t.getColumn(CqlIdentifier.fromCql(name)))
                .orElseThrow(() -> ApiException.badRequest("Column '" + name + "' is not part of " + t.getName().asInternal()));
    }

    /** The SELECT that will run (shown in the job log). */
    public String describe() {
        if (!o.tableMode()) return o.query().trim();
        return select() + " WHERE token(" + pk() + ") > ? AND token(" + pk() + ") <= ?";
    }

    private String select() {
        return "SELECT " + columns.stream().map(c -> c.getName().asCql(true)).collect(Collectors.joining(", "))
                + " FROM " + table.getKeyspace().asCql(true) + "." + table.getName().asCql(true);
    }

    private String pk() {
        return table.getPartitionKey().stream().map(c -> c.getName().asCql(true)).collect(Collectors.joining(", "));
    }

    public Map<String, Object> run(JobContext ctx) throws Exception {
        ctx.log("Writing " + o.format() + (o.gzip() ? " (gzip)" : "") + " to " + o.path());
        ctx.log(describe());
        try (Writer out = new OutputStreamWriter(BulkFiles.openOutput(o.path(), o.overwrite(), o.gzip(), stats.bytes), StandardCharsets.UTF_8)) {
            if (o.tableMode()) {
                runTable(ctx, out);
            } else {
                runQuery(ctx, out);
            }
        } finally {
            stats.finish();
            closeErrors();
        }
        progress(ctx, true);
        if (ctx.cancelled()) throw new CancellationException("cancelled");
        Map<String, Object> result = new LinkedHashMap<>(stats.snapshot());
        String done = String.format("Unloaded %,d rows to %s (%s) in %.1f s, %,d rows/s", stats.rowsWritten.get(), o.path(),
                BulkStats.bytesText(stats.bytes.get()), stats.elapsedSeconds(), stats.rate());
        ctx.log(done);
        if (stats.rangesFailed.get() > 0) {
            throw new IllegalStateException(String.format("%s; %d of %d token ranges failed, see %s", done,
                    stats.rangesFailed.get(), stats.rangesTotal, stats.errorFile));
        }
        return result;
    }

    // ---- table: token ranges ----------------------------------------------------------------

    private void runTable(JobContext ctx, Writer out) throws Exception {
        List<DataType> types = columns.stream().map(ColumnMetadata::getType).toList();
        List<String> names = columns.stream().map(c -> c.getName().asInternal()).toList();
        writeHeader(out, names);
        Optional<TokenMap> tm = session.getMetadata().getTokenMap();
        TokenFactory f = tm.map(m -> factory(m.getPartitionerName())).orElse(null);
        if (tm.isEmpty() || f == null) {
            ctx.log("Token metadata is not available: reading the table in one stream");
            stats.rangesTotal = 1;
            Statement<?> st = SimpleStatement.newInstance(select());
            readStream(ctx, out, st, types, names, null);
            stats.rangesDone.incrementAndGet();
            return;
        }
        List<TokenRange> ranges = plan(tm.get().getTokenRanges(), f, o.concurrency());
        stats.rangesTotal = ranges.size();
        ctx.log("Reading " + ranges.size() + " token ranges with concurrency " + o.concurrency()
                + ", page size " + o.pageSize() + ", consistency " + o.consistency());
        PreparedStatement both = session.prepare(select() + " WHERE token(" + pk() + ") > ? AND token(" + pk() + ") <= ?");
        PreparedStatement lower = session.prepare(select() + " WHERE token(" + pk() + ") > ?");
        Token min = f.minToken();
        ConcurrentLinkedQueue<TokenRange> queue = new ConcurrentLinkedQueue<>(ranges);
        List<Throwable> fatal = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> workers = new ArrayList<>();
            for (int i = 0; i < o.concurrency(); i++) {
                workers.add(pool.submit(() -> {
                    TokenRange r;
                    while (!stop && !ctx.cancelled() && (r = queue.poll()) != null) {
                        Statement<?> st = r.getEnd().equals(min)
                                ? lower.bind().setToken(0, r.getStart()).setRoutingToken(r.getStart())
                                : both.bind().setToken(0, r.getStart()).setToken(1, r.getEnd()).setRoutingToken(r.getEnd());
                        try {
                            readStream(ctx, out, st, types, names, r);
                            stats.rangesDone.incrementAndGet();
                        } catch (IOException e) {
                            synchronized (fatal) {
                                fatal.add(e);
                            }
                            stop = true;
                        } catch (CancellationException e) {
                            return;
                        } catch (RuntimeException e) {
                            stats.rangesFailed.incrementAndGet();
                            stats.rangesDone.incrementAndGet();
                            error("range (" + r.getStart() + ", " + r.getEnd() + "]: " + Errors.describe(e));
                        }
                        progress(ctx, false);
                    }
                }));
            }
            ctx.onCancel(() -> stop = true);
            for (Future<?> w : workers) w.get();
        }
        if (!fatal.isEmpty()) throw new IOException("Could not write " + o.path() + ": " + fatal.get(0).getMessage(), fatal.get(0));
    }

    /** The ring's ranges, unwrapped, split so there are at least ~8 per worker. */
    static List<TokenRange> plan(java.util.Set<TokenRange> ring, TokenFactory f, int concurrency) {
        Token min = f.minToken();
        List<TokenRange> base = new ArrayList<>();
        for (TokenRange r : ring) {
            if (r.getStart().equals(r.getEnd())) {
                // A single-token ring: (t, t] is the whole ring.
                base.add(f.range(r.getStart(), min));
                base.add(f.range(min, r.getEnd()));
            } else {
                base.addAll(r.unwrap());
            }
        }
        int per = (int) Math.max(1, Math.ceil(concurrency * 8.0 / Math.max(1, base.size())));
        List<TokenRange> out = new ArrayList<>();
        for (TokenRange r : base) {
            if (per > 1) out.addAll(r.splitEvenly(per));
            else out.add(r);
        }
        return out;
    }

    static TokenFactory factory(String partitioner) {
        if (partitioner == null) return null;
        if (partitioner.endsWith("Murmur3Partitioner")) return new Murmur3TokenFactory();
        if (partitioner.endsWith("RandomPartitioner")) return new RandomTokenFactory();
        if (partitioner.endsWith("ByteOrderedPartitioner")) return new ByteOrderedTokenFactory();
        return null;
    }

    // ---- query: one stream ------------------------------------------------------------------

    private void runQuery(JobContext ctx, Writer out) throws Exception {
        stats.rangesTotal = 0;
        SimpleStatement st = SimpleStatement.newInstance(o.query().trim().replaceAll(";\\s*$", ""));
        if (perRequestKeyspace != null) st = st.setKeyspace(perRequestKeyspace);
        readStream(ctx, out, st, null, null, null);
    }

    // ---- reading pages ----------------------------------------------------------------------

    /** Reads one statement page by page; {@code types} null = take them from the first page (query mode). */
    private void readStream(JobContext ctx, Writer out, Statement<?> base, List<DataType> types, List<String> names,
                            TokenRange range) throws IOException {
        Statement<?> st = base.setPageSize(o.pageSize()).setConsistencyLevel(o.consistency())
                .setTimeout(Duration.ofMillis(o.timeoutMs()));
        ByteBuffer paging = null;
        List<TypeCodec<Object>> codecs = types == null ? null : codecs(types);
        while (!stop) {
            if (ctx.cancelled()) throw new CancellationException("cancelled");
            ResultSet rs = execute(paging == null ? st : st.setPagingState(paging), range);
            if (types == null) {
                types = new ArrayList<>();
                names = new ArrayList<>();
                for (ColumnDefinition d : rs.getColumnDefinitions()) {
                    types.add(d.getType());
                    names.add(d.getName().asInternal());
                }
                codecs = codecs(types);
                writeHeader(out, names);
            }
            int n = rs.getAvailableWithoutFetching();
            StringBuilder chunk = new StringBuilder(Math.max(256, n * 80));
            long rows = 0;
            for (int i = 0; i < n; i++) {
                Row row = rs.one();
                if (remaining.getAndDecrement() <= 0) {
                    stop = true;
                    break;
                }
                appendRow(chunk, row, types, names, codecs);
                rows++;
            }
            stats.rowsRead.addAndGet(n);
            synchronized (out) {
                out.write(chunk.toString());
            }
            stats.rowsWritten.addAndGet(rows);
            progress(ctx, false);
            paging = rs.getExecutionInfo().getPagingState();
            if (paging == null) break;
        }
    }

    private ResultSet execute(Statement<?> st, TokenRange range) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                return session.execute(st);
            } catch (QueryValidationException e) {
                throw e;
            } catch (RuntimeException e) {
                last = e;
                if (attempt < ATTEMPTS) {
                    try {
                        Thread.sleep(250L * attempt * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new CancellationException("cancelled");
                    }
                }
            }
        }
        throw last;
    }

    private List<TypeCodec<Object>> codecs(List<DataType> types) {
        List<TypeCodec<Object>> out = new ArrayList<>();
        for (DataType t : types) out.add(conv.registry().codecFor(t));
        return out;
    }

    private void appendRow(StringBuilder sb, Row row, List<DataType> types, List<String> names, List<TypeCodec<Object>> codecs) {
        if (o.format() == BulkOptions.Format.JSON) {
            ObjectNode node = Json.MAPPER.createObjectNode();
            for (int i = 0; i < types.size(); i++) node.set(names.get(i), conv.toJson(types.get(i), row.get(i, codecs.get(i))));
            try {
                sb.append(Json.MAPPER.writeValueAsString(node)).append('\n');
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return;
        }
        char d = o.text().delimiter();
        for (int i = 0; i < types.size(); i++) {
            if (i > 0) sb.append(d);
            Object v = row.get(i, codecs.get(i));
            String text = conv.format(types.get(i), v);
            Csv.appendField(sb, text, v == null, d, o.text().nullString());
        }
        sb.append('\n');
    }

    private void writeHeader(Writer out, List<String> names) throws IOException {
        if (o.format() != BulkOptions.Format.CSV || !o.text().header()) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(o.text().delimiter());
            Csv.appendField(sb, names.get(i), false, o.text().delimiter(), o.text().nullString());
        }
        synchronized (out) {
            out.write(sb.append('\n').toString());
        }
    }

    private void progress(JobContext ctx, boolean force) {
        long now = System.nanoTime();
        if (!force && now - lastProgress < PROGRESS_EVERY_NANOS) return;
        lastProgress = now;
        Double frac = null;
        if (stats.rangesTotal > 0) frac = (double) stats.rangesDone.get() / stats.rangesTotal;
        if (o.maxRows() > 0) frac = Math.max(frac == null ? 0 : frac, (double) stats.rowsWritten.get() / o.maxRows());
        String ranges = stats.rangesTotal > 0 ? stats.rangesDone.get() + "/" + stats.rangesTotal + " ranges · " : "";
        ctx.progress(frac, String.format("%s%,d rows · %,d rows/s · %s", ranges, stats.rowsWritten.get(), stats.rate(),
                BulkStats.bytesText(stats.bytes.get())));
    }

    private synchronized void error(String line) {
        try {
            if (errors == null) {
                Path p = BulkFiles.sibling(o.path(), ".errors.log");
                errors = Files.newBufferedWriter(p, StandardCharsets.UTF_8);
                stats.errorFile = p.toString();
            }
            errors.write(line + "\n");
            errors.flush();
        } catch (IOException e) {
            // the job still reports the failure count
        }
    }

    private synchronized void closeErrors() {
        if (errors == null) return;
        try {
            errors.close();
        } catch (IOException e) {
            // ignore
        }
    }
}
