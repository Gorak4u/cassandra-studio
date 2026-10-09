package com.cassandrastudio.engine.metrics;

import com.cassandrastudio.engine.metrics.MonitoringModel.Alert;
import com.cassandrastudio.engine.metrics.MonitoringModel.DataDir;
import com.cassandrastudio.engine.metrics.MonitoringModel.Health;
import com.cassandrastudio.engine.metrics.MonitoringModel.Level;
import com.cassandrastudio.engine.metrics.MonitoringModel.NodeSnapshot;
import com.cassandrastudio.engine.metrics.MonitoringModel.ThreadPool;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * ALR-1 built-in health rules for one cluster (docs/api/monitoring.md), evaluated on every
 * poll. Stateful: remembers the previous poll for "increased since" rules, consecutive polls
 * for hints, and when each alert started. An alert id is {@code rule:node} (or the rule alone
 * for cluster-wide rules) and keeps its {@code sinceEpochMs} while it stays active.
 */
public final class HealthRules {
    private Map<String, NodeSnapshot> previous = Map.of();
    private final Map<String, Integer> hintPolls = new HashMap<>();
    private Map<String, Long> since = Map.of();

    public synchronized List<Alert> evaluate(List<NodeSnapshot> nodes, boolean schemaAgreement, Thresholds thresholds,
                                             long nowMs) {
        Thresholds t = thresholds == null ? Thresholds.DEFAULTS : thresholds.effective();
        List<Draft> drafts = new ArrayList<>();
        Map<String, NodeSnapshot> next = new HashMap<>(previous);
        for (NodeSnapshot n : nodes) {
            String a = n.address();
            if (n.state() != null && !"UN".equals(n.state()) && !n.state().startsWith("?")) {
                drafts.add(new Draft("node.down", Level.RED, a, a + " is " + n.state(), null, null));
            }
            if (n.error() != null) {
                drafts.add(new Draft("node.unreachable", Level.YELLOW, a,
                        "Cannot read metrics from " + a + ": " + n.error(), null, null));
                continue;
            }
            nodeRules(n, previous.get(a), t, drafts);
            next.put(a, n);
        }
        if (!schemaAgreement) {
            drafts.add(new Draft("schema.disagreement", Level.YELLOW, null,
                    "Schema versions disagree between live nodes", null, null));
        }
        loadImbalance(nodes, t, drafts);
        previous = next;

        Map<String, Long> nextSince = new HashMap<>();
        List<Alert> alerts = new ArrayList<>();
        for (Draft d : drafts) {
            String id = d.node == null ? d.rule : d.rule + ":" + d.node;
            long s = since.getOrDefault(id, nowMs);
            nextSince.put(id, s);
            alerts.add(new Alert(id, d.level, d.rule, d.node, d.message, d.value, d.threshold, s));
        }
        since = nextSince;
        alerts.sort(Comparator.comparing(Alert::level).reversed().thenComparing(Alert::id));
        return alerts;
    }

    /** Overall health = worst alert level; reasons = alert messages, worst first. */
    public static Health health(List<Alert> alerts) {
        Level worst = Level.GREEN;
        List<String> reasons = new ArrayList<>();
        for (Alert a : alerts) {
            if (a.level().compareTo(worst) > 0) worst = a.level();
            reasons.add(a.message());
        }
        return new Health(worst, reasons);
    }

    private record Draft(String rule, Level level, String node, String message, Double value, Double threshold) {}

    private void nodeRules(NodeSnapshot n, NodeSnapshot prev, Thresholds t, List<Draft> out) {
        String a = n.address();
        if (n.heapUsedBytes() != null && n.heapMaxBytes() != null && n.heapMaxBytes() > 0) {
            double pct = 100.0 * n.heapUsedBytes() / n.heapMaxBytes();
            level(out, "heap.high", a, pct, t.heapYellowPct(), t.heapRedPct(), "Heap on %s at %.0f %% of max");
        }
        if (n.gcTimePct() != null) {
            level(out, "gc.pressure", a, n.gcTimePct(), t.gcYellowPct(), t.gcRedPct(),
                    "GC on %s took %.1f %% of wall time");
        }
        if (prev != null) {
            Map<String, Long> verbs = increases(prev.dropped(), n.dropped());
            if (!verbs.isEmpty()) {
                long total = verbs.values().stream().mapToLong(Long::longValue).sum();
                out.add(new Draft("dropped.messages", Level.YELLOW, a, total + " messages dropped on " + a
                        + " since the previous poll (" + String.join(", ", verbs.keySet()) + ")", (double) total, 0.0));
            }
            Long tNow = sum(SeriesMetrics.timeouts(n), SeriesMetrics.unavailables(n));
            Long tPrev = sum(SeriesMetrics.timeouts(prev), SeriesMetrics.unavailables(prev));
            if (tNow != null && tPrev != null && tNow > tPrev) {
                out.add(new Draft("client.timeouts", Level.YELLOW, a, (tNow - tPrev)
                        + " client timeouts/unavailables on " + a + " since the previous poll", (double) (tNow - tPrev), 0.0));
            }
        }
        if (n.pendingCompactions() != null && n.pendingCompactions() > t.compactionPending()) {
            out.add(new Draft("compaction.backlog", Level.YELLOW, a, n.pendingCompactions() + " pending compactions on "
                    + a, n.pendingCompactions().doubleValue(), t.compactionPending().doubleValue()));
        }
        List<String> blocked = blockedPools(n, prev);
        if (!blocked.isEmpty()) {
            out.add(new Draft("threadpool.blocked", Level.YELLOW, a, "Blocked thread pools on " + a + ": "
                    + String.join(", ", blocked), (double) blocked.size(), 0.0));
        }
        disk(n, t, out);
        if (n.hintsInProgress() != null) {
            int polls = n.hintsInProgress() > 0 ? hintPolls.merge(a, 1, Integer::sum) : 0;
            if (polls == 0) hintPolls.remove(a);
            if (polls >= t.hintsPolls()) {
                out.add(new Draft("hints.backlog", Level.YELLOW, a, n.hintsInProgress() + " hints in progress on " + a
                        + " for " + polls + " polls", n.hintsInProgress().doubleValue(), (double) t.hintsPolls()));
            }
        }
    }

    private static void level(List<Draft> out, String rule, String node, double value, double yellow, double red,
                              String format) {
        if (value <= yellow) return;
        Level l = value > red ? Level.RED : Level.YELLOW;
        out.add(new Draft(rule, l, node, String.format(Locale.ROOT, format, node, value), value,
                l == Level.RED ? red : yellow));
    }

    private static Map<String, Long> increases(Map<String, Long> before, Map<String, Long> now) {
        Map<String, Long> out = new TreeMap<>();
        if (before == null || now == null) return out;
        now.forEach((verb, v) -> {
            Long b = before.get(verb);
            if (b != null && v != null && v > b) out.put(verb, v - b);
        });
        return out;
    }

    private static List<String> blockedPools(NodeSnapshot n, NodeSnapshot prev) {
        List<String> out = new ArrayList<>();
        if (n.threadPools() == null) return out;
        Map<String, ThreadPool> before = new HashMap<>();
        if (prev != null && prev.threadPools() != null) prev.threadPools().forEach(p -> before.put(p.name(), p));
        for (ThreadPool p : n.threadPools()) {
            ThreadPool b = before.get(p.name());
            boolean now = p.blocked() != null && p.blocked() > 0;
            boolean grew = b != null && b.allTimeBlocked() != null && p.allTimeBlocked() != null
                    && p.allTimeBlocked() > b.allTimeBlocked();
            if (now || grew) out.add(p.name());
        }
        return out;
    }

    private static void disk(NodeSnapshot n, Thresholds t, List<Draft> out) {
        if (n.dataDirs() == null) return;
        DataDir worst = null;
        double worstPct = -1;
        for (DataDir d : n.dataDirs()) {
            if (d.totalBytes() == null || d.freeBytes() == null || d.totalBytes() <= 0) continue;
            double pct = 100.0 * (d.totalBytes() - d.freeBytes()) / d.totalBytes();
            if (pct > worstPct) {
                worstPct = pct;
                worst = d;
            }
        }
        if (worst != null) {
            level(out, "disk.usage", n.address(), worstPct, t.diskYellowPct(), t.diskRedPct(),
                    "Data directory " + worst.path().replace("%", "%%") + " on %s is %.0f %% full");
        }
    }

    private static void loadImbalance(List<NodeSnapshot> nodes, Thresholds t, List<Draft> out) {
        Map<String, List<NodeSnapshot>> byDc = new LinkedHashMap<>();
        for (NodeSnapshot n : nodes) {
            if (n.loadBytes() != null) byDc.computeIfAbsent(String.valueOf(n.datacenter()), k -> new ArrayList<>()).add(n);
        }
        byDc.forEach((dc, list) -> {
            if (list.size() < 2) return;
            double avg = list.stream().mapToLong(NodeSnapshot::loadBytes).average().orElse(0);
            if (avg <= 0) return;
            for (NodeSnapshot n : list) {
                double ratio = n.loadBytes() / avg;
                if (ratio > t.loadImbalanceFactor()) {
                    out.add(new Draft("load.imbalance", Level.YELLOW, n.address(), String.format(Locale.ROOT,
                            "Load on %s is %.1fx the %s average", n.address(), ratio, dc), ratio, t.loadImbalanceFactor()));
                }
            }
        });
    }

    private static Long sum(Long a, Long b) {
        return a == null && b == null ? null : (a == null ? 0 : a) + (b == null ? 0 : b);
    }
}
