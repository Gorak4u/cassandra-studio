package com.cassandrastudio.engine.metrics;

import com.cassandrastudio.engine.cql.ClusterService.ClusterInfo;
import com.cassandrastudio.engine.cql.ClusterService.NodeInfo;
import com.cassandrastudio.engine.jmx.JmxAccess;
import com.cassandrastudio.engine.jmx.NodeEndpoint;
import com.cassandrastudio.engine.metrics.MonitoringModel.AccessStatus;
import com.cassandrastudio.engine.metrics.MonitoringModel.Alert;
import com.cassandrastudio.engine.metrics.MonitoringModel.ClusterSnapshot;
import com.cassandrastudio.engine.metrics.MonitoringModel.DataDir;
import com.cassandrastudio.engine.metrics.MonitoringModel.Health;
import com.cassandrastudio.engine.metrics.MonitoringModel.Level;
import com.cassandrastudio.engine.metrics.MonitoringModel.NodeAccess;
import com.cassandrastudio.engine.metrics.MonitoringModel.NodeSnapshot;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.JmxMethod;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls one cluster (MON-1): every interval, reads all nodes in parallel with a per-node
 * timeout, merges driver and gossip state, evaluates the health rules and records history.
 * Polls never overlap: the next one starts after the previous finished. A node whose previous
 * read is still hanging is reported unreachable instead of getting a second read (NFR-RELI).
 */
final class Poller {
    private static final Logger LOG = LoggerFactory.getLogger(Poller.class);

    private final String connectionId;
    private final ConnectionConfig cfg;
    private final Map<String, String> secrets;
    private final JmxAccess jmx;
    private final Topology topology;
    private final ExecutorService executor;
    private final Semaphore permits;
    private final LongSupplier clock;
    private final Supplier<Thresholds> thresholds;
    private final long intervalMs;

    final History history = new History();
    private final HealthRules rules = new HealthRules();
    private final Map<String, NodeState> nodes = new ConcurrentHashMap<>();
    /** A lock, not a monitor: a virtual thread blocking inside {@code synchronized} pins its carrier. */
    private final ReentrantLock pollLock = new ReentrantLock(true);
    /** How long a request waits for the first poll before answering 503. */
    static final long FIRST_POLL_WAIT_MS = 25_000;
    /** Pause between polls even when a poll took longer than the interval (slow connects never spin). */
    static final long MIN_PAUSE_MS = 1_000;
    static final long DISK_REFRESH_MS = 60_000;
    static final long EXPORTER_RETRY_MS = 5 * 60_000;
    private volatile boolean running;
    private volatile Thread thread;
    private volatile ClusterSnapshot latest;
    private volatile List<NodeInfo> lastNodes = List.of();

    /** Per node: its stateful readers, the last successful snapshot and the last outcome. */
    private static final class NodeState {
        final NodeReader jmxReader = new NodeReader();
        final ExporterReader exporterReader = new ExporterReader();
        final AtomicBoolean inFlight = new AtomicBoolean();
        /** Data dir sizes from df over SSH, refreshed every {@link #DISK_REFRESH_MS}. */
        List<DataDir> disk;
        long diskAtMs;
        String diskError;
        /** True while JMX is unreachable and the node is read from its jmx_exporter instead. */
        boolean viaExporter;
        /** After the exporter fails too, it is not tried again before this time (it may only time out). */
        long exporterRetryAtMs;
        NodeSnapshot lastOk;
        long lastOkAtMs;
        volatile NodeAccess access;
    }

    Poller(String connectionId, ConnectionConfig cfg, Map<String, String> secrets, JmxAccess jmx, Topology topology,
           ExecutorService executor, Semaphore permits, LongSupplier clock, Supplier<Thresholds> thresholds,
           long intervalMs) {
        this.connectionId = connectionId;
        this.cfg = cfg;
        this.secrets = secrets;
        this.jmx = jmx;
        this.topology = topology;
        this.executor = executor;
        this.permits = permits;
        this.clock = clock;
        this.thresholds = thresholds;
        this.intervalMs = intervalMs;
    }

    int intervalSec() {
        return (int) Math.max(1, intervalMs / 1000);
    }

    long intervalMs() {
        return intervalMs;
    }

    boolean running() {
        return running;
    }

    ClusterSnapshot latest() {
        return latest;
    }

    ConnectionConfig config() {
        return cfg;
    }

    Map<String, String> secrets() {
        return secrets;
    }

    /** Per-node read timeout: most of the interval, at least 1.5 s, at most 15 s. */
    long timeoutMs() {
        return Math.min(15_000, Math.max(1_500, intervalMs * 8 / 10));
    }

    synchronized void start() {
        if (running) return;
        running = true;
        thread = Thread.ofVirtual().name("monitoring-" + connectionId).start(this::loop);
    }

    synchronized void stop() {
        running = false;
        if (thread != null) thread.interrupt();
        thread = null;
    }

    private void loop() {
        while (running) {
            long started = System.nanoTime();
            try {
                pollOnce();
            } catch (RuntimeException e) {
                LOG.warn("Monitoring poll failed for connection {}: {}", connectionId, e.toString());
            }
            long sleepMs = Math.max(Math.min(MIN_PAUSE_MS, intervalMs), intervalMs - (System.nanoTime() - started) / 1_000_000);
            try {
                if (sleepMs > 0) Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    AccessStatus status() {
        List<NodeAccess> access = new ArrayList<>();
        for (NodeInfo n : lastNodes) {
            NodeState s = nodes.get(n.address());
            if (s != null && s.access != null) access.add(s.access);
        }
        return new AccessStatus(cfg.jmx().method().name(), running, intervalSec(), access);
    }

    /** One poll. Polls never run concurrently: a second caller waits for the first. */
    ClusterSnapshot pollOnce() {
        pollLock.lock();
        try {
            return doPoll();
        } finally {
            pollLock.unlock();
        }
    }

    /** The latest snapshot, polling now if there is none yet (first request right after start). */
    ClusterSnapshot latestOrPoll() {
        ClusterSnapshot s = latest;
        if (s != null) return s;
        // Wait for the first poll that is under way (a cluster that hangs on connect can take the driver's
        // whole connect timeout), but never longer than FIRST_POLL_WAIT_MS: the request must answer.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FIRST_POLL_WAIT_MS);
        try {
            while (System.nanoTime() < deadline) {
                if (pollLock.tryLock(100, TimeUnit.MILLISECONDS)) {
                    try {
                        return latest != null ? latest : doPoll();
                    } finally {
                        pollLock.unlock();
                    }
                }
                s = latest;
                if (s != null) return s;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        s = latest;
        if (s != null) return s;
        throw new com.cassandrastudio.engine.util.ApiException(503, "first_poll_pending",
                "The first poll of this cluster is still running (cluster slow or unreachable); try again shortly");
    }

    private ClusterSnapshot doPoll() {
        long now = clock.getAsLong();
        ClusterInfo info;
        boolean schemaAgreement;
        try {
            info = topology.info(connectionId);
            lastNodes = info.nodes();
            schemaAgreement = info.schemaAgreement();
        } catch (RuntimeException e) {
            if (lastNodes.isEmpty()) {
                String reason = "Cluster metadata unavailable: " + e.getMessage();
                latest = new ClusterSnapshot(now, intervalSec(), new Health(Level.RED, List.of(reason)), List.of(),
                        List.of(), false);
                return latest;
            }
            info = null;
            schemaAgreement = true; // unknown: do not raise a disagreement we cannot see
        }
        List<NodeInfo> infos = info == null ? lastNodes.stream().map(Poller::unknownState).toList() : lastNodes;
        nodes.keySet().retainAll(infos.stream().map(NodeInfo::address).toList());

        Map<String, Future<NodeReader.Result>> futures = new LinkedHashMap<>();
        Map<String, Permit> held = new HashMap<>();
        Map<String, String> skipped = new HashMap<>();
        // Nodes that answered last time first: with a fair semaphore they get read slots before nodes that
        // failed (and may hang again), so a dead DC cannot starve the healthy ones (NFR-RELI at 500 nodes).
        List<NodeInfo> order = new ArrayList<>(infos);
        order.sort(Comparator.comparing((NodeInfo n) -> {
            NodeState st = nodes.get(n.address());
            return st != null && st.access != null && !st.access.ok();
        }));
        for (NodeInfo n : order) {
            NodeState st = nodes.computeIfAbsent(n.address(), k -> new NodeState());
            if (!st.inFlight.compareAndSet(false, true)) {
                skipped.put(n.address(), "Previous read is still running (node slow or hung)");
                continue;
            }
            Permit permit = new Permit(permits);
            held.put(n.address(), permit);
            futures.put(n.address(), executor.submit(() -> {
                try {
                    if (!permit.acquire()) throw new TimeoutException("Not read: the poll's time ran out");
                    try {
                        return read(n, st, clock.getAsLong());
                    } finally {
                        permit.release();
                    }
                } finally {
                    st.inFlight.set(false);
                }
            }));
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs());
        Map<String, NodeReader.Result> results = new HashMap<>();
        Map<String, String> errors = new HashMap<>(skipped);
        futures.forEach((address, f) -> {
            try {
                results.put(address, f.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            } catch (TimeoutException e) {
                // not cancelled: the read keeps its in-flight mark until JmxAccess's own timeout ends it,
                // but gives its read slot back (a hung connection puts no load anywhere)
                held.get(address).release();
                errors.put(address, "Timed out after " + timeoutMs() / 1000.0 + " s");
            } catch (ExecutionException e) {
                errors.put(address, message(e.getCause()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errors.put(address, "Interrupted");
            }
        });

        NodeReader.Gossip gossip = results.values().stream().map(NodeReader.Result::gossip)
                .filter(g -> !g.live().isEmpty()).findFirst().orElse(NodeReader.Gossip.EMPTY);
        List<NodeSnapshot> snapshots = new ArrayList<>();
        for (NodeInfo n : infos) {
            NodeState st = nodes.get(n.address());
            NodeReader.Result r = results.get(n.address());
            NodeSnapshot snap;
            if (r != null) {
                snap = NodeBuilder.withState(r.snapshot(), NodeStates.state(n, true, r.operationMode(), gossip));
                record(st, snap, r.gcPauseMs(), now);
                st.access = new NodeAccess(n.address(), true, snap.route(), null, now);
            } else {
                String err = errors.getOrDefault(n.address(), "Not read");
                String route = st.access == null ? null : st.access.route();
                snap = NodeBuilder.withState(NodeBuilder.failed(n.hostId(), n.address(), n.datacenter(), n.rack(),
                        n.version(), route, err), NodeStates.state(n, false, null, gossip));
                st.access = new NodeAccess(n.address(), false, route, err, now);
            }
            snapshots.add(snap);
        }
        snapshots.sort(Comparator.comparing(NodeSnapshot::datacenter, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(NodeSnapshot::rack, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(NodeSnapshot::address));
        history.retainNodes(Set.copyOf(infos.stream().map(NodeInfo::address).toList()));
        List<Alert> alerts = rules.evaluate(snapshots, schemaAgreement, thresholds.get(), now);
        latest = new ClusterSnapshot(now, intervalSec(), HealthRules.health(alerts), List.copyOf(snapshots),
                alerts, schemaAgreement);
        return latest;
    }

    private NodeReader.Result read(NodeInfo n, NodeState st, long now) {
        NodeEndpoint ep = new NodeEndpoint(n.hostId(), n.address(), n.datacenter(), n.rack(), n.version());
        NodeReader.Identity id = new NodeReader.Identity(n.hostId(), n.address(), n.datacenter(), n.rack(), n.version());
        try {
            if (cfg.jmx().method() == JmxMethod.EXPORTER) {
                return st.exporterReader.read(jmx.scrapeExporter(cfg, ep), id, now);
            }
            JmxAccess.JmxSession session;
            try {
                session = jmx.session(cfg, secrets, ep);
            } catch (JmxAccess.JmxUnavailableException e) {
                return exporterFallback(ep, id, st, now, e);
            }
            if (st.viaExporter) {
                st.viaExporter = false;
                st.exporterReader.reset();
            }
            NodeReader.Result r = st.jmxReader.read(session.mbeans(), session.route(), id, now);
            return withDisk(r, session, st, now);
        } catch (RuntimeException e) {
            st.jmxReader.reset();
            st.exporterReader.reset();
            throw e;
        }
    }

    /**
     * JMX is unreachable: read the node's jmx_exporter instead when it answers (CON-7), so health
     * and charts keep going. If the exporter fails too, the JMX reason is the one reported.
     */
    private NodeReader.Result exporterFallback(NodeEndpoint ep, NodeReader.Identity id, NodeState st, long now,
                                               JmxAccess.JmxUnavailableException jmxFailure) {
        if (now < st.exporterRetryAtMs) throw jmxFailure;
        List<JmxAccess.ExporterSample> samples;
        try {
            samples = jmx.scrapeExporter(cfg, ep);
        } catch (RuntimeException exporterFailure) {
            st.viaExporter = false;
            st.exporterRetryAtMs = now + EXPORTER_RETRY_MS;
            throw jmxFailure;
        }
        if (!st.viaExporter) {
            st.viaExporter = true;
            st.jmxReader.reset();
            st.exporterReader.reset();
        }
        NodeReader.Result r = st.exporterReader.read(samples, id, now);
        NodeBuilder b = NodeBuilder.of(r.snapshot());
        b.route = "jmx_exporter (JMX unreachable: " + message(jmxFailure) + ")";
        return new NodeReader.Result(b.build(), r.operationMode(), r.gossip(), r.gcPauseMs());
    }

    /** Fills data dir sizes from df over SSH; skipped without SSH, and a failure only leaves them unknown. */
    private NodeReader.Result withDisk(NodeReader.Result r, JmxAccess.JmxSession session, NodeState st, long now) {
        List<DataDir> dirs = r.snapshot().dataDirs();
        if (dirs == null || dirs.isEmpty()) return r;
        if (st.disk == null || now - st.diskAtMs >= DISK_REFRESH_MS || !samePaths(st.disk, dirs)) {
            try {
                String out = session.exec(DiskUsage.command(dirs), Duration.ofSeconds(5));
                if (out == null) return r; // no SSH on this route
                st.disk = DiskUsage.apply(dirs, out);
                st.diskError = null;
            } catch (RuntimeException e) {
                st.disk = dirs;
                String m = message(e);
                if (!m.equals(st.diskError)) LOG.info("disk usage of {} unknown: {}", r.snapshot().address(), m);
                st.diskError = m;
            }
            st.diskAtMs = now;
        }
        NodeBuilder b = NodeBuilder.of(r.snapshot());
        b.dataDirs = st.disk;
        return new NodeReader.Result(b.build(), r.operationMode(), r.gossip(), r.gcPauseMs());
    }

    private static boolean samePaths(List<DataDir> a, List<DataDir> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).path().equals(b.get(i).path())) return false;
        return true;
    }

    private void record(NodeState st, NodeSnapshot snap, Double gcPauseMs, long now) {
        double dt = st.lastOk == null ? 0 : (now - st.lastOkAtMs) / 1000.0;
        SeriesMetrics.Sample sample = new SeriesMetrics.Sample(snap, st.lastOk, dt, gcPauseMs);
        for (SeriesMetrics.Def d : SeriesMetrics.ALL.values()) {
            history.add(d.id(), snap.address(), now, d.extract().apply(sample));
        }
        st.lastOk = snap;
        st.lastOkAtMs = now;
    }

    /**
     * One read's slot in the shared limit of concurrent node reads. Released once, by whichever comes
     * first: the read finishing or the poll giving up on it; a read given up on before it got a slot
     * does not start.
     */
    static final class Permit {
        private final Semaphore permits;
        /** 0 = waiting, 1 = holding, 2 = released or abandoned. */
        private final java.util.concurrent.atomic.AtomicInteger state = new java.util.concurrent.atomic.AtomicInteger();

        Permit(Semaphore permits) {
            this.permits = permits;
        }

        boolean acquire() throws InterruptedException {
            permits.acquire();
            if (state.compareAndSet(0, 1)) return true;
            permits.release();
            return false;
        }

        void release() {
            if (state.getAndSet(2) == 1) permits.release();
        }
    }

    private static NodeInfo unknownState(NodeInfo n) {
        return new NodeInfo(n.hostId(), n.address(), n.cqlPort(), n.datacenter(), n.rack(), n.version(), "UNKNOWN",
                n.tokens(), n.schemaVersion(), n.openConnections());
    }

    static String message(Throwable t) {
        if (t instanceof UnsupportedOperationException) {
            return t.getMessage() == null ? "This JMX access method is not supported" : t.getMessage();
        }
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }
}
