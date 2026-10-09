package com.cassandrastudio.engine.metrics;

import com.cassandrastudio.engine.jmx.JmxAccess.ExporterSample;
import com.cassandrastudio.engine.metrics.MetricCatalog.Exporter;
import com.cassandrastudio.engine.metrics.MonitoringModel.ClientRequests;
import com.cassandrastudio.engine.metrics.MonitoringModel.GcCollector;
import com.cassandrastudio.engine.metrics.MonitoringModel.Latency;
import com.cassandrastudio.engine.metrics.MonitoringModel.TableMetrics;
import com.cassandrastudio.engine.metrics.MonitoringModel.ThreadPool;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Builds a node snapshot from jmx_exporter samples (EXPORTER method, CON-6). Best effort over
 * the standard Cassandra exporter config: fields the scrape does not carry stay null. Request
 * rates are derived from counter deltas between scrapes because that config exports counts only.
 */
public final class ExporterReader {
    private final GcTracker gc = new GcTracker();
    private final Map<String, Long> previousCounts = new HashMap<>();
    private long previousAtMs = -1;

    public NodeReader.Result read(List<ExporterSample> samples, NodeReader.Identity id, long nowMs) {
        NodeBuilder b = new NodeBuilder();
        b.hostId = id.hostId();
        b.address = id.address();
        b.datacenter = id.datacenter();
        b.rack = id.rack();
        b.route = "jmx_exporter";
        b.cassandraVersion = id.version();
        b.heapUsedBytes = asLong(value(samples, Exporter.HEAP_USED, label("area", "heap")));
        b.heapMaxBytes = asLong(value(samples, Exporter.HEAP_MAX, label("area", "heap")));
        ExporterSample info = first(samples, Exporter.JVM_INFO, x -> true);
        if (info != null) {
            b.javaVersion = info.labels().get("version");
            b.javaVendor = info.labels().get("vendor");
        }
        Double start = value(samples, Exporter.START_TIME, x -> true);
        b.uptimeSec = start == null ? null : Math.max(0, nowMs / 1000 - start.longValue());
        b.openFds = asLong(value(samples, Exporter.OPEN_FDS, x -> true));
        b.maxFds = asLong(value(samples, Exporter.MAX_FDS, x -> true));
        b.loadBytes = asLong(value(samples, Exporter.LOAD, x -> true));
        b.totalHints = asLong(value(samples, Exporter.TOTAL_HINTS, x -> true));
        b.hintsInProgress = asLong(value(samples, Exporter.HINTS_IN_PROGRESS, x -> true));
        b.pendingCompactions = asLong(value(samples, Exporter.PENDING_COMPACTIONS, x -> true));
        b.completedCompactions = asLong(value(samples, Exporter.COMPLETED_COMPACTIONS, x -> true));
        b.liveSSTables = asLong(value(samples, Exporter.LIVE_SSTABLES,
                x -> blank(x.labels().get("keyspace")) && blank(x.labels().get("table"))));

        b.gc = gcCollectors(samples);
        GcTracker.Delta d = gc.update(b.gc, nowMs);
        b.gcTimePct = d.timePct();
        b.threadPools = threadPools(samples);
        b.dropped = dropped(samples);
        b.clientRequests = clientRequests(samples, nowMs);
        return new NodeReader.Result(b.build(), null, NodeReader.Gossip.EMPTY, d.pauseMs());
    }

    public void reset() {
        gc.reset();
        previousCounts.clear();
        previousAtMs = -1;
    }

    /** MON-18 per-table values of one node from {@code cassandra_table_*{keyspace,table}} samples. */
    public static List<TableMetrics> tables(List<ExporterSample> samples, String keyspace) {
        Map<String, Map<String, Double>> byTable = new TreeMap<>();
        for (ExporterSample s : samples) {
            String ks = s.labels().get("keyspace"), table = s.labels().get("table");
            if (blank(ks) || blank(table) || !s.name().startsWith(Exporter.TABLE_PREFIX)) continue;
            if (keyspace == null ? !NodeReader.userKeyspace(ks) : !keyspace.equals(ks)) continue;
            String q = s.labels().get("quantile");
            String metric = s.name().substring(Exporter.TABLE_PREFIX.length()) + (q == null ? "" : "@" + q);
            byTable.computeIfAbsent(ks + "." + table, k -> new HashMap<>()).put(metric, s.value());
        }
        List<TableMetrics> out = new ArrayList<>();
        byTable.forEach((key, v) -> {
            int dot = key.indexOf('.');
            out.add(new TableMetrics(key.substring(0, dot), key.substring(dot + 1), asLong(v.get("readlatency")),
                    asLong(v.get("writelatency")), v.get("readlatency@0.99"), v.get("writelatency@0.99"),
                    asLong(v.get("livediskspaceused")), asLong(v.get("totaldiskspaceused")),
                    asLong(v.get("livesstablecount")), asLong(v.get("meanpartitionsize")),
                    asLong(v.get("maxpartitionsize")), v.get("tombstonescannedhistogram@0.99"),
                    v.get("sstablesperreadhistogram@0.99"), v.get("bloomfilterfalseratio"),
                    asLong(v.get("pendingcompactions")), v.get("keycachehitrate")));
        });
        return out;
    }

    private static List<GcCollector> gcCollectors(List<ExporterSample> samples) {
        Map<String, double[]> byName = new TreeMap<>();
        for (ExporterSample s : samples) {
            String name = s.labels().get("gc");
            if (name == null) continue;
            if (Exporter.GC_COUNT.contains(s.name())) byName.computeIfAbsent(name, k -> new double[2])[0] = s.value();
            if (Exporter.GC_SECONDS.contains(s.name())) byName.computeIfAbsent(name, k -> new double[2])[1] = s.value();
        }
        if (byName.isEmpty()) return null;
        List<GcCollector> out = new ArrayList<>();
        byName.forEach((n, v) -> out.add(new GcCollector(n, (long) v[0], Math.round(v[1] * 1000))));
        return out;
    }

    private static List<ThreadPool> threadPools(List<ExporterSample> samples) {
        Map<String, Map<String, Long>> byPool = new TreeMap<>();
        for (ExporterSample s : samples) {
            String pool = s.labels().get(Exporter.THREAD_POOL_LABEL);
            if (pool == null || !s.name().startsWith(Exporter.THREAD_POOL_PREFIX)) continue;
            byPool.computeIfAbsent(pool, k -> new HashMap<>())
                    .put(s.name().substring(Exporter.THREAD_POOL_PREFIX.length()), (long) s.value());
        }
        if (byPool.isEmpty()) return null;
        List<ThreadPool> out = new ArrayList<>();
        byPool.forEach((p, v) -> out.add(new ThreadPool(p, v.get("activetasks"), v.get("pendingtasks"),
                v.get("currentlyblockedtasks"), v.get("completedtasks"), v.get("totalblockedtasks"))));
        return out;
    }

    private static Map<String, Long> dropped(List<ExporterSample> samples) {
        Map<String, Long> out = new TreeMap<>();
        for (ExporterSample s : samples) {
            String verb = s.labels().get(Exporter.DROPPED_LABEL);
            if (verb != null && s.name().equals(Exporter.DROPPED)) out.put(verb, (long) s.value());
        }
        return out.isEmpty() ? null : out;
    }

    private ClientRequests clientRequests(List<ExporterSample> samples, long nowMs) {
        Map<String, Map<String, Double>> byScope = new HashMap<>();
        for (ExporterSample s : samples) {
            String scope = s.labels().get(Exporter.CLIENT_LABEL);
            if (scope == null || !s.name().startsWith(Exporter.CLIENT_PREFIX)) continue;
            String metric = s.name().substring(Exporter.CLIENT_PREFIX.length());
            String q = s.labels().get("quantile");
            byScope.computeIfAbsent(scope, k -> new HashMap<>()).put(q == null ? metric : metric + "@" + q, s.value());
        }
        double dtSec = previousAtMs < 0 ? 0 : (nowMs - previousAtMs) / 1000.0;
        previousAtMs = nowMs;
        if (byScope.isEmpty()) return null;
        Map<String, Latency> lat = new HashMap<>();
        for (String scope : MetricCatalog.CLIENT_SCOPES) {
            Map<String, Double> v = byScope.get(scope);
            if (v == null || !v.containsKey("latency")) continue;
            long count = v.get("latency").longValue();
            Long prev = previousCounts.put(scope, count);
            Double rate = prev == null || dtSec <= 0 || count < prev ? null : (count - prev) / dtSec;
            lat.put(scope, new Latency(v.get("latency@0.5"), v.get("latency@0.95"), v.get("latency@0.99"),
                    v.get("latency@1.0"), rate, count));
        }
        return new ClientRequests(lat.get("Read"), lat.get("Write"), lat.get("RangeSlice"), lat.get("CASRead"),
                lat.get("CASWrite"), count(byScope, "Read", "timeouts"), count(byScope, "Write", "timeouts"),
                count(byScope, "Read", "unavailables"), count(byScope, "Write", "unavailables"),
                count(byScope, "Read", "failures"), count(byScope, "Write", "failures"));
    }

    private static Long count(Map<String, Map<String, Double>> byScope, String scope, String metric) {
        Map<String, Double> v = byScope.get(scope);
        return v == null || v.get(metric) == null ? null : v.get(metric).longValue();
    }

    private static ExporterSample first(List<ExporterSample> samples, List<String> names, Predicate<ExporterSample> p) {
        for (String n : names) {
            for (ExporterSample s : samples) {
                if (s.name().toLowerCase(Locale.ROOT).equals(n) && p.test(s)) return s;
            }
        }
        return null;
    }

    private static Double value(List<ExporterSample> samples, List<String> names, Predicate<ExporterSample> p) {
        ExporterSample s = first(samples, names, p);
        return s == null || Double.isNaN(s.value()) ? null : s.value();
    }

    private static Predicate<ExporterSample> label(String key, String value) {
        return s -> value.equals(s.labels().get(key));
    }

    private static boolean blank(String s) {
        return s == null || s.isEmpty();
    }

    private static Long asLong(Double d) {
        return d == null ? null : d.longValue();
    }
}
