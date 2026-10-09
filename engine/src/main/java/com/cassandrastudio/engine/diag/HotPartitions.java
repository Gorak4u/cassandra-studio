package com.cassandrastudio.engine.diag;

import com.cassandrastudio.engine.jobs.JobContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.management.MBeanServerConnection;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

/**
 * Hot partitions (PRF-1), the JMX equivalent of nodetool toppartitions: the table's local
 * samplers are started, left running for the chosen duration, then read back.
 *
 * <ul>
 *   <li>3.11: {@code ColumnFamilyStore.beginLocalSampling(String sampler, int capacity)} and
 *       {@code finishLocalSampling(String, int count)} → CompositeData {cardinality, partitions};
 *       samplers READS and WRITES.</li>
 *   <li>4.0+: {@code beginLocalSampling(String, int capacity, int durationMillis)} and
 *       {@code finishLocalSampling(String, int)} → List of CompositeData {value, count, error,
 *       string}; also WRITE_SIZE (largest partition updates, in bytes) and the read samplers of
 *       newer versions where present.</li>
 * </ul>
 * Sampling is always finished, also on cancel and failure, so no sampler is left running.
 */
public final class HotPartitions {
    private HotPartitions() {}

    public static final int MAX_TABLES = 50;
    public static final List<String> SAMPLERS_311 = List.of("READS", "WRITES");
    public static final List<String> SAMPLERS_4 = List.of("READS", "WRITES", "WRITE_SIZE");
    /** Samplers whose count is a maximum (bytes), not an occurrence count: merged with max. */
    static final Set<String> MAX_SAMPLERS = Set.of("WRITE_SIZE", "LOCAL_READ_TIME");

    public record Request(List<String> tables, int durationMs, int capacity, int top, List<String> nodes) {}

    public record KeyCount(String key, long count, long error, List<String> nodes) {}

    public record SamplerResult(String sampler, Long cardinality, List<KeyCount> top, String error) {}

    public record TableResult(String keyspace, String table, List<SamplerResult> samplers) {}

    public record NodeResult(String node, String api, String error, List<TableResult> tables) {}

    public record Result(int durationMs, int capacity, int top, List<NodeResult> nodes, List<TableResult> merged) {}

    private record Started(String ks, String table, String sampler) {}

    /** Samples the tables on one node; never throws for a single table or sampler failure. */
    static NodeResult sampleNode(String node, MBeanServerConnection c, Request req, JobContext ctx,
                                 java.util.function.DoubleConsumer progress) throws InterruptedException {
        String api = null;
        List<Started> started = new ArrayList<>();
        Map<String, String> beginErrors = new LinkedHashMap<>();
        Map<String, SamplerResult> results = new LinkedHashMap<>();
        try {
            for (String t : req.tables()) {
                String[] kt = t.split("\\.", 2);
                String bean = bean(kt[0], kt[1]);
                for (String sampler : api == null || api.equals("4.0+") ? SAMPLERS_4 : SAMPLERS_311) {
                    try {
                        if (!"3.11".equals(api)) {
                            try {
                                Jmx.call(c, bean, "beginLocalSampling", new Object[] {sampler, req.capacity(), req.durationMs()},
                                        new String[] {String.class.getName(), int.class.getName(), int.class.getName()});
                                api = "4.0+";
                                started.add(new Started(kt[0], kt[1], sampler));
                                continue;
                            } catch (Jmx.Missing e) {
                                if (e.getMessage().startsWith("MBean")) throw e;
                                if ("4.0+".equals(api)) throw e;
                                api = "3.11";
                            }
                        }
                        if (!SAMPLERS_311.contains(sampler)) continue;
                        Jmx.call(c, bean, "beginLocalSampling", new Object[] {sampler, req.capacity()},
                                new String[] {String.class.getName(), int.class.getName()});
                        started.add(new Started(kt[0], kt[1], sampler));
                    } catch (Jmx.Missing e) {
                        beginErrors.put(t + "|" + sampler, e.getMessage().startsWith("MBean")
                                ? "Table not found on this node" : "Sampler " + sampler + " not supported on this version");
                    } catch (IllegalStateException e) {
                        beginErrors.put(t + "|" + sampler, e.getMessage());
                    }
                }
            }
            long end = System.currentTimeMillis() + req.durationMs();
            while (System.currentTimeMillis() < end) {
                if (ctx != null) ctx.checkCancelled();
                long left = end - System.currentTimeMillis();
                progress.accept(1.0 - Math.max(0, left) / (double) req.durationMs());
                Thread.sleep(Math.max(1, Math.min(250, left)));
            }
            if ("4.0+".equals(api)) Thread.sleep(100); // let the node close the sampling window
        } finally {
            // results are read (and samplers released) even when cancelled
            for (Started s : started) {
                String k = s.ks() + "." + s.table() + "|" + s.sampler();
                try {
                    Object res = Jmx.call(c, bean(s.ks(), s.table()), "finishLocalSampling",
                            new Object[] {s.sampler(), req.top()}, new String[] {String.class.getName(), int.class.getName()});
                    results.put(k, parse(s.sampler(), res, node));
                } catch (RuntimeException e) {
                    results.put(k, new SamplerResult(s.sampler(), null, List.of(), Jmx.rootMessage(e)));
                }
            }
        }
        List<TableResult> tables = new ArrayList<>();
        for (String t : req.tables()) {
            String[] kt = t.split("\\.", 2);
            List<SamplerResult> rs = new ArrayList<>();
            for (String sampler : "3.11".equals(api) ? SAMPLERS_311 : SAMPLERS_4) {
                SamplerResult r = results.get(t + "|" + sampler);
                if (r == null && beginErrors.containsKey(t + "|" + sampler)) {
                    r = new SamplerResult(sampler, null, List.of(), beginErrors.get(t + "|" + sampler));
                }
                if (r != null) rs.add(r);
            }
            tables.add(new TableResult(kt[0], kt[1], rs));
        }
        return new NodeResult(node, api, null, tables);
    }

    static String bean(String ks, String table) {
        return "org.apache.cassandra.db:type=ColumnFamilies,keyspace=" + ks + ",columnfamily=" + table;
    }

    /** Parses finishLocalSampling's result of either version. */
    static SamplerResult parse(String sampler, Object res, String node) {
        Long cardinality = null;
        Collection<?> rows = List.of();
        if (res instanceof CompositeData cd) {
            if (cd.containsKey("cardinality")) cardinality = Jmx.asLong(cd.get("cardinality"));
            Object p = cd.containsKey("partitions") ? cd.get("partitions") : null;
            if (p instanceof TabularData td) rows = td.values();
        } else if (res instanceof TabularData td) {
            rows = td.values();
        } else if (res instanceof Collection<?> list) {
            rows = list;
        }
        Map<String, KeyCount> byKey = new LinkedHashMap<>();
        boolean max = MAX_SAMPLERS.contains(sampler);
        for (Object o : rows) {
            if (!(o instanceof CompositeData r)) continue;
            String key = r.containsKey("string") && r.get("string") != null ? String.valueOf(r.get("string"))
                    : r.containsKey("value") ? String.valueOf(r.get("value")) : "?";
            long count = r.containsKey("count") ? orZero(Jmx.asLong(r.get("count"))) : 0;
            long error = r.containsKey("error") ? orZero(Jmx.asLong(r.get("error"))) : 0;
            // size samplers report one entry per sampled update: keep the largest per key
            byKey.merge(key, new KeyCount(key, count, error, List.of(node)), (a, b) -> max
                    ? (a.count() >= b.count() ? a : b)
                    : new KeyCount(key, a.count() + b.count(), a.error() + b.error(), a.nodes()));
        }
        List<KeyCount> top = new ArrayList<>(byKey.values());
        top.sort(Comparator.comparingLong(KeyCount::count).reversed().thenComparing(KeyCount::key));
        return new SamplerResult(sampler, cardinality, top, null);
    }

    private static long orZero(Long l) {
        return l == null ? 0 : l;
    }

    /** All nodes merged per table and sampler: counts summed (max for size samplers), top {@code top} kept. */
    static List<TableResult> merge(List<NodeResult> nodes, int top) {
        Map<String, Map<String, Map<String, long[]>>> acc = new LinkedHashMap<>();
        Map<String, Map<String, Map<String, Set<String>>>> where = new LinkedHashMap<>();
        Map<String, Map<String, String>> errors = new LinkedHashMap<>();
        for (NodeResult n : nodes) {
            if (n.tables() == null) continue;
            for (TableResult t : n.tables()) {
                String tk = t.keyspace() + "." + t.table();
                for (SamplerResult s : t.samplers()) {
                    var keys = acc.computeIfAbsent(tk, k -> new LinkedHashMap<>()).computeIfAbsent(s.sampler(), k -> new LinkedHashMap<>());
                    var nodesOf = where.computeIfAbsent(tk, k -> new LinkedHashMap<>()).computeIfAbsent(s.sampler(), k -> new LinkedHashMap<>());
                    if (s.error() != null) errors.computeIfAbsent(tk, k -> new LinkedHashMap<>()).putIfAbsent(s.sampler(), n.node() + ": " + s.error());
                    boolean max = MAX_SAMPLERS.contains(s.sampler());
                    for (KeyCount kc : s.top()) {
                        long[] v = keys.computeIfAbsent(kc.key(), k -> new long[2]);
                        v[0] = max ? Math.max(v[0], kc.count()) : v[0] + kc.count();
                        v[1] = max ? Math.max(v[1], kc.error()) : v[1] + kc.error();
                        nodesOf.computeIfAbsent(kc.key(), k -> new LinkedHashSet<>()).add(n.node());
                    }
                }
            }
        }
        List<TableResult> out = new ArrayList<>();
        for (var t : acc.entrySet()) {
            String[] kt = t.getKey().split("\\.", 2);
            List<SamplerResult> samplers = new ArrayList<>();
            for (var s : t.getValue().entrySet()) {
                List<KeyCount> keys = new ArrayList<>();
                for (var k : s.getValue().entrySet()) {
                    keys.add(new KeyCount(k.getKey(), k.getValue()[0], k.getValue()[1],
                            List.copyOf(where.get(t.getKey()).get(s.getKey()).get(k.getKey()))));
                }
                keys.sort(Comparator.comparingLong(KeyCount::count).reversed().thenComparing(KeyCount::key));
                String err = keys.isEmpty() ? errors.getOrDefault(t.getKey(), Map.of()).get(s.getKey()) : null;
                samplers.add(new SamplerResult(s.getKey(), null, keys.subList(0, Math.min(top, keys.size())), err));
            }
            out.add(new TableResult(kt[0], kt[1], samplers));
        }
        return out;
    }
}
