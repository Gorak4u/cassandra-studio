package com.cassandrastudio.engine.metrics;

import com.cassandrastudio.engine.conn.ConnectionRepository;
import com.cassandrastudio.engine.cql.ClusterService.NodeInfo;
import com.cassandrastudio.engine.jmx.JmxAccess;
import com.cassandrastudio.engine.jmx.NodeEndpoint;
import com.cassandrastudio.engine.metrics.MonitoringModel.ClusterSnapshot;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.JmxMethod;
import com.cassandrastudio.engine.secrets.SecretStores;
import com.cassandrastudio.engine.store.Database;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Synthetic large clusters for the scale tests in {@code com.cassandrastudio.engine.perf}: N fake
 * nodes (each its own MBeanServer with the full 4.1 metric set), a fake driver topology and
 * MonitoringService driven by a fake clock. Public so tests in other packages can use the
 * package-private fakes.
 */
public final class ScaleHarness implements AutoCloseable {
    public final Database db = Database.inMemory();
    public final ConnectionRepository repo = new ConnectionRepository(db, SecretStores.inMemory());
    public final String connectionId;
    public final MonitoringService monitoring;
    public final List<String> addresses = new ArrayList<>();
    /** Nodes whose JMX connect hangs (until interrupted or {@link #hangMs}). */
    public final Set<String> hung = ConcurrentHashMap.newKeySet();
    public volatile long hangMs = 60_000;
    private final FakeNodes.Jmx fake = new FakeNodes.Jmx();
    private final FakeNodes.Topo topo = new FakeNodes.Topo();
    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
    private final JmxAccess access;

    /** {@code nodes} nodes spread over {@code dcs} DCs and 3 racks each; JMX through {@code jmx} (null = the fakes). */
    public ScaleHarness(int nodes, int dcs, JmxAccess jmx, int jmxPort) {
        connectionId = repo.save(new ConnectionConfig(null, null, "scale", ConnectionConfig.Environment.DEV, null, false,
                List.of("10.0.0.1"), "dc0", null, null, null, null, null, null,
                new ConnectionConfig.Jmx(JmxMethod.DIRECT, jmxPort, null, false, null, null), null, List.of(), null, null),
                Map.of()).id();
        List<NodeInfo> infos = new ArrayList<>();
        for (int i = 0; i < nodes; i++) {
            String address = jmx == null ? "10.1." + (i / 250) + "." + (i % 250 + 1) : "127.0.0." + (i + 1);
            String host = "h" + i;
            addresses.add(address);
            if (jmx == null) fake.add(FakeNodes.cassandra41G1(address, host));
            infos.add(new NodeInfo(host, address, 9042, "dc" + (i % dcs), "rack" + (i % 3 + 1), "4.1.5", "UP", 16, "s1", 1));
        }
        topo.nodes = infos;
        access = jmx != null ? jmx : new Hanging();
        monitoring = new MonitoringService(db, repo, access, topo, clock::get);
    }

    public ScaleHarness(int nodes, int dcs) {
        this(nodes, dcs, null, 7199);
    }

    /** Starts the poller, waits for its first poll, then stops its loop; returns the interval-based node timeout. */
    public long start(long intervalMs) {
        Poller p = monitoring.startPoller(connectionId, intervalMs);
        long deadline = System.currentTimeMillis() + 120_000;
        while (p.latest() == null && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        p.stop(); // from here on the test drives the polls (no background poll competing for the lock)
        return p.timeoutMs();
    }

    /** Advances the fake clock by {@code ms} and runs one poll now. */
    public ClusterSnapshot poll(long advanceMs) {
        clock.addAndGet(advanceMs);
        return poller().pollOnce();
    }

    public long historyBytes() {
        return poller().history.approxBytes();
    }

    public int historyPoints() {
        return poller().history.pointCount();
    }

    /**
     * Fills every node's history as {@code hours} of polling every {@code stepMs} would leave it: one
     * point a minute until the last hour (only the minute mean is kept there), then every step.
     */
    public void fillHistory(int hours, long stepMs) {
        History h = poller().history;
        long start = clock.get() + stepMs; // after the points the polls already recorded
        long end = clock.addAndGet(stepMs + hours * 3_600_000L);
        long rawFrom = end - History.RAW_MS;
        for (String metric : SeriesMetrics.ALL.keySet()) {
            for (String a : addresses) {
                for (long t = start; t <= end; t += t < rawFrom ? History.MINUTE_MS : stepMs) h.add(metric, a, t, (double) (t % 1000));
            }
        }
    }

    public int jmxSessions() {
        return fake.sessions.get();
    }

    public int maxConcurrentReads() {
        return fake.maxActive.get();
    }

    /** Simulated network latency of each JMX connect on the fakes. */
    public void latencyMs(long ms) {
        fake.delayMs = ms;
    }

    public long now() {
        return clock.get();
    }

    private Poller poller() {
        Poller p = monitoring.pollerFor(connectionId);
        if (p == null) throw new IllegalStateException("not started");
        return p;
    }

    @Override
    public void close() {
        monitoring.close();
        db.close();
    }

    /** The fakes, with some nodes hanging on connect. */
    private final class Hanging implements JmxAccess {
        @Override
        public JmxSession session(ConnectionConfig cfg, Map<String, String> secrets, NodeEndpoint node) {
            if (hung.contains(node.address())) {
                try {
                    Thread.sleep(hangMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new JmxUnavailableException("Read timed out (hung node " + node.address() + ")", null);
            }
            return fake.session(cfg, secrets, node);
        }

        @Override
        public List<ExporterSample> scrapeExporter(ConnectionConfig cfg, NodeEndpoint node) {
            return fake.scrapeExporter(cfg, node);
        }

        @Override
        public void closeConnection(String connectionId) {}

        @Override
        public void close() {}
    }
}
