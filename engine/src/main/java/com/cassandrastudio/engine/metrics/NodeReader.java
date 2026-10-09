package com.cassandrastudio.engine.metrics;

import com.cassandrastudio.engine.metrics.MetricCatalog.Major;
import com.cassandrastudio.engine.metrics.MetricCatalog.Scalar;
import com.cassandrastudio.engine.metrics.MetricCatalog.Source;
import com.cassandrastudio.engine.metrics.MetricCatalog.TableMetric;
import com.cassandrastudio.engine.metrics.MonitoringModel.ClientRequests;
import com.cassandrastudio.engine.metrics.MonitoringModel.DataDir;
import com.cassandrastudio.engine.metrics.MonitoringModel.GcCollector;
import com.cassandrastudio.engine.metrics.MonitoringModel.Latency;
import com.cassandrastudio.engine.metrics.MonitoringModel.NodeSnapshot;
import com.cassandrastudio.engine.metrics.MonitoringModel.TableMetrics;
import com.cassandrastudio.engine.metrics.MonitoringModel.ThreadPool;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

/**
 * Reads one node over JMX (MON-1, MON-12). Stateful per node: keeps GC counters for
 * {@link GcTracker} and caches the names of per-pool / per-verb MBeans, refreshed every
 * {@value #NAME_REFRESH_POLLS} polls, so a steady-state poll is one {@code getAttributes}
 * per MBean and no {@code queryNames} (NFR-PERF). One reader is used by one poll at a time.
 */
public final class NodeReader {
    static final int NAME_REFRESH_POLLS = 30;

    private final GcTracker gc = new GcTracker();
    private final Map<String, Set<ObjectName>> names = new HashMap<>();
    private int polls;
    private String javaVersion;
    private Long lastUptimeMs;

    /** Cluster view as this node's gossip sees it (nodetool status), keyed by bare address. */
    public record Gossip(Set<String> live, Set<String> unreachable, Set<String> joining, Set<String> leaving,
                         Set<String> moving, Map<String, String> hostIdByEndpoint) {
        static final Gossip EMPTY = new Gossip(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Map.of());
    }

    /** One read: the snapshot (state not yet set), the node's own mode, its gossip view, mean GC pause. */
    public record Result(NodeSnapshot snapshot, String operationMode, Gossip gossip, Double gcPauseMs) {}

    /** Driver-side identity of the node being read. */
    public record Identity(String hostId, String address, String datacenter, String rack, String version) {}

    public Result read(MBeanServerConnection conn, String route, Identity id, long nowMs) {
        Mbeans m = new Mbeans(conn);
        if (polls++ % NAME_REFRESH_POLLS == 0) names.clear();
        Map<Scalar, Object> s = readScalars(m, Major.of(id.version()), true);

        NodeBuilder b = new NodeBuilder();
        b.hostId = str(s.get(Scalar.LOCAL_HOST_ID), id.hostId());
        b.address = id.address();
        b.datacenter = id.datacenter();
        b.rack = id.rack();
        b.route = route;
        b.cassandraVersion = str(s.get(Scalar.RELEASE_VERSION), id.version());
        Long uptimeMs = Mbeans.asLong(s.get(Scalar.UPTIME_MS));
        b.uptimeSec = uptimeMs == null ? null : uptimeMs / 1000;
        b.javaVendor = Mbeans.asString(s.get(Scalar.VM_VENDOR));
        b.javaVersion = javaVersion(m, uptimeMs, s);
        b.loadBytes = Mbeans.asLong(s.get(Scalar.LOAD_BYTES));
        b.tokens = s.get(Scalar.LOCAL_TOKENS) instanceof Collection<?> c ? c.size() : null;
        b.heapUsedBytes = Mbeans.compositeLong(s.get(Scalar.HEAP_USAGE), "used");
        b.heapMaxBytes = Mbeans.compositeLong(s.get(Scalar.HEAP_USAGE), "max");
        b.offHeapBytes = sum(s, Scalar.MEMTABLE_OFF_HEAP, Scalar.BLOOM_FILTER_OFF_HEAP, Scalar.INDEX_SUMMARY_OFF_HEAP,
                Scalar.COMPRESSION_OFF_HEAP);
        b.cpuProcessPct = Mbeans.loadPct(s.get(Scalar.PROCESS_CPU_LOAD));
        b.cpuSystemPct = Mbeans.loadPct(s.get(Scalar.SYSTEM_CPU_LOAD));
        b.openFds = Mbeans.asLong(s.get(Scalar.OPEN_FDS));
        b.maxFds = Mbeans.asLong(s.get(Scalar.MAX_FDS));
        b.pendingCompactions = Mbeans.asLong(s.get(Scalar.PENDING_COMPACTIONS));
        b.completedCompactions = Mbeans.asLong(s.get(Scalar.COMPLETED_COMPACTIONS));
        b.activeCompactions = s.get(Scalar.ACTIVE_COMPACTIONS) instanceof Collection<?> c ? (long) c.size() : null;
        b.totalHints = Mbeans.asLong(s.get(Scalar.TOTAL_HINTS));
        b.hintsInProgress = Mbeans.asLong(s.get(Scalar.HINTS_IN_PROGRESS));
        b.liveSSTables = Mbeans.asLong(s.get(Scalar.LIVE_SSTABLES));
        b.dataDirs = dataDirs(s.get(Scalar.DATA_FILE_LOCATIONS));

        b.gc = gcCollectors(m);
        GcTracker.Delta d = gc.update(b.gc, nowMs);
        b.gcTimePct = d.timePct();
        b.threadPools = threadPools(m);
        b.dropped = dropped(m);
        b.clientRequests = clientRequests(m);

        Gossip gossip = new Gossip(addresses(s.get(Scalar.LIVE_NODES)), addresses(s.get(Scalar.UNREACHABLE_NODES)),
                addresses(s.get(Scalar.JOINING_NODES)), addresses(s.get(Scalar.LEAVING_NODES)),
                addresses(s.get(Scalar.MOVING_NODES)), endpointMap(s.get(Scalar.HOST_ID_MAP)));
        return new Result(b.build(), Mbeans.asString(s.get(Scalar.OPERATION_MODE)), gossip, d.pauseMs());
    }

    /** Forget GC baselines (after a failed read the next delta would span the outage). */
    public void reset() {
        gc.reset();
        names.clear();
    }

    // ---- scalars ------------------------------------------------------------------------

    /** Reads every scalar with one getAttributes per MBean; first candidate present wins. */
    static Map<Scalar, Object> readScalars(Mbeans m, Major major, boolean perPoll) {
        Map<String, Set<String>> byBean = new LinkedHashMap<>();
        Map<Scalar, List<Source>> wanted = new EnumMap<>(Scalar.class);
        for (Scalar sc : Scalar.values()) {
            if (sc.perPoll != perPoll) continue;
            List<Source> src = MetricCatalog.sources(sc, major);
            wanted.put(sc, src);
            for (Source x : src) byBean.computeIfAbsent(x.objectName(), k -> new LinkedHashSet<>()).add(x.attribute());
        }
        Map<String, Map<String, Object>> values = new HashMap<>();
        byBean.forEach((bean, attrs) -> values.put(bean, m.attributes(Mbeans.name(bean), attrs)));
        Map<Scalar, Object> out = new EnumMap<>(Scalar.class);
        wanted.forEach((sc, src) -> {
            for (Source x : src) {
                Object v = values.get(x.objectName()).get(x.attribute());
                if (v != null) {
                    out.put(sc, v);
                    break;
                }
            }
        });
        return out;
    }

    private String javaVersion(Mbeans m, Long uptimeMs, Map<Scalar, Object> s) {
        boolean restarted = uptimeMs != null && lastUptimeMs != null && uptimeMs < lastUptimeMs;
        lastUptimeMs = uptimeMs;
        if (javaVersion == null || restarted) {
            javaVersion = systemProperty(m.attribute(MetricCatalog.RUNTIME, "SystemProperties"), "java.version");
            if (javaVersion == null) javaVersion = Mbeans.asString(s.get(Scalar.SPEC_VERSION));
        }
        return javaVersion;
    }

    private static String systemProperty(Object props, String key) {
        if (props instanceof TabularData td) {
            for (Object row : td.values()) {
                if (row instanceof CompositeData cd && key.equals(cd.get("key"))) return Mbeans.asString(cd.get("value"));
            }
        }
        return null;
    }

    private static Long sum(Map<Scalar, Object> s, Scalar... parts) {
        Long total = null;
        for (Scalar p : parts) {
            Long v = Mbeans.asLong(s.get(p));
            if (v != null) total = (total == null ? 0 : total) + v;
        }
        return total;
    }

    private static List<DataDir> dataDirs(Object v) {
        if (!(v instanceof String[] dirs)) return null;
        List<DataDir> out = new ArrayList<>();
        // Free/total space is not exposed over JMX; SSH (df) can fill it in later.
        for (String d : dirs) out.add(new DataDir(d, null, null));
        return out;
    }

    // ---- families -------------------------------------------------------------------------

    private Set<ObjectName> cached(Mbeans m, String pattern) {
        return names.computeIfAbsent(pattern, m::query);
    }

    private List<GcCollector> gcCollectors(Mbeans m) {
        List<GcCollector> out = new ArrayList<>();
        for (ObjectName n : cached(m, MetricCatalog.GC_PATTERN)) {
            Map<String, Object> a = m.attributes(n, MetricCatalog.GC_ATTRS);
            if (a.isEmpty()) continue;
            out.add(new GcCollector(n.getKeyProperty("name"), Mbeans.asLong(a.get("CollectionCount")),
                    Mbeans.asLong(a.get("CollectionTime"))));
        }
        return out.isEmpty() ? null : out;
    }

    private List<ThreadPool> threadPools(Mbeans m) {
        Map<String, Map<String, Long>> byPool = new TreeMap<>();
        for (ObjectName n : cached(m, MetricCatalog.THREAD_POOLS_PATTERN)) {
            String metric = n.getKeyProperty("name"), pool = n.getKeyProperty("scope");
            if (pool == null || !MetricCatalog.THREAD_POOL_NAMES.contains(metric)) continue;
            Map<String, Object> a = m.attributes(n, MetricCatalog.THREAD_POOL_ATTRS);
            Object v = a.containsKey("Value") ? a.get("Value") : a.get("Count");
            byPool.computeIfAbsent(pool, k -> new HashMap<>()).put(metric, Mbeans.asLong(v));
        }
        if (byPool.isEmpty()) return null;
        List<ThreadPool> out = new ArrayList<>();
        byPool.forEach((pool, v) -> out.add(new ThreadPool(pool, v.get("ActiveTasks"), v.get("PendingTasks"),
                v.get("CurrentlyBlockedTasks"), v.get("CompletedTasks"), v.get("TotalBlockedTasks"))));
        return out;
    }

    private Map<String, Long> dropped(Mbeans m) {
        Map<String, Long> out = new TreeMap<>();
        for (ObjectName n : cached(m, MetricCatalog.DROPPED_PATTERN)) {
            Long v = Mbeans.asLong(m.attributes(n, List.of("Count")).get("Count"));
            if (v != null && n.getKeyProperty("scope") != null) out.put(n.getKeyProperty("scope"), v);
        }
        return out.isEmpty() ? null : out;
    }

    private static ClientRequests clientRequests(Mbeans m) {
        Map<String, Latency> lat = new HashMap<>();
        for (String scope : MetricCatalog.CLIENT_SCOPES) {
            lat.put(scope, latency(m.attributes(Mbeans.name(MetricCatalog.clientRequest(scope, "Latency")),
                    MetricCatalog.TIMER_ATTRS)));
        }
        Long rt = count(m, "Read", "Timeouts"), wt = count(m, "Write", "Timeouts");
        Long ru = count(m, "Read", "Unavailables"), wu = count(m, "Write", "Unavailables");
        Long rf = count(m, "Read", "Failures"), wf = count(m, "Write", "Failures");
        if (lat.values().stream().allMatch(x -> x == null) && rt == null && wt == null) return null;
        return new ClientRequests(lat.get("Read"), lat.get("Write"), lat.get("RangeSlice"), lat.get("CASRead"),
                lat.get("CASWrite"), rt, wt, ru, wu, rf, wf);
    }

    private static Long count(Mbeans m, String scope, String name) {
        return Mbeans.asLong(m.attributes(Mbeans.name(MetricCatalog.clientRequest(scope, name)), List.of("Count"))
                .get("Count"));
    }

    static Latency latency(Map<String, Object> a) {
        if (a.isEmpty()) return null;
        Object unit = a.get("DurationUnit");
        return new Latency(MetricCatalog.toMicros(Mbeans.asDouble(a.get("50thPercentile")), unit),
                MetricCatalog.toMicros(Mbeans.asDouble(a.get("95thPercentile")), unit),
                MetricCatalog.toMicros(Mbeans.asDouble(a.get("99thPercentile")), unit),
                MetricCatalog.toMicros(Mbeans.asDouble(a.get("Max")), unit),
                Mbeans.asDouble(a.get("OneMinuteRate")), Mbeans.asLong(a.get("Count")));
    }

    static Set<String> addresses(Object v) {
        if (!(v instanceof Collection<?> c)) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (Object o : c) out.add(Mbeans.bareAddress(o));
        return out;
    }

    /** endpoint (any key form) -> value, keys reduced to the bare address. */
    static Map<String, String> endpointMap(Object v) {
        if (!(v instanceof Map<?, ?> map)) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        map.forEach((k, val) -> out.put(Mbeans.bareAddress(k), Mbeans.asString(val)));
        return out;
    }

    private static String str(Object v, String dflt) {
        return v == null ? dflt : v.toString();
    }

    // ---- on request: ring and tables -----------------------------------------------------

    /** Ring data from one node's StorageService (MON-13). Maps are keyed by bare endpoint address. */
    public record RingRead(String partitioner, Map<String, String> endpointByToken, Map<String, Double> ownershipPct,
                           Map<String, Double> effectivePct, Map<String, String> hostIdByEndpoint) {}

    public static RingRead readRing(MBeanServerConnection conn, String version, String keyspace) {
        Mbeans m = new Mbeans(conn);
        Major major = Major.of(version);
        Map<Scalar, Object> s = readScalars(m, major, false);
        Map<String, String> byToken = new LinkedHashMap<>();
        if (s.get(Scalar.TOKEN_TO_ENDPOINT) instanceof Map<?, ?> tm) {
            tm.forEach((t, e) -> byToken.put(String.valueOf(t), Mbeans.bareAddress(e)));
        }
        Map<String, Double> effective = null;
        if (keyspace != null) {
            for (String op : MetricCatalog.effectiveOwnershipOps(major)) {
                Object r = m.invoke(MetricCatalog.STORAGE_SERVICE, op, keyspace);
                if (r != null) {
                    effective = pct(r);
                    break;
                }
            }
        }
        Map<Scalar, Object> perPoll = readScalars(m, major, true);
        return new RingRead(Mbeans.asString(s.get(Scalar.PARTITIONER)), byToken, pct(s.get(Scalar.OWNERSHIP)),
                effective == null ? Map.of() : effective, endpointMap(perPoll.get(Scalar.HOST_ID_MAP)));
    }

    private static Map<String, Double> pct(Object v) {
        Map<String, Double> out = new HashMap<>();
        if (v instanceof Map<?, ?> map) {
            map.forEach((k, val) -> {
                Double d = Mbeans.asDouble(val);
                if (d != null) out.put(Mbeans.bareAddress(k), d * 100.0);
            });
        }
        return out;
    }

    /** MON-18 per-table metrics of one node; keyspace null = every non-system keyspace. */
    public static List<TableMetrics> readTables(MBeanServerConnection conn, String version, String keyspace) {
        Mbeans m = new Mbeans(conn);
        String type = null;
        for (String t : MetricCatalog.tableTypes(Major.of(version))) {
            if (!m.query(MetricCatalog.tablePattern(t, "LiveSSTableCount", keyspace)).isEmpty()) {
                type = t;
                break;
            }
        }
        if (type == null) return List.of();
        // bean -> attributes, and which table field each (bean, attribute) feeds
        Map<ObjectName, Set<String>> attrs = new LinkedHashMap<>();
        Map<ObjectName, List<TableMetric>> fields = new HashMap<>();
        for (TableMetric tm : TableMetric.values()) {
            for (String name : tm.names) {
                Set<ObjectName> found = m.query(MetricCatalog.tablePattern(type, name, keyspace));
                found.removeIf(n -> n.getKeyProperty("scope") == null
                        || keyspace == null && !userKeyspace(n.getKeyProperty("keyspace")));
                if (found.isEmpty()) continue;
                for (ObjectName n : found) {
                    Set<String> a = attrs.computeIfAbsent(n, k -> new LinkedHashSet<>());
                    a.add(tm.attribute);
                    if (tm.attribute.endsWith("Percentile")) a.add("DurationUnit");
                    fields.computeIfAbsent(n, k -> new ArrayList<>()).add(tm);
                }
                break;
            }
        }
        Map<String, Map<TableMetric, Object>> byTable = new TreeMap<>();
        attrs.forEach((bean, a) -> {
            Map<String, Object> v = m.attributes(bean, a);
            String key = bean.getKeyProperty("keyspace") + "." + bean.getKeyProperty("scope");
            Map<TableMetric, Object> t = byTable.computeIfAbsent(key, k -> new EnumMap<>(TableMetric.class));
            for (TableMetric tm : fields.get(bean)) {
                Object raw = v.get(tm.attribute);
                boolean latency = tm == TableMetric.READ_LATENCY_P99 || tm == TableMetric.WRITE_LATENCY_P99;
                t.put(tm, latency ? MetricCatalog.toMicros(Mbeans.asDouble(raw), v.get("DurationUnit")) : raw);
            }
        });
        List<TableMetrics> out = new ArrayList<>();
        byTable.forEach((key, t) -> {
            int dot = key.indexOf('.');
            out.add(new TableMetrics(key.substring(0, dot), key.substring(dot + 1),
                    Mbeans.asLong(t.get(TableMetric.READ_LATENCY)), Mbeans.asLong(t.get(TableMetric.WRITE_LATENCY)),
                    Mbeans.asDouble(t.get(TableMetric.READ_LATENCY_P99)),
                    Mbeans.asDouble(t.get(TableMetric.WRITE_LATENCY_P99)),
                    Mbeans.asLong(t.get(TableMetric.LIVE_DISK)), Mbeans.asLong(t.get(TableMetric.TOTAL_DISK)),
                    Mbeans.asLong(t.get(TableMetric.SSTABLES)), Mbeans.asLong(t.get(TableMetric.MEAN_PARTITION)),
                    Mbeans.asLong(t.get(TableMetric.MAX_PARTITION)), Mbeans.asDouble(t.get(TableMetric.TOMBSTONES_P99)),
                    Mbeans.asDouble(t.get(TableMetric.SSTABLES_PER_READ_P99)),
                    Mbeans.asDouble(t.get(TableMetric.BLOOM_FALSE_RATIO)),
                    Mbeans.asLong(t.get(TableMetric.PENDING_COMPACTIONS)),
                    Mbeans.asDouble(t.get(TableMetric.KEY_CACHE_HIT_RATE))));
        });
        return out;
    }

    /** System keyspaces are left out of table listings and of the default ring keyspace. */
    public static boolean userKeyspace(String keyspace) {
        return keyspace != null && !keyspace.startsWith("system") && !keyspace.startsWith("dse_");
    }
}
