package com.cassandrastudio.engine.diag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;

/**
 * Per-table histograms like nodetool tablehistograms (PRF-2): estimated partition size and cell
 * count (from the SSTables' EstimatedHistogram buckets), tombstones scanned and SSTables touched
 * per read (decaying read histograms), with percentiles and large-partition / tombstone flags.
 */
public final class TableHistograms {
    private TableHistograms() {}

    static final String[] PCTS = {"50thPercentile", "75thPercentile", "95thPercentile", "98thPercentile",
            "99thPercentile", "Max", "Count"};

    /** Percentiles of one histogram; any value null when the node does not report it. */
    public record Pcts(Double p50, Double p75, Double p95, Double p98, Double p99, Double min, Double max, Long count) {
        static final Pcts EMPTY = new Pcts(null, null, null, null, null, null, null, null);

        static Pcts maxOf(Pcts a, Pcts b) {
            return new Pcts(mx(a.p50, b.p50), mx(a.p75, b.p75), mx(a.p95, b.p95), mx(a.p98, b.p98), mx(a.p99, b.p99),
                    mn(a.min, b.min), mx(a.max, b.max), a.count == null ? b.count : b.count == null ? a.count
                            : Long.valueOf(a.count + b.count));
        }

        private static Double mx(Double x, Double y) {
            if (x == null) return y;
            return y == null ? x : Double.valueOf(Math.max(x, y));
        }

        private static Double mn(Double x, Double y) {
            if (x == null) return y;
            return y == null ? x : Double.valueOf(Math.min(x, y));
        }
    }

    public record Thresholds(long largePartitionBytes, double tombstonesP99) {}

    /**
     * One table on one node (or all nodes: the maximum of each percentile, counts summed).
     * {@code flags} name what is over a threshold: LARGE_PARTITION, TOMBSTONES.
     */
    public record TableHist(String keyspace, String table, String node, Pcts partitionSize, Pcts cellCount,
                            Pcts tombstonesPerRead, Pcts sstablesPerRead, Pcts liveCellsPerRead, List<String> flags) {}

    public record NodeError(String node, String error) {}

    public record View(List<TableHist> merged, List<TableHist> perNode, List<NodeError> errors, Thresholds thresholds) {}

    // ---- EstimatedHistogram (org.apache.cassandra.utils.EstimatedHistogram) -------------------

    /** Bucket offsets: 1, 2, 3 ... growing by 1.2, the same series Cassandra uses. */
    static long[] offsets(int size) {
        long[] o = new long[size];
        long last = 1;
        o[0] = last;
        for (int i = 1; i < size; i++) {
            long next = Math.round(last * 1.2);
            if (next == last) next++;
            o[i] = next;
            last = next;
        }
        return o;
    }

    /**
     * Percentiles of raw EstimatedHistogram buckets as the gauge returns them (one bucket per
     * offset plus an overflow bucket). Values in overflow make the max unknown (reported as null).
     */
    static Pcts estimated(long[] buckets) {
        if (buckets == null || buckets.length < 2) return Pcts.EMPTY;
        long[] off = offsets(buckets.length - 1);
        int last = buckets.length - 1;
        long count = 0;
        for (long b : buckets) count += b;
        if (count == 0) return new Pcts(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L);
        boolean overflow = buckets[last] > 0;
        Double min = null;
        Double max = overflow ? null : 0.0;
        for (int i = 0; i < last; i++) {
            if (buckets[i] > 0) {
                if (min == null) min = (double) (i == 0 ? 0 : off[i - 1] + 1);
                if (!overflow) max = (double) off[i];
            }
        }
        return new Pcts(pct(buckets, off, count, 0.5), pct(buckets, off, count, 0.75), pct(buckets, off, count, 0.95),
                pct(buckets, off, count, 0.98), pct(buckets, off, count, 0.99), min, max, count);
    }

    private static Double pct(long[] buckets, long[] off, long count, double p) {
        long target = (long) Math.ceil(count * p);
        if (target == 0) return 0.0;
        long seen = 0;
        for (int i = 0; i < off.length; i++) {
            seen += buckets[i];
            if (seen >= target) return (double) off[i];
        }
        return null; // in the overflow bucket
    }

    // ---- reading ------------------------------------------------------------------------

    static boolean systemKeyspace(String ks) {
        return ks.equals("system") || ks.startsWith("system_") || ks.startsWith("dse_") || ks.equals("solr_admin")
                || ks.equals("cfs") || ks.equals("cfs_archive") || ks.equals("OpsCenter");
    }

    /** Reads every (non-system) table of one node, optionally one keyspace. */
    static List<TableHist> read(MBeanServerConnection c, String node, String keyspace, boolean includeSystem,
                                Thresholds th) {
        String type = "Table";
        var names = Jmx.query(c, "org.apache.cassandra.metrics:type=Table,name=EstimatedPartitionSizeHistogram,*");
        if (names.isEmpty()) {
            type = "ColumnFamily";
            names = Jmx.query(c, "org.apache.cassandra.metrics:type=ColumnFamily,name=EstimatedRowSizeHistogram,*");
        }
        List<TableHist> out = new ArrayList<>();
        for (ObjectName n : names) {
            String ks = n.getKeyProperty("keyspace");
            String table = n.getKeyProperty("scope");
            if (ks == null || table == null) continue; // keyspace-level aggregate
            if (keyspace != null && !keyspace.equals(ks)) continue;
            if (keyspace == null && !includeSystem && systemKeyspace(ks)) continue;
            String base = "org.apache.cassandra.metrics:type=" + type + ",keyspace=" + ks + ",scope=" + table + ",name=";
            Pcts size = estimated(longs(Jmx.attribute(c, n.toString(), "Value")));
            Long maxGauge = Jmx.asLong(Jmx.attribute(c, base + "MaxPartitionSize", "Value"));
            if (maxGauge != null && maxGauge > 0) {
                size = new Pcts(size.p50(), size.p75(), size.p95(), size.p98(), size.p99(), size.min(),
                        (double) maxGauge, size.count());
            }
            Pcts cells = estimated(longs(Jmx.attribute(c, base + "EstimatedColumnCountHistogram", "Value")));
            Pcts tomb = decaying(c, base + "TombstoneScannedHistogram");
            Pcts sst = decaying(c, base + "SSTablesPerReadHistogram");
            Pcts live = decaying(c, base + "LiveScannedHistogram");
            out.add(new TableHist(ks, table, node, size, cells, tomb, sst, live, flags(size, tomb, th)));
        }
        out.sort(Comparator.comparing(TableHist::keyspace).thenComparing(TableHist::table));
        return out;
    }

    private static long[] longs(Object v) {
        return v instanceof long[] l ? l : null;
    }

    private static Pcts decaying(MBeanServerConnection c, String bean) {
        Map<String, Object> a = Jmx.attributes(c, bean, PCTS);
        if (a.isEmpty()) return Pcts.EMPTY;
        Long max = Jmx.asLong(a.get("Max"));
        return new Pcts(Jmx.asDouble(a.get("50thPercentile")), Jmx.asDouble(a.get("75thPercentile")),
                Jmx.asDouble(a.get("95thPercentile")), Jmx.asDouble(a.get("98thPercentile")),
                Jmx.asDouble(a.get("99thPercentile")), null, max == null ? null : max.doubleValue(),
                Jmx.asLong(a.get("Count")));
    }

    static List<String> flags(Pcts size, Pcts tomb, Thresholds th) {
        List<String> f = new ArrayList<>();
        if (size.max() != null && size.max() > th.largePartitionBytes()) f.add("LARGE_PARTITION");
        if (tomb.p99() != null && tomb.p99() > th.tombstonesP99()) f.add("TOMBSTONES");
        return f;
    }

    /** One row per table over all nodes: the worst value of each percentile. */
    static List<TableHist> merge(List<TableHist> perNode, Thresholds th) {
        Map<String, TableHist> by = new LinkedHashMap<>();
        for (TableHist h : perNode) {
            by.merge(h.keyspace() + "." + h.table(), h, (a, b) -> new TableHist(a.keyspace(), a.table(), null,
                    Pcts.maxOf(a.partitionSize(), b.partitionSize()), Pcts.maxOf(a.cellCount(), b.cellCount()),
                    Pcts.maxOf(a.tombstonesPerRead(), b.tombstonesPerRead()),
                    Pcts.maxOf(a.sstablesPerRead(), b.sstablesPerRead()),
                    Pcts.maxOf(a.liveCellsPerRead(), b.liveCellsPerRead()), List.of()));
        }
        List<TableHist> out = new ArrayList<>();
        for (TableHist h : by.values()) {
            out.add(new TableHist(h.keyspace(), h.table(), null, h.partitionSize(), h.cellCount(), h.tombstonesPerRead(),
                    h.sstablesPerRead(), h.liveCellsPerRead(), flags(h.partitionSize(), h.tombstonesPerRead(), th)));
        }
        out.sort(Comparator.comparing((TableHist h) -> h.flags().isEmpty()).thenComparing(TableHist::keyspace)
                .thenComparing(TableHist::table));
        return out;
    }
}
