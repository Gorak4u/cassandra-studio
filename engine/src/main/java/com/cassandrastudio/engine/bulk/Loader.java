package com.cassandrastudio.engine.bulk;

import com.cassandrastudio.engine.cql.Errors;
import com.cassandrastudio.engine.jobs.JobContext;
import com.cassandrastudio.engine.util.ApiException;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.DriverTimeoutException;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BatchStatement;
import com.datastax.oss.driver.api.core.cql.BatchType;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.BoundStatementBuilder;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.datastax.oss.driver.api.core.servererrors.CoordinatorException;
import com.datastax.oss.driver.api.core.servererrors.QueryValidationException;
import com.datastax.oss.driver.api.core.type.DataType;
import com.datastax.oss.driver.api.core.type.DataTypes;
import com.datastax.oss.driver.api.core.type.codec.TypeCodec;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * BLK-2: loads a CSV or JSON-lines file into a table. Records are converted with
 * {@link ValueConverter} and written with prepared INSERTs, asynchronously with at most
 * {@code concurrency} requests in flight, optionally rate limited, optionally grouped into
 * unlogged batches of rows of the same partition. Rows that cannot be converted or written
 * are rejected to a side file with the reason; the job stops after {@code maxErrors}.
 */
public final class Loader {
    static final int SAMPLE_ROWS = 20;
    private static final int ATTEMPTS = 3;
    private static final long PROGRESS_EVERY_NANOS = 250_000_000L;

    /** A table column fed from a file column. */
    record Target(ColumnMetadata column, int index, String key, DataType type, TypeCodec<Object> codec, boolean pk) {}

    private final CqlSession session;
    private final BulkOptions.Load o;
    private final BulkStats stats;
    private final ValueConverter conv;
    private final TableMetadata table;
    private final boolean gzip;
    private final List<String> fileColumns;
    private final List<Target> targets = new ArrayList<>();
    private final int ttlIndex;
    private final int tsIndex;
    private final String cql;
    private final boolean unsetNulls;
    private final Long estimatedRows;
    private volatile boolean stop;
    private volatile long lastProgress;
    private Writer rejectData;
    private Writer rejectLog;
    private final List<String> rejectSamples = new ArrayList<>();

    /** Plans the load: reads the file's header, resolves the mapping, builds the INSERT. 400 on any problem. */
    public Loader(CqlSession session, BulkOptions.Load o, BulkStats stats) {
        this.session = session;
        this.o = o;
        this.stats = stats;
        BulkOptions.TextOptions t = o.text();
        this.conv = new ValueConverter(session.getContext().getCodecRegistry(), t.timestampFormat(), t.dateFormat(), t.zone(), t.blobFormat());
        BulkFiles.checkSource(o.path());
        this.table = Unloader.findTable(session, o.keyspace(), o.table());
        rejectCounters(table);
        this.gzip = o.gzip() != null ? o.gzip() : BulkFiles.looksGzip(o.path());
        this.fileColumns = fileColumns(o.path(), o.format(), gzip, t);
        List<BulkOptions.Mapping> mapping = o.mapping().isEmpty() ? autoMapping(fileColumns, table) : o.mapping();
        Set<String> seen = new HashSet<>();
        for (BulkOptions.Mapping m : mapping) {
            ColumnMetadata c = Unloader.findColumn(table, m.column());
            if (!seen.add(c.getName().asInternal())) throw ApiException.badRequest("Column '" + m.column() + "' is mapped twice");
            int idx = sourceIndex(m.source());
            targets.add(new Target(c, idx, m.source(), c.getType(), conv.registry().codecFor(c.getType()),
                    table.getPartitionKey().contains(c) || table.getClusteringColumns().containsKey(c)));
        }
        if (targets.isEmpty()) throw ApiException.badRequest("No file column is mapped to a table column");
        List<String> missing = new ArrayList<>();
        for (ColumnMetadata c : table.getPrimaryKey()) {
            if (!seen.contains(c.getName().asInternal())) missing.add(c.getName().asInternal());
        }
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("Primary key column" + (missing.size() > 1 ? "s " : " ") + String.join(", ", missing)
                    + (missing.size() > 1 ? " are" : " is") + " not mapped to a file column");
        }
        this.ttlIndex = o.ttlField() == null ? -1 : sourceIndex(o.ttlField());
        this.tsIndex = o.timestampField() == null ? -1 : sourceIndex(o.timestampField());
        this.cql = buildCql();
        this.unsetNulls = session.getContext().getProtocolVersion().getCode() >= 4;
        this.estimatedRows = estimateRows(o.path(), gzip, o.format() == BulkOptions.Format.CSV && t.header());
        stats.estimatedRows = estimatedRows;
        try {
            stats.totalBytes = Files.size(o.path());
        } catch (IOException e) {
            stats.totalBytes = 0;
        }
    }

    static void rejectCounters(TableMetadata t) {
        for (ColumnMetadata c : t.getColumns().values()) {
            if (c.getType().equals(DataTypes.COUNTER)) {
                throw ApiException.badRequest(t.getKeyspace().asInternal() + "." + t.getName().asInternal()
                        + " is a counter table. Counters cannot be loaded: they can only be incremented with"
                        + " UPDATE ... SET " + c.getName().asCql(true) + " = " + c.getName().asCql(true) + " + ?.");
            }
        }
    }

    private int sourceIndex(String source) {
        if (o.format() == BulkOptions.Format.JSON) return -1;
        int i = fileColumns.indexOf(source);
        if (i < 0) {
            for (int j = 0; j < fileColumns.size(); j++) if (fileColumns.get(j).equalsIgnoreCase(source)) return j;
            throw ApiException.badRequest("File column '" + source + "' is not in the file (it has: "
                    + String.join(", ", fileColumns.subList(0, Math.min(30, fileColumns.size()))) + ")");
        }
        return i;
    }

    private String buildCql() {
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(table.getKeyspace().asCql(true)).append('.')
                .append(table.getName().asCql(true)).append(" (")
                .append(targets.stream().map(x -> x.column().getName().asCql(true)).collect(Collectors.joining(", ")))
                .append(") VALUES (").append(targets.stream().map(x -> "?").collect(Collectors.joining(", "))).append(')');
        List<String> using = new ArrayList<>();
        if (o.ttlSeconds() != null) using.add("TTL " + o.ttlSeconds());
        if (o.ttlField() != null) using.add("TTL ?");
        if (o.timestampMicros() != null) using.add("TIMESTAMP " + o.timestampMicros());
        if (o.timestampField() != null) using.add("TIMESTAMP ?");
        if (!using.isEmpty()) sb.append(" USING ").append(String.join(" AND ", using));
        return sb.toString();
    }

    public String cql() {
        return cql;
    }

    public Long estimatedRows() {
        return estimatedRows;
    }

    /** What the confirm dialog shows: the INSERT and where the rows come from. */
    public List<String> preview() {
        List<String> p = new ArrayList<>();
        p.add(cql);
        String est = estimatedRows == null ? "" : String.format(", ~%,d rows", estimatedRows);
        p.add("-- from " + o.path() + " (" + o.format() + (gzip ? ", gzip" : "") + ", " + BulkStats.bytesText(stats.totalBytes) + est + ")");
        p.add("-- mapping: " + targets.stream().map(x -> x.column().getName().asInternal() + " <- " + x.key()).collect(Collectors.joining(", "))
                + (o.ttlField() != null ? "; TTL <- " + o.ttlField() : "") + (o.timestampField() != null ? "; WRITETIME <- " + o.timestampField() : ""));
        p.add("-- " + (o.batchSize() > 1 ? "unlogged batches of up to " + o.batchSize() + " rows of the same partition" : "one INSERT per row")
                + ", " + o.concurrency() + " in flight, " + (o.rateLimit() > 0 ? "at most " + o.rateLimit() + " rows/s" : "no rate limit")
                + ", consistency " + o.consistency() + ", stop after " + (o.maxErrors() < 0 ? "no limit of" : String.valueOf(o.maxErrors())) + " errors");
        return p;
    }

    public List<String> warnings() {
        List<String> w = new ArrayList<>();
        w.add("Existing rows with the same primary key are overwritten (INSERT is an upsert).");
        Set<String> mapped = targets.stream().map(x -> x.column().getName().asInternal()).collect(Collectors.toSet());
        List<String> notMapped = table.getColumns().values().stream().map(c -> c.getName().asInternal())
                .filter(n -> !mapped.contains(n)).toList();
        if (!notMapped.isEmpty()) w.add("Not loaded (left unchanged): " + String.join(", ", notMapped));
        return w;
    }

    // ---- run --------------------------------------------------------------------------------

    public Map<String, Object> run(JobContext ctx) throws Exception {
        stats.dryRun = o.dryRun();
        ctx.log((o.dryRun() ? "Dry run (nothing is written): " : "") + cql);
        PreparedStatement ps = session.prepare(cql);
        Semaphore inflight = new Semaphore(o.concurrency());
        RateLimiter limiter = o.rateLimit() > 0 ? new RateLimiter(o.rateLimit()) : null;
        Batcher batcher = new Batcher(inflight, limiter);
        ctx.onCancel(() -> stop = true);
        AtomicLong rawBytes = stats.bytes;
        try (InputStream in = BulkFiles.openInput(o.path(), gzip, rawBytes)) {
            if (o.format() == BulkOptions.Format.CSV) {
                Csv.Reader r = new Csv.Reader(new InputStreamReader(in, StandardCharsets.UTF_8), o.text().delimiter());
                if (o.text().header()) r.next();
                while (!stop && !ctx.cancelled()) {
                    Csv.Record rec;
                    try {
                        rec = r.next();
                    } catch (Csv.FormatException e) {
                        stats.rowsRead.incrementAndGet();
                        reject(e.line, "", e.getMessage().replaceFirst("^line \\d+: ", ""));
                        continue;
                    }
                    if (rec == null) break;
                    stats.rowsRead.incrementAndGet();
                    handle(ctx, ps, batcher, rec.line(), new Fields.CsvFields(rec), () -> Csv.line(rec, o.text().delimiter(), o.text().nullString()));
                }
            } else {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
                String line;
                long n = 0;
                while (!stop && !ctx.cancelled() && (line = r.readLine()) != null) {
                    n++;
                    if (line.isBlank()) continue;
                    stats.rowsRead.incrementAndGet();
                    JsonNode node;
                    try {
                        node = ValueConverter.JSON.readTree(line);
                    } catch (IOException e) {
                        reject(n, line, "not valid JSON");
                        continue;
                    }
                    if (node == null || !node.isObject()) {
                        reject(n, line, "not a JSON object");
                        continue;
                    }
                    final String raw = line;
                    handle(ctx, ps, batcher, n, new Fields.JsonFields(node), () -> raw);
                }
            }
            batcher.finish();
        } catch (Exception e) {
            batcher.abort();
            throw e;
        } finally {
            stats.finish();
            closeRejects();
        }
        progress(ctx, true);
        if (ctx.cancelled()) throw new CancellationException("cancelled");
        String verb = o.dryRun() ? "Validated" : "Loaded";
        String done = String.format("%s %,d of %,d rows into %s.%s in %.1f s, %,d rows/s; %,d rejected", verb, stats.rowsWritten.get(),
                stats.rowsRead.get(), table.getKeyspace().asInternal(), table.getName().asInternal(), stats.elapsedSeconds(), stats.rate(),
                stats.rejected.get());
        ctx.log(done);
        if (stats.rejectFile != null) ctx.log("Rejected rows: " + stats.rejectFile);
        if (tooManyErrors()) {
            throw new IllegalStateException(String.format("Stopped after %,d rejected rows (max errors %d); %,d rows were written. See %s",
                    stats.rejected.get(), o.maxErrors(), stats.rowsWritten.get(), stats.rejectFile));
        }
        Map<String, Object> result = new LinkedHashMap<>(stats.snapshot());
        synchronized (this) {
            result.put("rejectSamples", List.copyOf(rejectSamples));
        }
        return result;
    }

    private boolean tooManyErrors() {
        return o.maxErrors() >= 0 && stats.rejected.get() > o.maxErrors();
    }

    private void handle(JobContext ctx, PreparedStatement ps, Batcher batcher, long line, Fields f, Supplier<String> raw) throws InterruptedException {
        BoundStatement bs;
        try {
            bs = bind(ps, f);
        } catch (IllegalArgumentException e) {
            reject(line, raw.get(), e.getMessage());
            return;
        }
        if (o.dryRun()) {
            stats.rowsWritten.incrementAndGet();
        } else {
            batcher.add(new Pending(bs, line, raw));
        }
        progress(ctx, false);
    }

    /** Converts one record; IllegalArgumentException names the column and why. */
    BoundStatement bind(PreparedStatement ps, Fields f) {
        if (f instanceof Fields.CsvFields c && c.rec().fields().size() != fileColumns.size()) {
            throw new IllegalArgumentException("has " + c.rec().fields().size() + " fields, expected " + fileColumns.size());
        }
        BoundStatementBuilder b = ps.boundStatementBuilder();
        int i = 0;
        for (Target t : targets) {
            Object v;
            try {
                v = f.value(this, t.index(), t.key(), t.type());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("column " + t.column().getName().asInternal() + ": " + e.getMessage());
            }
            if (v == null) {
                if (t.pk()) throw new IllegalArgumentException("primary key column " + t.column().getName().asInternal() + " is empty");
                if (!unsetNulls) b = b.setToNull(i);
            } else {
                b = b.set(i, v, t.codec());
            }
            i++;
        }
        if (o.ttlField() != null) {
            Object v = f.value(this, ttlIndex, o.ttlField(), DataTypes.INT);
            if (v == null) throw new IllegalArgumentException("TTL field " + o.ttlField() + " is empty");
            if ((Integer) v < 0) throw new IllegalArgumentException("TTL field " + o.ttlField() + " is negative");
            b = b.setInt(i++, (Integer) v);
        }
        if (o.timestampField() != null) {
            String text = f.text(this, tsIndex, o.timestampField());
            if (text == null) throw new IllegalArgumentException("timestamp field " + o.timestampField() + " is empty");
            b = b.setLong(i, writetime(text.trim()));
        }
        return b.setConsistencyLevel(o.consistency()).setTimeout(Duration.ofMillis(o.timeoutMs())).build().setIdempotent(true);
    }

    /** USING TIMESTAMP from a field: epoch microseconds, or a timestamp in the file's format. */
    long writetime(String text) {
        if (text.matches("-?\\d{1,19}")) return Long.parseLong(text);
        Instant i;
        try {
            i = (Instant) conv.parse(DataTypes.TIMESTAMP, text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("timestamp field " + o.timestampField() + ": " + e.getMessage());
        }
        return i.getEpochSecond() * 1_000_000L + i.getNano() / 1000;
    }

    /** Field access for CSV records and JSON objects. */
    sealed interface Fields {
        Object value(Loader l, int index, String key, DataType type);

        String text(Loader l, int index, String key);

        record CsvFields(Csv.Record rec) implements Fields {
            @Override
            public Object value(Loader l, int index, String key, DataType type) {
                String t = text(l, index, key);
                return t == null ? null : l.conv.parse(type, t);
            }

            @Override
            public String text(Loader l, int index, String key) {
                String f = rec.fields().get(index);
                if (!rec.wasQuoted(index) && f.equals(l.o.text().nullString())) return null;
                return f;
            }
        }

        record JsonFields(JsonNode obj) implements Fields {
            @Override
            public Object value(Loader l, int index, String key, DataType type) {
                return l.conv.fromJson(type, obj.get(key));
            }

            @Override
            public String text(Loader l, int index, String key) {
                JsonNode n = obj.get(key);
                return n == null || n.isNull() ? null : n.asText();
            }
        }
    }

    // ---- writing ----------------------------------------------------------------------------

    record Pending(BoundStatement stmt, long line, Supplier<String> raw) {}

    /**
     * Groups rows by partition (routing key) into unlogged batches, or sends them one by one.
     * A sender thread takes them from a bounded queue, so reading and converting the file
     * overlaps with writing.
     */
    final class Batcher {
        private static final List<Pending> END = List.of();
        private final Map<ByteBuffer, List<Pending>> groups = new HashMap<>();
        private final java.util.concurrent.BlockingQueue<List<Pending>> queue = new java.util.concurrent.ArrayBlockingQueue<>(1024);
        private final Semaphore inflight;
        private final RateLimiter limiter;
        private final int window;
        private final Thread sender;
        private int buffered;

        Batcher(Semaphore inflight, RateLimiter limiter) {
            this.inflight = inflight;
            this.limiter = limiter;
            this.window = Math.max(1000, o.batchSize() * o.concurrency() * 4);
            this.sender = Thread.ofVirtual().name("bulk-load-sender").start(this::sendLoop);
        }

        private void sendLoop() {
            try {
                while (true) {
                    List<Pending> rows = queue.take();
                    if (rows == END) return;
                    if (!stop) write(rows);
                }
            } catch (InterruptedException e) {
                stop = true;
            }
        }

        /** Flushes what is buffered and waits until every write has finished. */
        void finish() throws InterruptedException {
            try {
                flushAll();
            } finally {
                queue.put(END);
                sender.join();
                inflight.acquire(o.concurrency());
                inflight.release(o.concurrency());
            }
        }

        /** Stops the sender without waiting for the queue (cancel, errors). */
        void abort() {
            stop = true;
            queue.clear();
            sender.interrupt();
        }

        void add(Pending p) throws InterruptedException {
            if (o.batchSize() <= 1) {
                send(List.of(p));
                return;
            }
            ByteBuffer key = p.stmt().getRoutingKey();
            if (key == null) {
                send(List.of(p));
                return;
            }
            List<Pending> g = groups.computeIfAbsent(key, k -> new ArrayList<>());
            g.add(p);
            buffered++;
            if (g.size() >= o.batchSize()) {
                groups.remove(key);
                buffered -= g.size();
                send(g);
            }
            if (buffered >= window) flushAll();
        }

        void flushAll() throws InterruptedException {
            for (List<Pending> g : groups.values()) send(g);
            groups.clear();
            buffered = 0;
        }

        private void send(List<Pending> rows) throws InterruptedException {
            if (!stop) queue.put(rows);
        }

        private void write(List<Pending> rows) throws InterruptedException {
            if (limiter != null) limiter.acquire(rows.size());
            inflight.acquire();
            Statement<?> st = rows.size() == 1 ? rows.get(0).stmt()
                    : BatchStatement.newInstance(BatchType.UNLOGGED, rows.stream().map(Pending::stmt).toList().toArray(new BoundStatement[0]))
                    .setConsistencyLevel(o.consistency()).setTimeout(Duration.ofMillis(o.timeoutMs())).setIdempotent(true);
            attempt(st, rows, 1);
        }

        private void attempt(Statement<?> st, List<Pending> rows, int n) {
            CompletableFuture<AsyncResultSet> f;
            try {
                f = session.executeAsync(st).toCompletableFuture();
            } catch (RuntimeException e) {
                f = CompletableFuture.failedFuture(e);
            }
            f.whenComplete((rs, err) -> {
                if (err == null) {
                    stats.rowsWritten.addAndGet(rows.size());
                    inflight.release();
                    return;
                }
                Throwable cause = Errors.unwrap(err);
                if (n < ATTEMPTS && retryable(cause) && !stop) {
                    CompletableFuture.delayedExecutor(200L * n * n, TimeUnit.MILLISECONDS).execute(() -> attempt(st, rows, n + 1));
                    return;
                }
                String why = "write failed: " + Errors.describe(cause);
                for (Pending p : rows) reject(p.line(), p.raw().get(), why);
                inflight.release();
            });
        }
    }

    static boolean retryable(Throwable e) {
        if (e instanceof QueryValidationException) return false;
        return e instanceof DriverTimeoutException || e instanceof CoordinatorException
                || e instanceof com.datastax.oss.driver.api.core.AllNodesFailedException
                || e instanceof com.datastax.oss.driver.api.core.connection.BusyConnectionException;
    }

    // ---- rejects and progress ---------------------------------------------------------------

    private synchronized void reject(long line, String raw, String reason) {
        stats.rejected.incrementAndGet();
        if (rejectSamples.size() < SAMPLE_ROWS) rejectSamples.add("line " + line + ": " + reason);
        try {
            if (rejectLog == null) openRejects();
            rejectLog.write("line " + line + ": " + reason + "\n");
            if (!raw.isEmpty()) rejectData.write(raw + "\n");
        } catch (IOException e) {
            // counted above; the file is best effort
        }
        if (tooManyErrors()) stop = true;
    }

    private void openRejects() throws IOException {
        Path dir = o.path().getParent();
        if (dir == null || !Files.isWritable(dir)) dir = BulkFiles.downloadsDir();
        Path base = dir.resolve(o.path().getFileName());
        String ext = o.format() == BulkOptions.Format.JSON ? ".jsonl" : ".csv";
        Path data = BulkFiles.sibling(base, ".rejected" + ext);
        Path log = BulkFiles.sibling(base, ".rejected.log");
        rejectData = Files.newBufferedWriter(data, StandardCharsets.UTF_8);
        rejectLog = Files.newBufferedWriter(log, StandardCharsets.UTF_8);
        if (o.format() == BulkOptions.Format.CSV && o.text().header()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < fileColumns.size(); i++) {
                if (i > 0) sb.append(o.text().delimiter());
                Csv.appendField(sb, fileColumns.get(i), false, o.text().delimiter(), o.text().nullString());
            }
            rejectData.write(sb.append('\n').toString());
        }
        stats.rejectFile = data.toString();
        stats.errorFile = log.toString();
    }

    private synchronized void closeRejects() {
        for (Writer w : new Writer[] {rejectData, rejectLog}) {
            if (w == null) continue;
            try {
                w.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    private void progress(JobContext ctx, boolean force) {
        long now = System.nanoTime();
        if (!force && now - lastProgress < PROGRESS_EVERY_NANOS) return;
        lastProgress = now;
        Double frac = stats.totalBytes > 0 ? Math.min(1.0, (double) stats.bytes.get() / stats.totalBytes) : null;
        ctx.progress(frac, String.format("%,d read · %,d %s · %,d rejected · %,d rows/s", stats.rowsRead.get(),
                stats.rowsWritten.get(), o.dryRun() ? "valid" : "written", stats.rejected.get(), stats.rate()));
    }

    // ---- file inspection (preview, mapping, estimate) ---------------------------------------

    /** The file's column names: the CSV header, c1..cN without one, or the JSON keys of the first lines. */
    static List<String> fileColumns(Path path, BulkOptions.Format format, boolean gzip, BulkOptions.TextOptions t) {
        try (InputStream in = BulkFiles.openInput(path, gzip, new AtomicLong())) {
            if (format == BulkOptions.Format.CSV) {
                Csv.Reader r = new Csv.Reader(new InputStreamReader(in, StandardCharsets.UTF_8), t.delimiter());
                Csv.Record first = r.next();
                if (first == null) throw ApiException.badRequest("The file is empty");
                if (t.header()) {
                    List<String> names = first.fields().stream().map(String::trim).toList();
                    Set<String> dup = new HashSet<>();
                    for (String n : names) {
                        if (n.isEmpty()) throw ApiException.badRequest("The header has an empty column name");
                        if (!dup.add(n)) throw ApiException.badRequest("The header has column '" + n + "' twice");
                    }
                    return names;
                }
                List<String> names = new ArrayList<>();
                for (int i = 1; i <= first.fields().size(); i++) names.add("c" + i);
                return names;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            Set<String> keys = new LinkedHashSet<>();
            String line;
            int n = 0;
            while (n < 100 && (line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                n++;
                JsonNode node;
                try {
                    node = ValueConverter.JSON.readTree(line);
                } catch (IOException e) {
                    if (n == 1) throw ApiException.badRequest("The first line is not JSON: is this a JSON-lines file (one object per line)?");
                    continue;
                }
                if (node != null && node.isObject()) node.fieldNames().forEachRemaining(keys::add);
            }
            if (keys.isEmpty()) throw ApiException.badRequest("No JSON objects found in the first lines");
            return List.copyOf(keys);
        } catch (Csv.FormatException e) {
            throw ApiException.badRequest("The first record is not valid CSV: " + e.getMessage());
        } catch (java.util.zip.ZipException e) {
            throw ApiException.badRequest("The file is not valid gzip: " + e.getMessage());
        } catch (IOException e) {
            throw ApiException.badRequest("Could not read " + path + ": " + e.getMessage());
        }
    }

    /** File columns matched to table columns by name (exact, then ignoring case). */
    static List<BulkOptions.Mapping> autoMapping(List<String> fileColumns, TableMetadata t) {
        List<BulkOptions.Mapping> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (String f : fileColumns) {
            ColumnMetadata match = null;
            for (ColumnMetadata c : t.getColumns().values()) {
                if (c.getName().asInternal().equals(f)) match = c;
            }
            if (match == null) {
                for (ColumnMetadata c : t.getColumns().values()) {
                    if (c.getName().asInternal().equalsIgnoreCase(f)) match = c;
                }
            }
            if (match != null && used.add(match.getName().asInternal())) out.add(new BulkOptions.Mapping(match.getName().asInternal(), f));
        }
        return out;
    }

    /** Rows in the file: exact for small files, else from the first MiB. */
    static Long estimateRows(Path path, boolean gzip, boolean header) {
        AtomicLong raw = new AtomicLong();
        try (InputStream in = BulkFiles.openInput(path, gzip, raw)) {
            byte[] buf = new byte[1 << 16];
            long total = 0;
            long lines = 0;
            int last = '\n';
            int n;
            while (total < (1 << 20) && (n = in.read(buf)) > 0) {
                for (int i = 0; i < n; i++) if (buf[i] == '\n') lines++;
                last = buf[n - 1];
                total += n;
            }
            boolean eof = in.read() < 0;
            if (eof) {
                long rows = lines + (last != '\n' && total > 0 ? 1 : 0) - (header ? 1 : 0);
                return Math.max(0, rows);
            }
            long size = Files.size(path);
            double perByte = (double) lines / (gzip ? Math.max(1, raw.get()) : total);
            return Math.max(0, Math.round(size * perByte) - (header ? 1 : 0));
        } catch (IOException e) {
            return null;
        }
    }

    /** POST .../bulk/load/preview: the file's columns, its first rows, the table and a suggested mapping. */
    public static Map<String, Object> inspect(CqlSession session, BulkOptions.Load o) {
        BulkFiles.checkSource(o.path());
        boolean gzip = o.gzip() != null ? o.gzip() : BulkFiles.looksGzip(o.path());
        List<String> cols = fileColumns(o.path(), o.format(), gzip, o.text());
        List<List<String>> rows = new ArrayList<>();
        try (InputStream in = BulkFiles.openInput(o.path(), gzip, new AtomicLong())) {
            if (o.format() == BulkOptions.Format.CSV) {
                Csv.Reader r = new Csv.Reader(new InputStreamReader(in, StandardCharsets.UTF_8), o.text().delimiter());
                if (o.text().header()) r.next();
                Csv.Record rec;
                while (rows.size() < SAMPLE_ROWS && (rec = r.next()) != null) {
                    List<String> row = new ArrayList<>();
                    for (int i = 0; i < rec.fields().size(); i++) {
                        String f = rec.fields().get(i);
                        row.add(!rec.wasQuoted(i) && f.equals(o.text().nullString()) ? null : f);
                    }
                    rows.add(row);
                }
            } else {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while (rows.size() < SAMPLE_ROWS && (line = r.readLine()) != null) {
                    if (line.isBlank()) continue;
                    List<String> row = new ArrayList<>();
                    try {
                        JsonNode n = ValueConverter.JSON.readTree(line);
                        for (String c : cols) {
                            JsonNode v = n.get(c);
                            row.add(v == null || v.isNull() ? null : v.isValueNode() ? v.asText() : v.toString());
                        }
                    } catch (IOException e) {
                        row.add("(not JSON) " + line);
                    }
                    rows.add(row);
                }
            }
        } catch (IOException e) {
            throw ApiException.badRequest("Could not read " + o.path() + ": " + e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", o.path().toString());
        out.put("format", o.format().name().toLowerCase(Locale.ROOT));
        out.put("gzip", gzip);
        try {
            out.put("sizeBytes", Files.size(o.path()));
        } catch (IOException e) {
            out.put("sizeBytes", null);
        }
        out.put("estimatedRows", estimateRows(o.path(), gzip, o.format() == BulkOptions.Format.CSV && o.text().header()));
        out.put("fileColumns", cols);
        out.put("sampleRows", rows);
        if (o.keyspace() != null && o.table() != null) {
            TableMetadata t = Unloader.findTable(session, o.keyspace(), o.table());
            List<Map<String, Object>> tcols = new ArrayList<>();
            for (ColumnMetadata c : t.getColumns().values()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", c.getName().asInternal());
                m.put("type", c.getType().asCql(false, true));
                m.put("kind", t.getPartitionKey().contains(c) ? "partition_key"
                        : t.getClusteringColumns().containsKey(c) ? "clustering" : "regular");
                tcols.add(m);
            }
            out.put("tableColumns", tcols);
            out.put("mapping", autoMapping(cols, t));
            String counter = null;
            try {
                rejectCounters(t);
            } catch (ApiException e) {
                counter = e.getMessage();
            }
            out.put("error", counter);
        }
        return out;
    }
}
