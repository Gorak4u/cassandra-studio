package com.cassandrastudio.engine.cql;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Up/down of every node as the cluster's gossip sees it, read over CQL from the node Studio is
 * connected to ({@code system_views.gossip_info}, Cassandra 4.1+). A live node's gossip heartbeat
 * goes up about once a second, so a node is UP while its heartbeat (or generation) keeps moving and
 * DOWN once it has stood still for {@link #DOWN_AFTER_MS}. This works when Studio cannot open a
 * connection to the other nodes itself (addresses not routable from this computer, firewalls), where
 * the driver alone would wrongly call them down.
 */
public final class GossipLiveness {
    /** A heartbeat that has not moved for this long means the node is down (gossip marks it down after ~10-20 s). */
    public static final long DOWN_AFTER_MS = 30_000;
    /** Reads closer together than this reuse the last view. */
    static final long REUSE_MS = 2_000;
    /** First look at a cluster: wait this long for a second sample, so live nodes show UP at once. */
    static final long FIRST_SAMPLE_GAP_MS = 1_500;

    /** One gossip_info row. */
    public record Beat(String hostId, long generation, long heartbeat, String status) {}

    /** Reads the rows; returns null when the node has no gossip_info table (Cassandra before 4.1). */
    @FunctionalInterface
    public interface Source {
        List<Beat> read();
    }

    /** Last generation and heartbeat seen for a node; {@code changedAtMs} is MIN_VALUE until it has moved once. */
    private record Seen(long generation, long heartbeat, long changedAtMs, long firstSeenMs) {}

    private static final class Cluster {
        final Map<String, Seen> seen = new HashMap<>();
        final Set<String> left = new HashSet<>();
        long lastReadMs = Long.MIN_VALUE;
        Map<String, String> lastView = Map.of();
        boolean unsupported;
    }

    private final Map<String, Cluster> clusters = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final Sleeper sleeper;

    @FunctionalInterface
    interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    public GossipLiveness() {
        this(System::currentTimeMillis, Thread::sleep);
    }

    GossipLiveness(LongSupplier clock, Sleeper sleeper) {
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /**
     * Host id (lower case) to "UP", "DOWN" or "UNKNOWN" (not enough samples yet), plus "LEFT" for nodes
     * gossip says have left; null when this cluster cannot tell (no gossip_info table, or the read failed).
     */
    public Map<String, String> view(String connectionId, Source source) {
        Cluster c = clusters.computeIfAbsent(connectionId, k -> new Cluster());
        synchronized (c) {
            if (c.unsupported) return null;
            long now = clock.getAsLong();
            boolean neverRead = c.lastReadMs == Long.MIN_VALUE;
            if (!neverRead && now - c.lastReadMs < REUSE_MS) return c.lastView;
            boolean fresh = neverRead || c.seen.isEmpty() || now - c.lastReadMs > DOWN_AFTER_MS;
            if (!sample(c, source, now)) return null;
            if (c.unsupported) return null;
            if (fresh) {
                try {
                    sleeper.sleep(FIRST_SAMPLE_GAP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                now = clock.getAsLong();
                if (!sample(c, source, now)) return null;
            }
            Map<String, String> view = new HashMap<>();
            for (Map.Entry<String, Seen> e : c.seen.entrySet()) {
                Seen s = e.getValue();
                boolean moved = s.changedAtMs() != Long.MIN_VALUE;
                long stillSince = moved ? s.changedAtMs() : s.firstSeenMs();
                String state;
                if (moved && now - s.changedAtMs() <= DOWN_AFTER_MS) state = "UP";
                else if (now - stillSince >= DOWN_AFTER_MS) state = "DOWN";
                else state = "UNKNOWN";
                view.put(e.getKey(), state);
            }
            c.left.forEach(id -> view.put(id, "LEFT"));
            c.lastView = Map.copyOf(view);
            return c.lastView;
        }
    }

    public void forget(String connectionId) {
        clusters.remove(connectionId);
    }

    /** Reads once and records which heartbeats moved; false when the read failed. */
    private static boolean sample(Cluster c, Source source, long now) {
        List<Beat> beats;
        try {
            beats = source.read();
        } catch (RuntimeException e) {
            return false;
        }
        if (beats == null) {
            c.unsupported = true;
            return true;
        }
        c.left.clear();
        Set<String> present = new HashSet<>();
        for (Beat b : beats) {
            if (b.hostId() == null || b.hostId().isBlank()) continue;
            String id = b.hostId().toLowerCase(Locale.ROOT);
            String status = b.status() == null ? "" : b.status().toUpperCase(Locale.ROOT);
            if (status.startsWith("LEFT") || status.startsWith("REMOVED")) {
                c.left.add(id);
                continue;
            }
            present.add(id);
            Seen before = c.seen.get(id);
            if (before == null) {
                c.seen.put(id, new Seen(b.generation(), b.heartbeat(), Long.MIN_VALUE, now));
            } else if (before.generation() != b.generation() || before.heartbeat() != b.heartbeat()) {
                c.seen.put(id, new Seen(b.generation(), b.heartbeat(), now, before.firstSeenMs()));
            }
        }
        c.seen.keySet().retainAll(present);
        c.lastReadMs = now;
        return true;
    }
}
