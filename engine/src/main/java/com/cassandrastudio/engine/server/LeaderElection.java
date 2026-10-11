package com.cassandrastudio.engine.server;

import com.cassandrastudio.engine.store.Database;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Picks the one Studio Server instance that fires schedules (SRV-7): a lease row in the shared store
 * ({@code server_leader}), renewed every quarter lease by its holder and taken over by another instance
 * once it has expired. Times are the database's clock, so the instances' clocks need not agree. The
 * holder believes it leads only until three quarters of the lease after its last successful renewal, so a
 * paused or cut-off leader stops before anyone else can start; schedules additionally claim each run with
 * a compare-and-set ({@code ScheduleService}), so a run never fires twice.
 *
 * <p>A cleanly stopped leader gives the lease up at once; a crashed one is replaced after at most one
 * lease (default 20 s) plus one renewal interval.
 */
public final class LeaderElection implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(LeaderElection.class);
    static final String NAME = "scheduler";

    private final Database db;
    private final String instanceId;
    private final String host;
    private final String version;
    private final long leaseMs;
    private final Runnable onElected;
    private final long startedMs = System.currentTimeMillis();
    private final ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "leader-election");
        t.setDaemon(true);
        return t;
    });
    private volatile long validUntilNanos;
    private volatile boolean leader;
    private volatile String lastError;

    public LeaderElection(Database db, String instanceId, String host, String version, long leaseMs, Runnable onElected) {
        this.db = db;
        this.instanceId = instanceId;
        this.host = host;
        this.version = version;
        this.leaseMs = leaseMs;
        this.onElected = onElected;
    }

    public void start() {
        loop.scheduleWithFixedDelay(this::round, 0, leaseMs / 4, TimeUnit.MILLISECONDS);
    }

    /** True while this instance holds an unexpired lease. */
    public boolean isLeader() {
        return leader && System.nanoTime() - validUntilNanos < 0;
    }

    public String instanceId() {
        return instanceId;
    }

    public String lastError() {
        return lastError;
    }

    private String nowMs() {
        return db.dialect() == Database.Dialect.POSTGRESQL
                ? "CAST(EXTRACT(EPOCH FROM clock_timestamp()) * 1000 AS BIGINT)"
                : "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)";
    }

    /** One renewal or takeover attempt; package-private for tests. */
    void round() {
        long t0 = System.nanoTime();
        boolean was = isLeader();
        try {
            heartbeat();
            String now = nowMs();
            int n = db.update("INSERT INTO server_leader(name, holder, since_ms, expires_ms) VALUES (?, ?, " + now + ", " + now + " + ?)"
                    + " ON CONFLICT(name) DO UPDATE SET holder = excluded.holder,"
                    + " since_ms = CASE WHEN server_leader.holder = excluded.holder THEN server_leader.since_ms ELSE excluded.since_ms END,"
                    + " expires_ms = excluded.expires_ms"
                    + " WHERE server_leader.holder = excluded.holder OR server_leader.expires_ms < excluded.since_ms",
                    NAME, instanceId, leaseMs);
            if (n == 1) {
                validUntilNanos = t0 + TimeUnit.MILLISECONDS.toNanos(leaseMs * 3 / 4);
                leader = true;
            } else {
                leader = false;
            }
            lastError = null;
        } catch (RuntimeException e) {
            leader = false;
            lastError = e.getMessage();
            LOG.warn("Leader election: store not reachable ({}); not firing schedules", e.getMessage());
        }
        boolean is = isLeader();
        if (is && !was) {
            LOG.info("Instance {} is now the leader: it fires schedules", instanceId);
            try {
                onElected.run();
            } catch (RuntimeException e) {
                LOG.warn("After becoming leader: {}", e.toString());
            }
        } else if (was && !is) {
            LOG.info("Instance {} is no longer the leader", instanceId);
        }
    }

    private void heartbeat() {
        db.update("INSERT INTO server_instances(id, host, version, started_ms, heartbeat_ms) VALUES (?,?,?,?,?)"
                        + " ON CONFLICT(id) DO UPDATE SET host = excluded.host, version = excluded.version,"
                        + " started_ms = excluded.started_ms, heartbeat_ms = excluded.heartbeat_ms",
                instanceId, host, version, startedMs, System.currentTimeMillis());
    }

    /** The current lease and every instance seen in the last day, for the status page. */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instanceId", instanceId);
        m.put("leader", isLeader());
        List<Map<String, Object>> lease = db.query("SELECT holder, since_ms, expires_ms FROM server_leader WHERE name = ?", NAME);
        m.put("lease", lease.isEmpty() ? null : Map.of("holder", lease.get(0).get("holder"),
                "sinceMs", lease.get(0).get("since_ms"), "expiresMs", lease.get(0).get("expires_ms")));
        long now = System.currentTimeMillis();
        db.update("DELETE FROM server_instances WHERE heartbeat_ms < ?", now - 86_400_000L);
        List<Map<String, Object>> instances = new java.util.ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT * FROM server_instances ORDER BY id")) {
            long hb = ((Number) r.get("heartbeat_ms")).longValue();
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("id", r.get("id"));
            i.put("host", r.get("host"));
            i.put("version", r.get("version"));
            i.put("startedMs", r.get("started_ms"));
            i.put("heartbeatMs", hb);
            i.put("alive", now - hb < leaseMs * 2);
            i.put("leader", !lease.isEmpty() && r.get("id").equals(lease.get(0).get("holder")));
            instances.add(i);
        }
        m.put("instances", instances);
        if (lastError != null) m.put("lastError", lastError);
        return m;
    }

    /** Stops renewing and gives the lease up, so another instance takes over at once. */
    @Override
    public void close() {
        loop.shutdownNow();
        try {
            loop.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        boolean held = leader;
        leader = false;
        if (held) {
            try {
                db.update("DELETE FROM server_leader WHERE name = ? AND holder = ?", NAME, instanceId);
            } catch (RuntimeException e) {
                LOG.warn("Could not give up the lease: {}", e.getMessage());
            }
        }
        try {
            db.update("DELETE FROM server_instances WHERE id = ?", instanceId);
        } catch (RuntimeException ignored) {
            // the row ages out
        }
    }

    /** Stops renewing without giving the lease up (tests: simulates a crash). */
    void crash() {
        loop.shutdownNow();
        leader = false;
    }
}
