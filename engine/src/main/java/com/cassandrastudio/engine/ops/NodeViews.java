package com.cassandrastudio.engine.ops;

import static com.cassandrastudio.engine.ops.Beans.COMPACTION_MANAGER;
import static com.cassandrastudio.engine.ops.Beans.FAILURE_DETECTOR;
import static com.cassandrastudio.engine.ops.Beans.MESSAGING;
import static com.cassandrastudio.engine.ops.Beans.METRICS;
import static com.cassandrastudio.engine.ops.Beans.SNITCH_INFO;
import static com.cassandrastudio.engine.ops.Beans.STORAGE_PROXY;
import static com.cassandrastudio.engine.ops.Beans.STORAGE_SERVICE;

import com.cassandrastudio.engine.cql.ClusterService.NodeInfo;
import com.cassandrastudio.engine.ops.OpsModel.Section;
import com.cassandrastudio.engine.ops.OpsModel.View;
import com.cassandrastudio.engine.util.ApiException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

/**
 * OPS-1: nodetool's status and statistics commands, read over JMX from one node and returned as
 * tables. Works on 3.11, 4.x and 5.0: where a version names an attribute differently the
 * candidates are tried in order, and what a node does not expose shows as empty (null) cells.
 */
final class NodeViews {
    private NodeViews() {}

    /** The views, in the order the UI lists them. */
    static final List<String> ALL = List.of("status", "info", "ring", "describecluster", "tpstats", "tablestats",
            "tablehistograms", "proxyhistograms", "gossipinfo", "compactionstats", "netstats", "getendpoints");

    record Params(String keyspace, String table, String key) {}

    static View read(Beans b, String view, String node, Params p, List<NodeInfo> topology) {
        String v = view == null ? "" : view.toLowerCase(Locale.ROOT);
        return switch (v) {
            case "status" -> status(b, node, p, topology);
            case "info" -> info(b, node);
            case "ring" -> ring(b, node, p, topology);
            case "describecluster" -> describeCluster(b, node, topology);
            case "tpstats" -> tpstats(b, node);
            case "tablestats" -> tablestats(b, node, p);
            case "tablehistograms" -> tablehistograms(b, node, p);
            case "proxyhistograms" -> proxyhistograms(b, node);
            case "gossipinfo" -> gossipinfo(b, node);
            case "compactionstats" -> compactionstats(b, node);
            case "netstats" -> netstats(b, node);
            case "getendpoints" -> getendpoints(b, node, p);
            default -> throw ApiException.badRequest("Unknown view '" + view + "'; one of " + String.join(", ", ALL));
        };
    }

    static String cmd(String node, String rest) {
        return "nodetool -h " + node + " " + rest;
    }

    // ---- status / ring ---------------------------------------------------------------------

    /** What this node's gossip says about every endpoint (shared by status and ring). */
    private record Cluster(Set<String> endpoints, Set<String> live, Set<String> unreachable, Set<String> joining,
                           Set<String> leaving, Set<String> moving, Map<String, Object> load, Map<String, Object> owns,
                           Map<String, String> hostIds, Map<String, List<String>> tokens, Map<String, String> dc,
                           Map<String, String> rack) {
        String status(String ep) {
            return live.contains(ep) ? "U" : unreachable.contains(ep) ? "D" : "?";
        }

        String state(String ep) {
            return joining.contains(ep) ? "J" : leaving.contains(ep) ? "L" : moving.contains(ep) ? "M" : "N";
        }
    }

    private static Cluster cluster(Beans b, String keyspace, List<NodeInfo> topology) {
        Map<String, Object> ss = b.attrs(STORAGE_SERVICE, "LiveNodes", "UnreachableNodes", "JoiningNodes", "LeavingNodes",
                "MovingNodes", "LoadMap", "Ownership", "TokenToEndpointMap", "EndpointToHostId", "HostIdMap");
        Map<String, List<String>> tokens = new HashMap<>();
        if (ss.get("TokenToEndpointMap") instanceof Map<?, ?> m) {
            m.forEach((t, ep) -> tokens.computeIfAbsent(Beans.bareAddress(ep), k -> new ArrayList<>()).add(String.valueOf(t)));
        }
        Map<String, Object> owns = keyspace == null ? Beans.byAddress(ss.get("Ownership"))
                : Beans.byAddress(effectiveOwnership(b, keyspace));
        Map<String, String> hostIds = new HashMap<>();
        Beans.byAddress(ss.getOrDefault("EndpointToHostId", ss.get("HostIdMap")))
                .forEach((k, v) -> hostIds.put(k, String.valueOf(v)));
        Set<String> live = new LinkedHashSet<>(Beans.addresses(ss.get("LiveNodes")));
        Set<String> unreachable = new LinkedHashSet<>(Beans.addresses(ss.get("UnreachableNodes")));
        Set<String> eps = new TreeSet<>(tokens.keySet());
        eps.addAll(live);
        eps.addAll(unreachable);
        Map<String, String> dc = new HashMap<>(), rack = new HashMap<>();
        Map<String, NodeInfo> byAddr = new HashMap<>();
        topology.forEach(n -> byAddr.put(n.address(), n));
        for (String ep : eps) {
            String d = Beans.str(b.tryInvoke(SNITCH_INFO, "getDatacenter", ep));
            String r = Beans.str(b.tryInvoke(SNITCH_INFO, "getRack", ep));
            NodeInfo n = byAddr.get(ep);
            dc.put(ep, d != null ? d : n == null ? "unknown" : n.datacenter());
            rack.put(ep, r != null ? r : n == null ? null : n.rack());
        }
        return new Cluster(eps, live, unreachable, new LinkedHashSet<>(Beans.addresses(ss.get("JoiningNodes"))),
                new LinkedHashSet<>(Beans.addresses(ss.get("LeavingNodes"))),
                new LinkedHashSet<>(Beans.addresses(ss.get("MovingNodes"))), Beans.byAddress(ss.get("LoadMap")), owns,
                hostIds, tokens, dc, rack);
    }

    private static Object effectiveOwnership(Beans b, String keyspace) {
        try {
            return b.invoke(STORAGE_SERVICE, "effectiveOwnership", Beans.Call.of(Beans.STR, keyspace));
        } catch (OpsException e) {
            throw new ApiException(400, "bad_request", "Effective ownership of " + keyspace + ": " + e.getMessage());
        }
    }

    private static String owns(Object v) {
        Double d = Beans.asDouble(v);
        return d == null ? "?" : Fmt.pct(d);
    }

    static View status(Beans b, String node, Params p, List<NodeInfo> topology) {
        Cluster c = cluster(b, p.keyspace(), topology);
        Map<String, List<List<String>>> byDc = new TreeMap<>();
        for (String ep : c.endpoints()) {
            byDc.computeIfAbsent(c.dc().get(ep), k -> new ArrayList<>()).add(Arrays.asList(c.status(ep) + c.state(ep), ep,
                    Beans.str(c.load().get(ep)), String.valueOf(c.tokens().getOrDefault(ep, List.of()).size()),
                    owns(c.owns().get(ep)), c.hostIds().get(ep), c.rack().get(ep)));
        }
        List<Section> sections = new ArrayList<>();
        String owns = p.keyspace() == null ? "Owns" : "Owns (effective)";
        byDc.forEach((dc, rows) -> sections.add(Section.table("Datacenter: " + dc,
                List.of("Status/State", "Address", "Load", "Tokens", owns, "Host ID", "Rack"), rows)));
        List<String> notes = new ArrayList<>(List.of("Status=Up/Down, State=Normal/Leaving/Joining/Moving"));
        if (p.keyspace() == null) notes.add("Ownership is not replica-aware; pick a keyspace for effective ownership.");
        return new View("status", node, cmd(node, "status" + (p.keyspace() == null ? "" : " " + p.keyspace())),
                sections, notes);
    }

    static View ring(Beans b, String node, Params p, List<NodeInfo> topology) {
        Cluster c = cluster(b, p.keyspace(), topology);
        Map<String, List<String[]>> byDc = new TreeMap<>();
        c.tokens().forEach((ep, toks) -> toks.forEach(t -> byDc.computeIfAbsent(c.dc().get(ep), k -> new ArrayList<>())
                .add(new String[] {ep, t})));
        List<Section> sections = new ArrayList<>();
        int total = 0;
        for (var e : byDc.entrySet()) {
            List<String[]> list = e.getValue();
            list.sort(Comparator.comparing((String[] x) -> x[1], NodeViews::compareTokens));
            List<List<String>> rows = new ArrayList<>();
            for (String[] x : list) {
                if (total++ >= 5000) break;
                String ep = x[0];
                rows.add(Arrays.asList(ep, c.rack().get(ep), c.live().contains(ep) ? "Up" : c.unreachable().contains(ep) ? "Down" : "?",
                        stateWord(c.state(ep)), Beans.str(c.load().get(ep)), owns(c.owns().get(ep)), x[1]));
            }
            sections.add(Section.table("Datacenter: " + e.getKey(),
                    List.of("Address", "Rack", "Status", "State", "Load", "Owns", "Token"), rows));
        }
        List<String> notes = total > 5000 ? List.of("Showing the first 5000 tokens.") : List.of();
        return new View("ring", node, cmd(node, "ring" + (p.keyspace() == null ? "" : " " + p.keyspace())), sections, notes);
    }

    private static String stateWord(String s) {
        return switch (s) {
            case "J" -> "Joining";
            case "L" -> "Leaving";
            case "M" -> "Moving";
            default -> "Normal";
        };
    }

    static int compareTokens(String a, String b) {
        try {
            return new BigInteger(a).compareTo(new BigInteger(b));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }

    // ---- info / describecluster ---------------------------------------------------------------

    static View info(Beans b, String node) {
        Map<String, Object> ss = b.attrs(STORAGE_SERVICE, "LocalHostId", "GossipRunning", "NativeTransportRunning",
                "LoadString", "CurrentGenerationNumber", "Tokens", "ReleaseVersion", "OperationMode");
        Map<String, Object> mem = b.attrs("java.lang:type=Memory", "HeapMemoryUsage");
        Long uptime = Beans.asLong(b.attr("java.lang:type=Runtime", "Uptime"));
        Map<String, Object> snitch = b.attrs(SNITCH_INFO, "Datacenter", "Rack");
        List<List<String>> rows = new ArrayList<>();
        kv(rows, "ID", Beans.str(ss.get("LocalHostId")));
        kv(rows, "Release version", Beans.str(ss.get("ReleaseVersion")));
        kv(rows, "Mode", Beans.str(ss.get("OperationMode")));
        kv(rows, "Gossip active", Fmt.bool(ss.get("GossipRunning")));
        kv(rows, "Native Transport active", Fmt.bool(ss.get("NativeTransportRunning")));
        kv(rows, "Load", Beans.str(ss.get("LoadString")));
        kv(rows, "Generation No", Fmt.num(Beans.asLong(ss.get("CurrentGenerationNumber"))));
        kv(rows, "Uptime (seconds)", uptime == null ? null : String.valueOf(uptime / 1000));
        if (mem.get("HeapMemoryUsage") instanceof CompositeData cd) {
            double used = ((Number) cd.get("used")).doubleValue() / (1024 * 1024);
            double max = ((Number) cd.get("max")).doubleValue() / (1024 * 1024);
            kv(rows, "Heap Memory (MB)", String.format(Locale.ROOT, "%.2f / %.2f", used, max));
        } else {
            kv(rows, "Heap Memory (MB)", null);
        }
        Long offHeap = null;
        for (String n : List.of("MemtableOffHeapSize", "BloomFilterOffHeapMemoryUsed", "IndexSummaryOffHeapMemoryUsed",
                "CompressionMetadataOffHeapMemoryUsed")) {
            Long v = Beans.asLong(b.firstAttrOf(List.of(Beans.metric("Table", n), Beans.metric("ColumnFamily", n)), "Value"));
            if (v != null) offHeap = (offHeap == null ? 0 : offHeap) + v;
        }
        kv(rows, "Off Heap Memory (MB)", offHeap == null ? null : String.format(Locale.ROOT, "%.2f", offHeap / (1024.0 * 1024)));
        kv(rows, "Data Center", Beans.str(snitch.get("Datacenter")));
        kv(rows, "Rack", Beans.str(snitch.get("Rack")));
        kv(rows, "Exceptions", Fmt.num(Beans.asLong(b.attr(Beans.metric("Storage", "Exceptions"), "Count"))));
        for (String cache : List.of("KeyCache", "RowCache", "CounterCache", "ChunkCache")) {
            String line = cacheLine(b, cache);
            if (line != null || !cache.equals("ChunkCache")) kv(rows, cache.replace("Cache", " Cache"), line);
        }
        kv(rows, "Token count", ss.get("Tokens") instanceof Collection<?> t ? String.valueOf(t.size()) : null);
        return new View("info", node, cmd(node, "info"), List.of(Section.keyValues("Node " + node, rows)), List.of());
    }

    private static String cacheLine(Beans b, String scope) {
        Map<String, Object> v = new HashMap<>();
        for (String n : List.of("Entries", "Size", "Capacity", "Hits", "Requests", "HitRate")) {
            Map<String, Object> a = b.attrs(Beans.name(METRICS + ":type=Cache,scope=" + scope + ",name=" + n), List.of("Value", "Count"));
            Object x = a.containsKey("Value") ? a.get("Value") : a.get("Count");
            if (x != null) v.put(n, x);
        }
        if (v.isEmpty()) return null;
        Double hr = Beans.asDouble(v.get("HitRate"));
        return String.format(Locale.ROOT, "entries %s, size %s, capacity %s, %s hits, %s requests, %s recent hit rate",
                v.getOrDefault("Entries", "?"), Fmt.bytes(Beans.asLong(v.get("Size"))), Fmt.bytes(Beans.asLong(v.get("Capacity"))),
                v.getOrDefault("Hits", "?"), v.getOrDefault("Requests", "?"), hr == null ? "NaN" : Fmt.dec(hr, 3));
    }

    static View describeCluster(Beans b, String node, List<NodeInfo> topology) {
        Map<String, Object> ss = b.attrs(STORAGE_SERVICE, "ClusterName", "PartitionerName", "UnreachableNodes");
        List<List<String>> rows = new ArrayList<>();
        kv(rows, "Name", Beans.str(ss.get("ClusterName")));
        kv(rows, "Snitch", Beans.str(b.attr(SNITCH_INFO, "SnitchName")));
        kv(rows, "DynamicEndPointSnitch", b.exists(Beans.DYNAMIC_SNITCH) ? "enabled" : "disabled");
        kv(rows, "Partitioner", Beans.str(ss.get("PartitionerName")));
        List<List<String>> schema = new ArrayList<>();
        Object sv = b.firstAttr(STORAGE_PROXY, "SchemaVersionsWithPort", "SchemaVersions");
        if (sv instanceof Map<?, ?> m) {
            m.forEach((ver, eps) -> schema.add(Arrays.asList(String.valueOf(ver),
                    eps instanceof Collection<?> c ? String.join(", ", c.stream().map(String::valueOf).toList()) : String.valueOf(eps))));
        }
        List<Section> sections = new ArrayList<>();
        sections.add(Section.keyValues("Cluster Information", rows));
        sections.add(Section.table("Schema versions", List.of("Version", "Endpoints"), schema));
        Set<String> down = new LinkedHashSet<>(Beans.addresses(ss.get("UnreachableNodes")));
        Map<String, int[]> dcs = new TreeMap<>();
        for (NodeInfo n : topology) {
            int[] c = dcs.computeIfAbsent(String.valueOf(n.datacenter()), k -> new int[2]);
            c[0]++;
            if (down.contains(n.address())) c[1]++;
        }
        List<List<String>> dcRows = new ArrayList<>();
        dcs.forEach((dc, c) -> dcRows.add(List.of(dc, String.valueOf(c[0]), String.valueOf(c[1]))));
        sections.add(Section.table("Data Centers", List.of("Datacenter", "Nodes", "Unreachable"), dcRows));
        List<String> notes = schema.size() > 1 ? List.of("Schema disagreement: more than one schema version.") : List.of();
        return new View("describecluster", node, cmd(node, "describecluster"), sections, notes);
    }

    // ---- tpstats ---------------------------------------------------------------------------

    static View tpstats(Beans b, String node) {
        Map<String, Map<String, Long>> pools = new TreeMap<>();
        for (ObjectName n : b.query(METRICS + ":type=ThreadPools,*")) {
            String metric = n.getKeyProperty("name"), pool = n.getKeyProperty("scope");
            if (pool == null || !List.of("ActiveTasks", "PendingTasks", "CompletedTasks", "CurrentlyBlockedTasks",
                    "TotalBlockedTasks").contains(metric)) continue;
            Map<String, Object> a = b.attrs(n, List.of("Value", "Count"));
            Object v = a.containsKey("Value") ? a.get("Value") : a.get("Count");
            pools.computeIfAbsent(pool, k -> new HashMap<>()).put(metric, Beans.asLong(v));
        }
        List<List<String>> rows = new ArrayList<>();
        pools.forEach((pool, m) -> rows.add(Arrays.asList(pool, Fmt.num(m.get("ActiveTasks")), Fmt.num(m.get("PendingTasks")),
                Fmt.num(m.get("CompletedTasks")), Fmt.num(m.get("CurrentlyBlockedTasks")), Fmt.num(m.get("TotalBlockedTasks")))));
        Map<String, Long> dropped = new TreeMap<>();
        for (ObjectName n : b.query(METRICS + ":type=DroppedMessage,name=Dropped,*")) {
            Long v = Beans.asLong(b.attrs(n, List.of("Count")).get("Count"));
            if (n.getKeyProperty("scope") != null) dropped.put(n.getKeyProperty("scope"), v);
        }
        List<List<String>> drop = new ArrayList<>();
        dropped.forEach((k, v) -> drop.add(Arrays.asList(k, Fmt.num(v))));
        return new View("tpstats", node, cmd(node, "tpstats"), List.of(
                Section.table("Thread pools", List.of("Pool Name", "Active", "Pending", "Completed", "Blocked",
                        "All time blocked"), rows),
                Section.table("Dropped messages", List.of("Message type", "Dropped"), drop)), List.of());
    }

    // ---- tables -----------------------------------------------------------------------------

    /** Keyspace → tables that have metrics on this node (type=Table on every version; ColumnFamily as fallback). */
    private static Map<String, Set<String>> tables(Beans b, String keyspace, String table) {
        Map<String, Set<String>> out = new TreeMap<>();
        for (String type : List.of("Table", "ColumnFamily")) {
            String pattern = METRICS + ":type=" + type + (keyspace == null ? "" : ",keyspace=" + quote(keyspace))
                    + (table == null ? "" : ",scope=" + quote(table)) + ",name=LiveSSTableCount,*";
            for (ObjectName n : b.query(pattern)) {
                String ks = n.getKeyProperty("keyspace"), t = n.getKeyProperty("scope");
                if (ks != null && t != null) out.computeIfAbsent(unquote(ks), k -> new TreeSet<>()).add(unquote(t));
            }
            if (!out.isEmpty()) return out;
        }
        return out;
    }

    private static String quote(String v) {
        return v.matches("[A-Za-z0-9_]+") ? v : ObjectName.quote(v);
    }

    private static String unquote(String v) {
        return v.startsWith("\"") ? ObjectName.unquote(v) : v;
    }

    private static final List<String> METRIC_ATTRS = List.of("Value", "Count", "Mean", "Max");

    /** All metric values of one table: name → (attribute → value). */
    private static Map<String, Map<String, Object>> tableMetrics(Beans b, String ks, String t, List<String> names) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        for (String n : names) {
            for (String type : List.of("Table", "ColumnFamily")) {
                Map<String, Object> a = b.attrs(Beans.name(METRICS + ":type=" + type + ",keyspace=" + quote(ks) + ",scope="
                        + quote(t) + ",name=" + n), METRIC_ATTRS);
                if (!a.isEmpty()) {
                    out.put(n, a);
                    break;
                }
            }
        }
        return out;
    }

    static boolean systemKeyspace(String ks) {
        return ks.equals("system") || ks.startsWith("system_") || ks.equals("dse_system");
    }

    private static final List<String> TABLE_METRICS = List.of("LiveSSTableCount", "LiveDiskSpaceUsed", "TotalDiskSpaceUsed",
            "SnapshotsSize", "MemtableOffHeapSize", "BloomFilterOffHeapMemoryUsed", "IndexSummaryOffHeapMemoryUsed",
            "CompressionMetadataOffHeapMemoryUsed", "CompressionRatio", "EstimatedPartitionCount", "MemtableColumnsCount",
            "MemtableLiveDataSize", "MemtableSwitchCount", "ReadLatency", "ReadTotalLatency", "WriteLatency",
            "WriteTotalLatency", "PendingFlushes", "PercentRepaired", "BloomFilterFalsePositives", "BloomFilterFalseRatio",
            "BloomFilterDiskSpaceUsed", "MinPartitionSize", "MaxPartitionSize", "MeanPartitionSize", "LiveScannedHistogram",
            "TombstoneScannedHistogram", "DroppedMutations");

    static final List<String> TABLESTATS_COLUMNS = List.of("Keyspace", "Table", "SSTable count", "Space used (live)",
            "Space used (total)", "Space used by snapshots", "Off heap memory used", "SSTable compression ratio",
            "Number of partitions (estimate)", "Memtable cell count", "Memtable data size", "Memtable switch count",
            "Local read count", "Local read latency (ms)", "Local write count", "Local write latency (ms)", "Pending flushes",
            "Percent repaired", "Bloom filter false positives", "Bloom filter false ratio", "Bloom filter space used",
            "Compacted partition minimum bytes", "Compacted partition maximum bytes", "Compacted partition mean bytes",
            "Average live cells per slice (last five minutes)", "Maximum live cells per slice (last five minutes)",
            "Average tombstones per slice (last five minutes)", "Maximum tombstones per slice (last five minutes)",
            "Dropped Mutations");

    static View tablestats(Beans b, String node, Params p) {
        Map<String, Set<String>> all = tables(b, p.keyspace(), p.table());
        if (p.keyspace() != null && all.isEmpty()) {
            throw ApiException.badRequest("No table metrics for " + p.keyspace() + (p.table() == null ? "" : "." + p.table())
                    + " on " + node + " (unknown keyspace or table?)");
        }
        List<List<String>> rows = new ArrayList<>();
        all.forEach((ks, tabs) -> {
            if (p.keyspace() == null && systemKeyspace(ks)) return;
            for (String t : tabs) {
                Map<String, Map<String, Object>> m = tableMetrics(b, ks, t, TABLE_METRICS);
                Long offHeap = sum(m, "MemtableOffHeapSize", "BloomFilterOffHeapMemoryUsed", "IndexSummaryOffHeapMemoryUsed",
                        "CompressionMetadataOffHeapMemoryUsed");
                Double ratio = Beans.asDouble(val(m, "CompressionRatio"));
                rows.add(Arrays.asList(ks, t, Fmt.num(Beans.asLong(val(m, "LiveSSTableCount"))),
                        Fmt.bytes(Beans.asLong(val(m, "LiveDiskSpaceUsed"))), Fmt.bytes(Beans.asLong(val(m, "TotalDiskSpaceUsed"))),
                        Fmt.bytes(Beans.asLong(val(m, "SnapshotsSize"))), Fmt.bytes(offHeap),
                        ratio == null || ratio < 0 ? (ratio == null ? null : "-1.0") : Fmt.dec(ratio, 5),
                        Fmt.num(Beans.asLong(val(m, "EstimatedPartitionCount"))),
                        Fmt.num(Beans.asLong(val(m, "MemtableColumnsCount"))),
                        Fmt.bytes(Beans.asLong(val(m, "MemtableLiveDataSize"))),
                        Fmt.num(Beans.asLong(val(m, "MemtableSwitchCount"))),
                        Fmt.num(Beans.asLong(val(m, "ReadLatency"))), latencyMs(m, "ReadTotalLatency", "ReadLatency"),
                        Fmt.num(Beans.asLong(val(m, "WriteLatency"))), latencyMs(m, "WriteTotalLatency", "WriteLatency"),
                        Fmt.num(Beans.asLong(val(m, "PendingFlushes"))),
                        pctValue(Beans.asDouble(val(m, "PercentRepaired"))),
                        Fmt.num(Beans.asLong(val(m, "BloomFilterFalsePositives"))),
                        Fmt.dec(Beans.asDouble(val(m, "BloomFilterFalseRatio")), 5),
                        Fmt.bytes(Beans.asLong(val(m, "BloomFilterDiskSpaceUsed"))),
                        Fmt.num(Beans.asLong(val(m, "MinPartitionSize"))), Fmt.num(Beans.asLong(val(m, "MaxPartitionSize"))),
                        Fmt.num(Beans.asLong(val(m, "MeanPartitionSize"))),
                        Fmt.dec(Beans.asDouble(attr(m, "LiveScannedHistogram", "Mean")), 1),
                        Fmt.num(Beans.asLong(attr(m, "LiveScannedHistogram", "Max"))),
                        Fmt.dec(Beans.asDouble(attr(m, "TombstoneScannedHistogram", "Mean")), 1),
                        Fmt.num(Beans.asLong(attr(m, "TombstoneScannedHistogram", "Max"))),
                        Fmt.num(Beans.asLong(val(m, "DroppedMutations")))));
            }
        });
        String args = p.keyspace() == null ? "" : " -- " + p.keyspace() + (p.table() == null ? "" : "." + p.table());
        List<String> notes = p.keyspace() == null ? List.of("System keyspaces are left out; pick one to see it.") : List.of();
        return new View("tablestats", node, cmd(node, "tablestats" + args),
                List.of(Section.table("Tables", TABLESTATS_COLUMNS, rows)), notes);
    }

    private static String pctValue(Double v) {
        return v == null ? null : String.format(Locale.ROOT, "%.2f%%", v);
    }

    /** Gauge value (Value) or counter / meter / timer count (Count). */
    private static Object val(Map<String, Map<String, Object>> m, String name) {
        Map<String, Object> a = m.get(name);
        if (a == null) return null;
        return a.containsKey("Value") ? a.get("Value") : a.get("Count");
    }

    private static Object attr(Map<String, Map<String, Object>> m, String name, String attr) {
        Map<String, Object> a = m.get(name);
        return a == null ? null : a.get(attr);
    }

    private static Long sum(Map<String, Map<String, Object>> m, String... names) {
        Long total = null;
        for (String n : names) {
            Long v = Beans.asLong(val(m, n));
            if (v != null) total = (total == null ? 0 : total) + v;
        }
        return total;
    }

    /** Total latency counter (microseconds) / operation count, in milliseconds; NaN as nodetool prints it. */
    private static String latencyMs(Map<String, Map<String, Object>> m, String total, String count) {
        Long t = Beans.asLong(val(m, total)), c = Beans.asLong(val(m, count));
        if (t == null || c == null) return null;
        return c == 0 ? "NaN" : String.format(Locale.ROOT, "%.3f", t / 1000.0 / c);
    }

    static final List<String> PERCENTILES = List.of("50%", "75%", "95%", "98%", "99%", "Min", "Max");
    private static final List<String> PCT_ATTRS = List.of("50thPercentile", "75thPercentile", "95thPercentile",
            "98thPercentile", "99thPercentile", "Min", "Max", "DurationUnit");
    private static final double[] PCT_POINTS = {0.5, 0.75, 0.95, 0.98, 0.99};

    static View tablehistograms(Beans b, String node, Params p) {
        if (p.keyspace() == null || p.table() == null) throw ApiException.badRequest("tablehistograms needs keyspace and table");
        Map<String, Map<String, Object>> m = new HashMap<>();
        for (String n : List.of("SSTablesPerReadHistogram", "WriteLatency", "ReadLatency", "EstimatedPartitionSizeHistogram",
                "EstimatedColumnCountHistogram")) {
            for (String type : List.of("Table", "ColumnFamily")) {
                Map<String, Object> a = b.attrs(Beans.name(METRICS + ":type=" + type + ",keyspace=" + quote(p.keyspace())
                        + ",scope=" + quote(p.table()) + ",name=" + n), n.startsWith("Estimated") ? List.of("Value") : PCT_ATTRS);
                if (!a.isEmpty()) {
                    m.put(n, a);
                    break;
                }
            }
        }
        if (m.isEmpty()) {
            throw ApiException.badRequest("No metrics for " + p.keyspace() + "." + p.table() + " on " + node
                    + " (unknown keyspace or table?)");
        }
        long[] size = m.containsKey("EstimatedPartitionSizeHistogram")
                && m.get("EstimatedPartitionSizeHistogram").get("Value") instanceof long[] l ? l : null;
        long[] cells = m.containsKey("EstimatedColumnCountHistogram")
                && m.get("EstimatedColumnCountHistogram").get("Value") instanceof long[] l ? l : null;
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < PERCENTILES.size(); i++) {
            String attr = PCT_ATTRS.get(i);
            rows.add(Arrays.asList(PERCENTILES.get(i), Fmt.dec(Beans.asDouble(attr(m, "SSTablesPerReadHistogram", attr)), 2),
                    micros(m.get("WriteLatency"), attr), micros(m.get("ReadLatency"), attr),
                    estimated(size, i), estimated(cells, i)));
        }
        return new View("tablehistograms", node, cmd(node, "tablehistograms -- " + p.keyspace() + " " + p.table()),
                List.of(Section.table(p.keyspace() + "/" + p.table() + " histograms", List.of("Percentile",
                        "SSTables", "Write Latency (micros)", "Read Latency (micros)", "Partition Size (bytes)", "Cell Count"),
                        rows)), List.of("Latencies are recent (decaying) percentiles."));
    }

    private static String estimated(long[] buckets, int i) {
        if (buckets == null) return null;
        Long v = i < PCT_POINTS.length ? Histograms.percentile(buckets, PCT_POINTS[i])
                : i == PCT_POINTS.length ? Histograms.min(buckets) : Histograms.max(buckets);
        if (v == null) return "NaN";
        return v == Long.MAX_VALUE ? "overflow" : String.valueOf(v);
    }

    private static String micros(Map<String, Object> timer, String attr) {
        if (timer == null || timer.get(attr) == null) return null;
        Double v = Beans.asDouble(timer.get(attr));
        if (v == null) return null;
        String unit = Beans.str(timer.get("DurationUnit"));
        double us = switch (unit == null ? "microseconds" : unit.toLowerCase(Locale.ROOT)) {
            case "nanoseconds" -> v / 1000.0;
            case "milliseconds" -> v * 1000.0;
            case "seconds" -> v * 1_000_000.0;
            default -> v;
        };
        return String.format(Locale.ROOT, "%.2f", us);
    }

    static View proxyhistograms(Beans b, String node) {
        List<String> scopes = List.of("Read", "Write", "RangeSlice", "CASRead", "CASWrite", "ViewWrite");
        Map<String, Map<String, Object>> m = new HashMap<>();
        for (String s : scopes) m.put(s, b.attrs(Beans.name(METRICS + ":type=ClientRequest,scope=" + s + ",name=Latency"), PCT_ATTRS));
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < PERCENTILES.size(); i++) {
            List<String> row = new ArrayList<>();
            row.add(PERCENTILES.get(i));
            for (String s : scopes) row.add(m.get(s).isEmpty() ? null : micros(m.get(s), PCT_ATTRS.get(i)));
            rows.add(row);
        }
        return new View("proxyhistograms", node, cmd(node, "proxyhistograms"), List.of(Section.table(
                "Proxy latencies (micros)", List.of("Percentile", "Read Latency", "Write Latency", "Range Latency",
                        "CAS Read Latency", "CAS Write Latency", "View Write Latency"), rows)),
                List.of("Latencies are recent (decaying) percentiles."));
    }

    // ---- gossip / compaction / streaming ----------------------------------------------------

    static View gossipinfo(Beans b, String node) {
        Object text = b.firstAttr(FAILURE_DETECTOR, "AllEndpointStatesWithPort", "AllEndpointStates");
        Map<String, Object> simple = Beans.byAddress(b.firstAttr(FAILURE_DETECTOR, "SimpleStatesWithPort", "SimpleStates"));
        List<Section> sections = new ArrayList<>();
        for (Gossip.Endpoint e : Gossip.parse(Beans.str(text))) {
            Object st = simple.get(Beans.bareAddress(e.endpoint()));
            List<List<String>> rows = new ArrayList<>();
            e.states().forEach(s -> rows.add(Arrays.asList(s.key(), s.version(), s.value())));
            sections.add(Section.table("/" + e.endpoint() + (st == null ? "" : " (" + st + ")"),
                    List.of("Key", "Version", "Value"), rows));
        }
        return new View("gossipinfo", node, cmd(node, "gossipinfo"), sections,
                text == null ? List.of("The FailureDetector MBean is not available on this node.") : List.of());
    }

    static View compactionstats(Beans b, String node) {
        Long pending = Beans.asLong(b.attr(Beans.metric("Compaction", "PendingTasks"), "Value"));
        List<List<String>> summary = new ArrayList<>();
        kv(summary, "pending tasks", Fmt.num(pending));
        Object byTable = b.attr(Beans.metric("Compaction", "PendingTasksByTableName"), "Value");
        List<List<String>> pendingRows = new ArrayList<>();
        if (byTable instanceof Map<?, ?> ks) {
            new TreeMap<>(ks.entrySet().stream().collect(java.util.stream.Collectors.toMap(e -> String.valueOf(e.getKey()),
                    Map.Entry::getValue))).forEach((k, tabs) -> {
                        if (tabs instanceof Map<?, ?> t) {
                            t.forEach((name, n) -> pendingRows.add(Arrays.asList(k, String.valueOf(name), String.valueOf(n))));
                        }
                    });
        }
        List<List<String>> active = new ArrayList<>();
        if (b.attr(COMPACTION_MANAGER, "Compactions") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> c)) continue;
                Long done = Beans.asLong(c.get("completed")), total = Beans.asLong(c.get("total"));
                boolean bytes = "bytes".equalsIgnoreCase(String.valueOf(c.get("unit")));
                active.add(Arrays.asList(Beans.str(c.get("compactionId")), Beans.str(c.get("taskType")), Beans.str(c.get("keyspace")),
                        Beans.str(c.get("columnfamily")), bytes ? Fmt.bytes(done) : Fmt.num(done),
                        bytes ? Fmt.bytes(total) : Fmt.num(total), Beans.str(c.get("unit")),
                        done == null || total == null || total == 0 ? null : Fmt.pct(done / (double) total)));
            }
        }
        List<Section> sections = new ArrayList<>();
        sections.add(Section.keyValues("Summary", summary));
        if (!pendingRows.isEmpty()) sections.add(Section.table("Pending by table", List.of("Keyspace", "Table", "Pending"), pendingRows));
        sections.add(Section.table("Active compactions", List.of("id", "compaction type", "keyspace", "table", "completed",
                "total", "unit", "progress"), active));
        return new View("compactionstats", node, cmd(node, "compactionstats"), sections, List.of());
    }

    static View netstats(Beans b, String node) {
        List<List<String>> summary = new ArrayList<>();
        kv(summary, "Mode", Beans.str(b.attr(STORAGE_SERVICE, "OperationMode")));
        List<List<String>> streams = new ArrayList<>();
        if (b.attr(Beans.STREAM_MANAGER, "CurrentStreams") instanceof Collection<?> set) {
            for (Object o : set) {
                if (!(o instanceof CompositeData s)) continue;
                List<String> peers = new ArrayList<>();
                if (get(s, "sessions") instanceof CompositeData[] sessions) {
                    for (CompositeData ss : sessions) peers.add(Beans.bareAddress(get(ss, "peer")) + " " + get(ss, "state"));
                }
                streams.add(Arrays.asList(Beans.str(get(s, "description")), Beans.str(get(s, "planId")), String.join(", ", peers),
                        Fmt.bytes(Beans.asLong(get(s, "currentRxBytes"))) + " / " + Fmt.bytes(Beans.asLong(get(s, "totalRxBytes"))),
                        Fmt.dec(Beans.asDouble(get(s, "rxPercentage")), 1),
                        Fmt.bytes(Beans.asLong(get(s, "currentTxBytes"))) + " / " + Fmt.bytes(Beans.asLong(get(s, "totalTxBytes"))),
                        Fmt.dec(Beans.asDouble(get(s, "txPercentage")), 1)));
            }
        }
        Map<String, Object> rr = b.attrs(STORAGE_PROXY, "ReadRepairAttempted", "ReadRepairRepairedBlocking",
                "ReadRepairRepairedBackground");
        List<List<String>> repair = new ArrayList<>();
        kv(repair, "Attempted", Fmt.num(Beans.asLong(rr.get("ReadRepairAttempted"))));
        kv(repair, "Mismatch (Blocking)", Fmt.num(Beans.asLong(rr.get("ReadRepairRepairedBlocking"))));
        kv(repair, "Mismatch (Background)", Fmt.num(Beans.asLong(rr.get("ReadRepairRepairedBackground"))));
        List<List<String>> pools = new ArrayList<>();
        for (String kind : List.of("Large", "Small", "Gossip")) {
            Map<String, Object> a = b.attrs(MESSAGING, kind + "MessagePendingTasks", kind + "MessageCompletedTasks",
                    kind + "MessageDroppedTasks");
            pools.add(Arrays.asList(kind + " messages", "n/a", Fmt.num(sumValues(a.get(kind + "MessagePendingTasks"))),
                    Fmt.num(sumValues(a.get(kind + "MessageCompletedTasks"))), Fmt.num(sumValues(a.get(kind + "MessageDroppedTasks")))));
        }
        return new View("netstats", node, cmd(node, "netstats"), List.of(
                Section.keyValues("Summary", summary),
                Section.table("Streams", List.of("Description", "Plan", "Peers", "Received", "Received %", "Sent", "Sent %"), streams),
                Section.keyValues("Read Repair Statistics", repair),
                Section.table("Messaging", List.of("Pool Name", "Active", "Pending", "Completed", "Dropped"), pools)),
                streams.isEmpty() ? List.of("Not sending or receiving any streams.") : List.of());
    }

    private static Object get(CompositeData cd, String key) {
        return cd.containsKey(key) ? cd.get(key) : null;
    }

    private static Long sumValues(Object map) {
        if (!(map instanceof Map<?, ?> m)) return null;
        long s = 0;
        for (Object v : m.values()) {
            Long l = Beans.asLong(v);
            if (l != null) s += l;
        }
        return s;
    }

    static View getendpoints(Beans b, String node, Params p) {
        if (p.keyspace() == null || p.table() == null || p.key() == null) {
            throw ApiException.badRequest("getendpoints needs keyspace, table and key");
        }
        Beans.Call call = Beans.Call.of(Beans.STR, p.keyspace(), Beans.STR, p.table(), Beans.STR, p.key());
        String op = b.has(STORAGE_SERVICE, "getNaturalEndpointsWithPort", call.signature())
                ? "getNaturalEndpointsWithPort" : "getNaturalEndpoints";
        Object eps;
        try {
            eps = b.invoke(STORAGE_SERVICE, op, call);
        } catch (OpsException e) {
            throw ApiException.badRequest("getendpoints: " + e.getMessage());
        }
        List<List<String>> rows = new ArrayList<>();
        for (String ep : Beans.addresses(eps)) rows.add(List.of(ep));
        return new View("getendpoints", node, cmd(node, "getendpoints -- " + p.keyspace() + " " + p.table() + " "
                + com.cassandrastudio.engine.ssh.NodeShell.quote(p.key())), List.of(Section.table("Replicas", List.of("Endpoint"), rows)),
                List.of());
    }

    private static void kv(List<List<String>> rows, String k, String v) {
        rows.add(Arrays.asList(k, v));
    }
}
