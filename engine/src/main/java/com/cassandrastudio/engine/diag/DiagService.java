package com.cassandrastudio.engine.diag;

import com.cassandrastudio.engine.conn.ConnectionRepository;
import com.cassandrastudio.engine.cql.ClusterService.NodeInfo;
import com.cassandrastudio.engine.diag.ThreadDumps.ThreadDump;
import com.cassandrastudio.engine.jmx.JmxAccess;
import com.cassandrastudio.engine.jmx.NodeEndpoint;
import com.cassandrastudio.engine.jobs.Job;
import com.cassandrastudio.engine.jobs.JobService;
import com.cassandrastudio.engine.metrics.Topology;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.ssh.NodeShell;
import com.cassandrastudio.engine.ssh.SshAccessException;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.ApiException;
import com.cassandrastudio.engine.util.Json;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.management.MBeanServerConnection;
import javax.management.openmbean.CompositeData;

/**
 * JVM and partition diagnostics (JVM-1, JVM-2, PRF-1, PRF-2). Everything here is read-only
 * towards the cluster (thread dumps, CPU counters, sampling, metrics, logs), so no ActionGuard;
 * long work (dump series, sampling, the estate tombstone scan) runs as jobs.
 * Thread dumps are kept in memory, the newest {@value #MAX_DUMPS} per connection.
 */
public final class DiagService implements AutoCloseable {
    static final int MAX_DUMPS = 40;
    static final Duration NODE_TIMEOUT = Duration.ofSeconds(60);
    static final Duration SSH_TIMEOUT = Duration.ofSeconds(30);
    /** A ttop baseline older than this is discarded (the next call starts a new one). */
    static final long TOP_STALE_MS = 120_000;
    private static final String SETTINGS_KEY = "diag.settings/";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,48}");

    private final Database db;
    private final ConnectionRepository connections;
    private final JmxAccess jmx;
    private final Topology topology;
    private final NodeShell shell;
    private final JobService jobs;
    private final Function<String, Map<String, String>> secrets;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Map<String, ThreadDump>> dumps = new ConcurrentHashMap<>();
    private final Map<String, ThreadTop.Sample> topBaselines = new ConcurrentHashMap<>();

    public DiagService(Database db, ConnectionRepository connections, JmxAccess jmx, Topology topology, NodeShell shell,
                       JobService jobs, Function<String, Map<String, String>> secrets) {
        this.db = db;
        this.connections = connections;
        this.jmx = jmx;
        this.topology = topology;
        this.shell = shell;
        this.jobs = jobs;
        this.secrets = secrets;
    }

    /** Drops dumps and ttop baselines of a connection (disconnect, edit, delete). */
    public void forget(String connectionId) {
        dumps.remove(connectionId);
        topBaselines.keySet().removeIf(k -> k.startsWith(connectionId + "|"));
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    // ---- nodes and access ---------------------------------------------------------------

    private List<NodeInfo> nodes(String connectionId) {
        return topology.info(connectionId).nodes();
    }

    /** The node with this address; 400 when it is not part of the cluster. */
    NodeInfo node(String connectionId, String address) {
        if (address == null || address.isBlank()) throw ApiException.badRequest("node is required");
        for (NodeInfo n : nodes(connectionId)) {
            if (address.equals(n.address())) return n;
        }
        throw ApiException.badRequest("Node " + address + " is not part of this cluster");
    }

    private List<NodeInfo> nodesOrAll(String connectionId, List<String> addresses) {
        if (addresses == null || addresses.isEmpty()) return nodes(connectionId);
        List<NodeInfo> out = new ArrayList<>();
        for (String a : addresses) out.add(node(connectionId, a));
        return out;
    }

    private MBeanServerConnection mbeans(ConnectionConfig cfg, NodeInfo n) {
        try {
            return jmx.session(cfg, secrets.apply(cfg.id()),
                    new NodeEndpoint(n.hostId(), n.address(), n.datacenter(), n.rack(), n.version())).mbeans();
        } catch (UnsupportedOperationException e) {
            throw new ApiException(409, "jmx_required", "This needs JMX; the connection reaches nodes by "
                    + cfg.jmx().method() + ". Set JMX access to SSH tunnel or Direct in the connection settings.");
        } catch (JmxAccess.JmxUnavailableException e) {
            throw new ApiException(502, "node_unreachable", "JMX on " + n.address() + ": " + e.getMessage());
        }
    }

    /** Runs a JMX read on one node, mapping a dropped connection to a readable 502. */
    private <T> T onNode(ConnectionConfig cfg, NodeInfo n, Function<MBeanServerConnection, T> work) {
        MBeanServerConnection c = mbeans(cfg, n);
        try {
            return work.apply(c);
        } catch (UncheckedIOException e) {
            throw new ApiException(502, "node_unreachable", "JMX on " + n.address() + " failed: " + Jmx.rootMessage(e));
        } catch (Jmx.Missing | IllegalStateException e) {
            throw new ApiException(409, "not_supported", n.address() + ": " + e.getMessage());
        }
    }

    // ---- settings -----------------------------------------------------------------------

    public DiagSettings settings(String connectionId) {
        connections.get(connectionId);
        return load(connectionId).effective();
    }

    public DiagSettings saveSettings(String connectionId, DiagSettings s) {
        connections.get(connectionId);
        DiagSettings v = (s == null ? new DiagSettings(null, null, null, null) : s).validated();
        if (db != null) {
            db.update("INSERT OR REPLACE INTO settings(key, value) VALUES (?, ?)", SETTINGS_KEY + connectionId, Json.write(v));
        }
        return v.effective();
    }

    private DiagSettings load(String connectionId) {
        if (db == null) return DiagSettings.DEFAULTS;
        List<Map<String, Object>> rows = db.query("SELECT value FROM settings WHERE key=?", SETTINGS_KEY + connectionId);
        if (rows.isEmpty()) return DiagSettings.DEFAULTS;
        try {
            return Json.read(String.valueOf(rows.get(0).get("value")), DiagSettings.class);
        } catch (IllegalArgumentException e) {
            return DiagSettings.DEFAULTS;
        }
    }

    // ---- thread dumps (JVM-1) -----------------------------------------------------------

    public ThreadDump takeDump(String connectionId, String address) {
        return takeDump(connectionId, address, null);
    }

    private ThreadDump takeDump(String connectionId, String address, String seriesId) {
        ConnectionConfig cfg = connections.get(connectionId);
        NodeInfo n = node(connectionId, address);
        ThreadDump d = onNode(cfg, n, c -> {
            long at = System.currentTimeMillis();
            CompositeData[] raw = (CompositeData[]) Jmx.call(c, Jmx.THREADING, "dumpAllThreads",
                    new Object[] {true, true}, new String[] {boolean.class.getName(), boolean.class.getName()});
            long[] dead;
            try {
                dead = (long[]) Jmx.call(c, Jmx.THREADING, "findDeadlockedThreads", new Object[0], new String[0]);
            } catch (Jmx.Missing | IllegalStateException e) {
                dead = null;
            }
            Map<String, Object> rt = Jmx.attributes(c, Jmx.RUNTIME, "VmName", "VmVersion", "SpecVersion");
            String jvm = rt.isEmpty() ? null : rt.getOrDefault("VmName", "JVM") + " " + rt.getOrDefault("VmVersion", "")
                    + (rt.containsKey("SpecVersion") ? " (Java " + rt.get("SpecVersion") + ")" : "");
            return ThreadDumps.build(UUID.randomUUID().toString(), n.address(), at, jvm, seriesId,
                    ThreadDumps.entries(raw), dead);
        });
        Map<String, ThreadDump> store = dumps.computeIfAbsent(connectionId, k -> Collections.synchronizedMap(new LinkedHashMap<>()));
        synchronized (store) {
            store.put(d.id(), d);
            while (store.size() > MAX_DUMPS) store.remove(store.keySet().iterator().next());
        }
        return d;
    }

    /** Newest first. */
    public List<ThreadDumps.Summary> dumps(String connectionId) {
        connections.get(connectionId);
        Map<String, ThreadDump> store = dumps.getOrDefault(connectionId, Map.of());
        List<ThreadDumps.Summary> out = new ArrayList<>();
        synchronized (store) {
            store.values().forEach(d -> out.add(d.summary()));
        }
        Collections.reverse(out);
        return out;
    }

    public ThreadDump dump(String connectionId, String dumpId) {
        connections.get(connectionId);
        ThreadDump d = dumps.getOrDefault(connectionId, Map.of()).get(dumpId);
        if (d == null) throw ApiException.notFound("Thread dump " + dumpId);
        return d;
    }

    public void deleteDump(String connectionId, String dumpId) {
        dump(connectionId, dumpId);
        dumps.get(connectionId).remove(dumpId);
    }

    public ThreadDumps.Comparison compare(String connectionId, String a, String b) {
        if (a == null || b == null) throw ApiException.badRequest("a and b (dump ids) are required");
        if (a.equals(b)) throw ApiException.badRequest("Choose two different dumps");
        ThreadDump da = dump(connectionId, a);
        ThreadDump db = dump(connectionId, b);
        if (!da.node().equals(db.node())) {
            throw ApiException.badRequest("Both dumps must come from the same node (" + da.node() + " vs " + db.node() + ")");
        }
        return ThreadDumps.compare(da, db);
    }

    /** {@code count} dumps every {@code intervalSec} seconds, as a job; the result lists the dump ids. */
    public Job series(String connectionId, String address, int count, int intervalSec) {
        if (count < 2 || count > 20) throw ApiException.badRequest("count must be between 2 and 20");
        if (intervalSec < 1 || intervalSec > 300) throw ApiException.badRequest("intervalSec must be between 1 and 300");
        connections.get(connectionId);
        NodeInfo n = node(connectionId, address);
        String seriesId = UUID.randomUUID().toString();
        String title = count + " thread dumps of " + n.address() + " every " + intervalSec + " s";
        return jobs.submit(new JobService.Spec(connectionId, "thread-dump-series", title, n.address(), null, true), ctx -> {
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                ctx.checkCancelled();
                if (i > 0) Thread.sleep(intervalSec * 1000L);
                ThreadDump d = takeDump(connectionId, address, seriesId);
                ids.add(d.id());
                ctx.log("dump " + (i + 1) + ": " + d.threadCount() + " threads, " + d.blockedCount() + " blocked"
                        + (d.deadlocks().isEmpty() ? "" : ", " + d.deadlocks().size() + " deadlock(s)"));
                ctx.progress((i + 1) / (double) count, "Dump " + (i + 1) + " of " + count);
            }
            return Map.of("seriesId", seriesId, "dumpIds", ids, "node", n.address());
        });
    }

    // ---- top threads (JVM-2) ------------------------------------------------------------

    /**
     * CPU per thread since the previous call for this node (shared by viewers of the same node).
     * The first call takes two samples one second apart so it already shows rates.
     */
    public ThreadTop.TopView top(String connectionId, String address, int limit, boolean group) throws InterruptedException {
        if (limit < 1 || limit > 500) throw ApiException.badRequest("limit must be between 1 and 500");
        ConnectionConfig cfg = connections.get(connectionId);
        NodeInfo n = node(connectionId, address);
        String key = connectionId + "|" + n.address();
        ThreadTop.Sample prev = topBaselines.get(key);
        if (prev != null && System.currentTimeMillis() - prev.atMs() > TOP_STALE_MS) prev = null;
        if (prev == null) {
            prev = onNode(cfg, n, c -> ThreadTop.sample(c, System.nanoTime(), System.currentTimeMillis()));
            Thread.sleep(1000);
        }
        ThreadTop.Sample cur = onNode(cfg, n, c -> ThreadTop.sample(c, System.nanoTime(), System.currentTimeMillis()));
        topBaselines.put(key, cur);
        return ThreadTop.view(n.address(), prev, cur, limit, group);
    }

    // ---- table histograms (PRF-2) -------------------------------------------------------

    public TableHistograms.View histograms(String connectionId, String address, String keyspace, boolean includeSystem) {
        ConnectionConfig cfg = connections.get(connectionId);
        if (keyspace != null && !NAME.matcher(keyspace).matches()) throw ApiException.badRequest("Invalid keyspace name");
        TableHistograms.Thresholds th = load(connectionId).thresholds();
        List<NodeInfo> targets = address == null ? nodes(connectionId) : List.of(node(connectionId, address));
        List<TableHistograms.TableHist> perNode = new ArrayList<>();
        List<TableHistograms.NodeError> errors = new ArrayList<>();
        for (var r : parallel(targets, n -> onNode(cfg, n, c -> TableHistograms.read(c, n.address(), keyspace, includeSystem, th)))) {
            if (r.error() != null) errors.add(new TableHistograms.NodeError(r.node(), r.error()));
            else perNode.addAll(r.value());
        }
        return new TableHistograms.View(TableHistograms.merge(perNode, th), perNode, errors, th);
    }

    // ---- hot partitions (PRF-1) ---------------------------------------------------------

    public Job hotPartitions(String connectionId, HotPartitions.Request req) {
        ConnectionConfig cfg = connections.get(connectionId);
        if (req.tables() == null || req.tables().isEmpty()) throw ApiException.badRequest("Choose at least one table");
        if (req.tables().size() > HotPartitions.MAX_TABLES) {
            throw ApiException.badRequest("At most " + HotPartitions.MAX_TABLES + " tables per run");
        }
        for (String t : req.tables()) {
            String[] kt = t.split("\\.", 2);
            if (kt.length != 2 || !NAME.matcher(kt[0]).matches() || !NAME.matcher(kt[1]).matches()) {
                throw ApiException.badRequest("Table must be keyspace.table: " + t);
            }
        }
        if (req.durationMs() < 1000 || req.durationMs() > 600_000) throw ApiException.badRequest("durationSec must be between 1 and 600");
        if (req.capacity() < 10 || req.capacity() > 1024) throw ApiException.badRequest("capacity must be between 10 and 1024");
        if (req.top() < 1 || req.top() > req.capacity()) throw ApiException.badRequest("top must be between 1 and capacity");
        List<NodeInfo> targets = nodesOrAll(connectionId, req.nodes());
        String title = "Hot partitions: " + (req.tables().size() == 1 ? req.tables().get(0) : req.tables().size() + " tables")
                + " for " + req.durationMs() / 1000 + " s on " + (targets.size() == 1 ? targets.get(0).address() : targets.size() + " nodes");
        return jobs.submit(new JobService.Spec(connectionId, "hot-partitions", title,
                targets.size() == 1 ? targets.get(0).address() : null, null, true), ctx -> {
            ctx.progress(0.0, "Sampling");
            double[] prog = new double[targets.size()];
            List<Future<HotPartitions.NodeResult>> fs = new ArrayList<>();
            for (int i = 0; i < targets.size(); i++) {
                NodeInfo n = targets.get(i);
                int idx = i;
                fs.add(executor.submit(() -> {
                    try {
                        MBeanServerConnection c = mbeans(cfg, n);
                        HotPartitions.NodeResult r = HotPartitions.sampleNode(n.address(), c, req, ctx, p -> {
                            synchronized (prog) {
                                prog[idx] = p;
                                double sum = 0;
                                for (double x : prog) sum += x;
                                ctx.progress(0.95 * sum / prog.length, "Sampling");
                            }
                        });
                        ctx.log(n.address() + ": sampled with the " + r.api() + " API");
                        return r;
                    } catch (ApiException | UncheckedIOException e) {
                        ctx.log(n.address() + ": " + Jmx.rootMessage(e));
                        return new HotPartitions.NodeResult(n.address(), null, Jmx.rootMessage(e), null);
                    }
                }));
            }
            List<HotPartitions.NodeResult> results = new ArrayList<>();
            try {
                for (Future<HotPartitions.NodeResult> f : fs) {
                    results.add(f.get(req.durationMs() + NODE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
                }
            } catch (ExecutionException e) {
                if (e.getCause() instanceof java.util.concurrent.CancellationException ce) throw ce;
                throw new IllegalStateException(Jmx.rootMessage(e));
            } catch (InterruptedException e) {
                fs.forEach(f -> f.cancel(true));
                throw e;
            }
            if (results.stream().allMatch(r -> r.error() != null)) {
                throw new IllegalStateException("No node could be sampled: " + results.get(0).error());
            }
            return new HotPartitions.Result(req.durationMs(), req.capacity(), req.top(), results,
                    HotPartitions.merge(results, req.top()));
        });
    }

    // ---- log warnings and the estate tombstone scan (PRF-2) -----------------------------

    public LogWarnings.View warnings(String connectionId, String address, int limit) {
        if (limit < 1 || limit > 5000) throw ApiException.badRequest("limit must be between 1 and 5000");
        ConnectionConfig cfg = connections.get(connectionId);
        String path = load(connectionId).effective().logPath();
        List<NodeInfo> targets = address == null ? nodes(connectionId) : List.of(node(connectionId, address));
        Map<String, String> sec = secrets.apply(connectionId);
        List<LogWarnings.Warning> all = new ArrayList<>();
        List<LogWarnings.NodeLog> logs = new ArrayList<>();
        for (var r : parallel(targets, n -> shell.exec(cfg, sec, n.address(), LogWarnings.command(path, limit), SSH_TIMEOUT, 4 << 20))) {
            if (r.error() != null) {
                logs.add(new LogWarnings.NodeLog(r.node(), path, r.error(), 0));
            } else if (r.value().contains(LogWarnings.NO_LOG)) {
                logs.add(new LogWarnings.NodeLog(r.node(), path, path + " is not readable over SSH on this node "
                        + "(check the log path in the settings and that the SSH user may read it)", 0));
            } else {
                List<LogWarnings.Warning> w = LogWarnings.parse(r.node(), r.value());
                all.addAll(w);
                logs.add(new LogWarnings.NodeLog(r.node(), path, null, w.size()));
            }
        }
        return new LogWarnings.View(LogWarnings.sorted(all), logs);
    }

    public Job tombstoneScan(String connectionId, String address, String keyspace, String table) {
        ConnectionConfig cfg = connections.get(connectionId);
        if (keyspace != null && !NAME.matcher(keyspace).matches()) throw ApiException.badRequest("Invalid keyspace name");
        if (table != null && !NAME.matcher(table).matches()) throw ApiException.badRequest("Invalid table name");
        if (table != null && keyspace == null) throw ApiException.badRequest("A table needs its keyspace");
        NodeInfo n = node(connectionId, address);
        String script = load(connectionId).effective().tombstoneScanScript();
        String cmd = LogWarnings.scanCommand(script, keyspace, table);
        String title = "Tombstone scan on " + n.address() + (keyspace == null ? "" : " (" + keyspace + (table == null ? "" : "." + table) + ")");
        return jobs.submit(new JobService.Spec(connectionId, "tombstone-scan", title, n.address(), null, false), ctx -> {
            ctx.progress(null, "Running " + script);
            ctx.log("$ " + cmd);
            String out = shell.exec(cfg, secrets.apply(connectionId), n.address(), cmd, Duration.ofMinutes(10), 4 << 20);
            LogWarnings.ScanResult r = LogWarnings.parseScan(n.address(), cmd, out);
            if (!r.available()) {
                throw new IllegalStateException(script + " is not installed on " + n.address()
                        + " (the estate deploys it with cass-ops; set its path in the settings)");
            }
            return r;
        });
    }

    // ---- helpers ------------------------------------------------------------------------

    record NodeOutcome<T>(String node, T value, String error) {}

    /** Runs {@code work} on every node at once; a node's failure becomes its error text. */
    private <T> List<NodeOutcome<T>> parallel(List<NodeInfo> targets, Function<NodeInfo, T> work) {
        List<Future<T>> fs = new ArrayList<>();
        for (NodeInfo n : targets) fs.add(executor.submit((Callable<T>) () -> work.apply(n)));
        List<NodeOutcome<T>> out = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            String node = targets.get(i).address();
            try {
                out.add(new NodeOutcome<>(node, fs.get(i).get(NODE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), null));
            } catch (TimeoutException e) {
                fs.get(i).cancel(true);
                out.add(new NodeOutcome<>(node, null, "No answer within " + NODE_TIMEOUT.toSeconds() + " s"));
            } catch (ExecutionException e) {
                Throwable c = e.getCause();
                String m = c instanceof ApiException || c instanceof SshAccessException ? c.getMessage() : Jmx.rootMessage(c);
                out.add(new NodeOutcome<>(node, null, m));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(503, "interrupted", "Interrupted");
            }
        }
        return out;
    }
}
