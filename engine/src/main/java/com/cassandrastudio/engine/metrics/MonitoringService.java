package com.cassandrastudio.engine.metrics;

import com.cassandrastudio.engine.conn.ConnectionRepository;
import com.cassandrastudio.engine.cql.ClusterService.ClusterInfo;
import com.cassandrastudio.engine.cql.ClusterService.NodeInfo;
import com.cassandrastudio.engine.jmx.JmxAccess;
import com.cassandrastudio.engine.jmx.NodeEndpoint;
import com.cassandrastudio.engine.metrics.MonitoringModel.AccessStatus;
import com.cassandrastudio.engine.metrics.MonitoringModel.Alert;
import com.cassandrastudio.engine.metrics.MonitoringModel.ClusterSnapshot;
import com.cassandrastudio.engine.metrics.MonitoringModel.NodeSnapshot;
import com.cassandrastudio.engine.metrics.MonitoringModel.Ring;
import com.cassandrastudio.engine.metrics.MonitoringModel.RingDc;
import com.cassandrastudio.engine.metrics.MonitoringModel.RingNode;
import com.cassandrastudio.engine.metrics.MonitoringModel.Series;
import com.cassandrastudio.engine.metrics.MonitoringModel.TableMetrics;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.JmxMethod;
import com.cassandrastudio.engine.model.ConnectionConfig.SecretKeys;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.ApiException;
import com.cassandrastudio.engine.util.Json;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Monitoring for every started cluster (MON-1..MON-18, ALR-1): one {@link Poller} per started
 * connection, nothing for the others (NFR-PERF). Node reads run on virtual threads, at most
 * {@value #MAX_CONCURRENT_READS} at a time across all clusters. Per-cluster thresholds are kept
 * in the settings table.
 */
public final class MonitoringService implements AutoCloseable {
    public static final int DEFAULT_INTERVAL_SEC = 10;
    public static final int MIN_INTERVAL_SEC = 2;
    public static final long DEFAULT_WINDOW_MS = 15 * 60_000L;
    static final int MAX_CONCURRENT_READS = 64;
    /** The ring request's whole JMX budget, and how many nodes it tries before using the driver's tokens. */
    static final long RING_BUDGET_MS = 15_000;
    static final int RING_MAX_NODES = 3;
    /** The per-table view's whole budget across all nodes. */
    static final long TABLES_BUDGET_MS = 20_000;
    private static final String THRESHOLDS_KEY = "monitoring.thresholds/";

    private final Database db;
    private final ConnectionRepository connections;
    private final JmxAccess jmx;
    private final Topology topology;
    private final LongSupplier clock;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits = new Semaphore(MAX_CONCURRENT_READS, true);
    private final Map<String, Poller> pollers = new ConcurrentHashMap<>();
    private final Map<String, Thresholds> thresholds = new ConcurrentHashMap<>();

    public MonitoringService(Database db, ConnectionRepository connections, JmxAccess jmx, Topology topology) {
        this(db, connections, jmx, topology, System::currentTimeMillis);
    }

    MonitoringService(Database db, ConnectionRepository connections, JmxAccess jmx, Topology topology,
                      LongSupplier clock) {
        this.db = db;
        this.connections = connections;
        this.jmx = jmx;
        this.topology = topology;
        this.clock = clock;
    }

    // ---- lifecycle --------------------------------------------------------------------

    /** Starts (or re-times) polling. Idempotent: same interval = no change. */
    public AccessStatus start(String connectionId, Integer intervalSec) {
        int sec = intervalSec == null ? DEFAULT_INTERVAL_SEC : intervalSec;
        if (sec < MIN_INTERVAL_SEC) throw ApiException.badRequest("intervalSec must be at least " + MIN_INTERVAL_SEC);
        if (sec > 3600) throw ApiException.badRequest("intervalSec must be at most 3600");
        return startPoller(connectionId, sec * 1000L).status();
    }

    /** Package-private so tests can poll faster than the API minimum. */
    synchronized Poller startPoller(String connectionId, long intervalMs) {
        ConnectionConfig cfg = connections.get(connectionId);
        Poller existing = pollers.get(connectionId);
        if (existing != null && existing.running() && existing.intervalMs() == intervalMs) return existing;
        if (existing != null) existing.stop();
        Poller p = new Poller(connectionId, cfg, secretsOf(connectionId), jmx, topology, executor, permits, clock,
                () -> effectiveThresholds(connectionId), intervalMs);
        pollers.put(connectionId, p);
        p.start();
        return p;
    }

    /** Stops polling and drops the cluster's history. Call on disconnect and on connection change. */
    public synchronized void stop(String connectionId) {
        Poller p = pollers.remove(connectionId);
        if (p != null) p.stop();
    }

    /** The running poller (tests). */
    Poller pollerFor(String connectionId) {
        return pollers.get(connectionId);
    }

    public boolean isStarted(String connectionId) {
        return pollers.containsKey(connectionId);
    }

    @Override
    public void close() {
        pollers.keySet().forEach(this::stop);
        executor.shutdownNow();
    }

    // ---- reads ------------------------------------------------------------------------

    public AccessStatus status(String connectionId) {
        ConnectionConfig cfg = connections.get(connectionId);
        Poller p = pollers.get(connectionId);
        return p == null ? new AccessStatus(cfg.jmx().method().name(), false, 0, List.of()) : p.status();
    }

    /** Latest poll; 409 not_started when monitoring is not running for this cluster. */
    public ClusterSnapshot snapshot(String connectionId) {
        connections.get(connectionId);
        Poller p = pollers.get(connectionId);
        if (p == null) throw notStarted();
        return p.latestOrPoll();
    }

    public List<Alert> alerts(String connectionId) {
        connections.get(connectionId);
        Poller p = pollers.get(connectionId);
        ClusterSnapshot s = p == null ? null : p.latest();
        return s == null ? List.of() : s.alerts();
    }

    /** History of one metric; node null = all nodes; window defaults to the last 15 minutes. */
    public Series series(String connectionId, String metric, String node, Long fromMs, Long toMs) {
        return series(connectionId, metric, node, fromMs, toMs, 0);
    }

    /**
     * As {@link #series(String, String, String, Long, Long)}, with at most {@code maxPoints} points per
     * node (0 = all), downsampled with largest-triangle-three-buckets so peaks stay visible. A 24 h
     * chart of a 500-node cluster then moves a few hundred points per node, not 1,800 (NFR-SCALE).
     */
    public Series series(String connectionId, String metric, String node, Long fromMs, Long toMs, int maxPoints) {
        if (maxPoints < 0 || (maxPoints > 0 && maxPoints < 3)) throw ApiException.badRequest("maxPoints must be 0 or at least 3");
        connections.get(connectionId);
        if (!SeriesMetrics.known(metric)) {
            throw new ApiException(400, "bad_request", "Unknown metric '" + metric + "'",
                    Map.of("metrics", List.copyOf(SeriesMetrics.ALL.keySet())));
        }
        long to = toMs == null ? clock.getAsLong() : toMs;
        long from = fromMs == null ? to - DEFAULT_WINDOW_MS : fromMs;
        if (from > to) throw ApiException.badRequest("fromMs must not be after toMs");
        Poller p = pollers.get(connectionId);
        String unit = SeriesMetrics.ALL.get(metric).unit();
        Map<String, List<double[]>> points = p == null ? Map.of() : p.history.query(metric, node, from, to);
        if (maxPoints > 0) {
            Map<String, List<double[]>> sampled = new TreeMap<>();
            points.forEach((n, pts) -> sampled.put(n, Downsample.lttb(pts, maxPoints)));
            points = sampled;
        }
        return new Series(metric, unit, points);
    }

    // ---- thresholds (ALR-1) -----------------------------------------------------------

    /** Effective thresholds: per-cluster overrides over the defaults. */
    public Thresholds thresholds(String connectionId) {
        connections.get(connectionId);
        return effectiveThresholds(connectionId);
    }

    private Thresholds effectiveThresholds(String connectionId) {
        return thresholds.computeIfAbsent(connectionId, this::loadThresholds).effective();
    }

    /** Saves overrides (null fields = default) and returns the effective values. */
    public Thresholds setThresholds(String connectionId, Thresholds overrides) {
        connections.get(connectionId);
        Thresholds t = (overrides == null ? new Thresholds(null, null, null, null, null, null, null, null, null)
                : overrides).validated();
        if (db != null) {
            db.update("INSERT OR REPLACE INTO settings(key, value) VALUES (?, ?)", THRESHOLDS_KEY + connectionId,
                    Json.write(t));
        }
        thresholds.put(connectionId, t);
        return t.effective();
    }

    private Thresholds loadThresholds(String connectionId) {
        if (db == null) return Thresholds.DEFAULTS;
        List<Map<String, Object>> rows = db.query("SELECT value FROM settings WHERE key=?", THRESHOLDS_KEY + connectionId);
        if (rows.isEmpty()) return Thresholds.DEFAULTS;
        try {
            return Json.read(String.valueOf(rows.get(0).get("value")), Thresholds.class);
        } catch (IllegalArgumentException e) {
            return Thresholds.DEFAULTS;
        }
    }

    // ---- on request: ring (MON-13/14) and tables (MON-18) ------------------------------

    public Ring ring(String connectionId, String keyspace) {
        ConnectionConfig cfg = connections.get(connectionId);
        ClusterInfo info = topology.info(connectionId);
        String ks = keyspace != null && !keyspace.isBlank() ? keyspace
                : topology.keyspaces(connectionId).stream().findFirst().orElse(null);
        NodeReader.RingRead rr = null;
        Map<String, NodeSnapshot> latest = latestByAddress(connectionId);
        if (cfg.jmx().method() != JmxMethod.EXPORTER) {
            Map<String, String> secrets = secretsOf(connectionId);
            // One overall budget and a few nodes at most, readable ones first: with many hung nodes the
            // request still answers in time, falling back to the driver's token map (NFR-RELI).
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RING_BUDGET_MS);
            int tried = 0;
            for (NodeInfo n : readableFirst(info.nodes(), latest)) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left < 500 || tried++ >= RING_MAX_NODES) break;
                try {
                    rr = call(() -> NodeReader.readRing(jmx.session(cfg, secrets, endpoint(n)).mbeans(), n.version(), ks),
                            Math.min(left, 15_000));
                    if (!rr.endpointByToken().isEmpty()) break;
                } catch (RuntimeException e) {
                    rr = null;
                }
            }
        }
        Map<String, List<String>> tokensByHost = new HashMap<>();
        Map<String, String> endpointByHost = new HashMap<>();
        if (rr != null) {
            rr.hostIdByEndpoint().forEach((ep, host) -> endpointByHost.put(host, ep));
            Map<String, String> hostByEndpoint = rr.hostIdByEndpoint();
            rr.endpointByToken().forEach((token, ep) ->
                    tokensByHost.computeIfAbsent(hostByEndpoint.getOrDefault(ep, ep), k -> new ArrayList<>()).add(token));
        }
        if (tokensByHost.isEmpty()) tokensByHost.putAll(topology.tokens(connectionId));
        Map<String, List<RingNode>> byDc = new TreeMap<>();
        for (NodeInfo n : info.nodes()) {
            String ep = n.hostId() == null ? n.address() : endpointByHost.getOrDefault(n.hostId(), n.address());
            List<String> tokens = new ArrayList<>(tokensByHost.getOrDefault(n.hostId(),
                    tokensByHost.getOrDefault(ep, List.of())));
            tokens.sort(MonitoringService::compareTokens);
            NodeSnapshot snap = latest.get(n.address());
            String state = snap != null && snap.state() != null ? snap.state() : driverState(n.state());
            byDc.computeIfAbsent(String.valueOf(n.datacenter()), k -> new ArrayList<>()).add(new RingNode(n.hostId(),
                    n.address(), n.rack(), state, snap == null ? null : snap.loadBytes(), tokens,
                    rr == null ? null : rr.ownershipPct().get(ep), rr == null ? null : rr.effectivePct().get(ep)));
        }
        List<RingDc> dcs = new ArrayList<>();
        byDc.forEach((dc, list) -> {
            list.sort(Comparator.comparing((RingNode r) -> r.tokens().isEmpty() ? null : r.tokens().get(0),
                    Comparator.nullsLast(MonitoringService::compareTokens)));
            dcs.add(new RingDc(dc, list));
        });
        String partitioner = rr != null && rr.partitioner() != null ? rr.partitioner() : info.partitioner();
        return new Ring(partitioner, ks, dcs);
    }

    /** Per-table metrics summed over readable nodes (latencies and maxima: worst node). */
    public List<TableMetrics> tables(String connectionId, String keyspace) {
        ConnectionConfig cfg = connections.get(connectionId);
        ClusterInfo info = topology.info(connectionId);
        Map<String, String> secrets = secretsOf(connectionId);
        String ks = keyspace == null || keyspace.isBlank() ? null : keyspace;
        List<Future<List<TableMetrics>>> futures = new ArrayList<>();
        for (NodeInfo n : info.nodes()) {
            futures.add(executor.submit(() -> cfg.jmx().method() == JmxMethod.EXPORTER
                    ? ExporterReader.tables(jmx.scrapeExporter(cfg, endpoint(n)), ks)
                    : NodeReader.readTables(jmx.session(cfg, secrets, endpoint(n)).mbeans(), n.version(), ks)));
        }
        List<List<TableMetrics>> perNode = new ArrayList<>();
        String lastError = null;
        // one budget for the whole request: hung nodes are left out instead of holding it up (NFR-RELI)
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TABLES_BUDGET_MS);
        for (Future<List<TableMetrics>> f : futures) {
            try {
                perNode.add(f.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            } catch (java.util.concurrent.TimeoutException e) {
                f.cancel(true);
                lastError = "Timed out after " + TABLES_BUDGET_MS / 1000 + " s";
            } catch (Exception e) {
                f.cancel(true);
                lastError = Poller.message(e.getCause() == null ? e : e.getCause());
            }
        }
        if (perNode.isEmpty() && !futures.isEmpty()) {
            throw new ApiException(503, "jmx_unavailable", "No node could be read: " + lastError);
        }
        return TableAggregator.aggregate(perNode);
    }

    // ---- helpers ------------------------------------------------------------------------

    private Map<String, NodeSnapshot> latestByAddress(String connectionId) {
        Map<String, NodeSnapshot> out = new HashMap<>();
        Poller p = pollers.get(connectionId);
        ClusterSnapshot s = p == null ? null : p.latest();
        if (s != null) s.nodes().forEach(n -> out.put(n.address(), n));
        return out;
    }

    private Map<String, String> secretsOf(String connectionId) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : SecretKeys.ALL) connections.secret(connectionId, k).ifPresent(v -> m.put(k, v));
        return m;
    }

    private <T> T call(Callable<T> task, long timeoutMs) {
        Future<T> f = executor.submit(task);
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            f.cancel(true);
            throw new IllegalStateException(Poller.message(e.getCause() == null ? e : e.getCause()), e);
        }
    }

    static NodeEndpoint endpoint(NodeInfo n) {
        return new NodeEndpoint(n.hostId(), n.address(), n.datacenter(), n.rack(), n.version());
    }

    /** Nodes read fine by the last poll first, then other nodes the driver sees up, then the rest. */
    private static List<NodeInfo> readableFirst(List<NodeInfo> nodes, Map<String, NodeSnapshot> latest) {
        List<NodeInfo> out = new ArrayList<>(nodes);
        out.sort(Comparator.comparingInt((NodeInfo n) -> {
            NodeSnapshot s = latest.get(n.address());
            if (s != null && s.error() == null) return 0;
            return s == null && "UP".equals(n.state()) ? 1 : 2;
        }));
        return out;
    }

    private static String driverState(String s) {
        return switch (s == null ? "" : s) {
            case "UP" -> "UN";
            case "DOWN", "FORCED_DOWN" -> "DN";
            default -> "?N";
        };
    }

    /** Numeric order for Murmur3/Random tokens, string order otherwise. */
    static int compareTokens(String a, String b) {
        try {
            return new BigInteger(a).compareTo(new BigInteger(b));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }

    private static ApiException notStarted() {
        return new ApiException(409, "not_started", "Monitoring is not started for this cluster");
    }
}
