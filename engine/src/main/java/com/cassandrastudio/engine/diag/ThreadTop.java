package com.cassandrastudio.engine.diag;

import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.management.MBeanServerConnection;
import javax.management.openmbean.CompositeData;

/**
 * Top threads by CPU like sjk ttop (JVM-2): per-thread CPU, user time and allocation deltas
 * between two samples. One sample costs a handful of JMX round trips whatever the thread count:
 * the array forms of {@code getThreadCpuTime/UserTime/AllocatedBytes(long[])} (HotSpot's
 * com.sun.management.ThreadMXBean), with a per-thread fallback for JVMs without them.
 */
public final class ThreadTop {
    private ThreadTop() {}

    /** Per-thread fallback reads at most this many threads (one round trip each). */
    static final int FALLBACK_MAX_THREADS = 400;

    /** Raw counters of one thread at one instant; -1 = not available. */
    record Counters(String name, String state, long cpuNs, long userNs, long allocBytes) {}

    /** One sample of the whole JVM. {@code processCpuNs} -1 when the OS bean lacks it. */
    record Sample(long atNanos, long atMs, Map<Long, Counters> threads, long processCpuNs, int processors,
                  boolean allocSupported, boolean bulk) {}

    /** One row of the top view. Percentages are of one core (100 % = a core fully busy), as in ttop. */
    public record Row(String name, Long id, int threads, String state, double cpuPct, double userPct,
                      Double allocBytesPerSec, long cpuTotalMs) {}

    public record TopView(String node, long atMs, long intervalMs, boolean firstSample, Double processCpuPct,
                          Integer processors, double threadsCpuPct, int threadCount, boolean allocSupported,
                          boolean grouped, String method, List<Row> rows) {}

    static Sample sample(MBeanServerConnection c, long nowNanos, long nowMs) {
        long[] ids = (long[]) Jmx.attribute(c, Jmx.THREADING, "AllThreadIds");
        if (ids == null) throw new IllegalStateException("The node's Threading MBean returned no thread ids");
        boolean bulk = true;
        long[] cpu;
        long[] user;
        long[] alloc = null;
        String[] arr = {long[].class.getName()};
        try {
            cpu = (long[]) Jmx.call(c, Jmx.THREADING, "getThreadCpuTime", new Object[] {ids}, arr);
            user = (long[]) Jmx.call(c, Jmx.THREADING, "getThreadUserTime", new Object[] {ids}, arr);
        } catch (Jmx.Missing e) {
            bulk = false;
            int n = Math.min(ids.length, FALLBACK_MAX_THREADS);
            long[] cut = java.util.Arrays.copyOf(ids, n);
            cpu = new long[n];
            user = new long[n];
            String[] one = {long.class.getName()};
            for (int i = 0; i < n; i++) {
                cpu[i] = asLong(Jmx.call(c, Jmx.THREADING, "getThreadCpuTime", new Object[] {cut[i]}, one));
                user[i] = asLong(Jmx.call(c, Jmx.THREADING, "getThreadUserTime", new Object[] {cut[i]}, one));
            }
            ids = cut;
        }
        if (bulk) {
            try {
                alloc = (long[]) Jmx.call(c, Jmx.THREADING, "getThreadAllocatedBytes", new Object[] {ids}, arr);
            } catch (Jmx.Missing | IllegalStateException e) {
                alloc = null; // not HotSpot, or allocation accounting disabled
            }
        }
        CompositeData[] infos = (CompositeData[]) Jmx.call(c, Jmx.THREADING, "getThreadInfo", new Object[] {ids, 0},
                new String[] {long[].class.getName(), int.class.getName()});
        Map<Long, Counters> threads = new LinkedHashMap<>();
        for (int i = 0; i < ids.length; i++) {
            CompositeData cd = infos != null && i < infos.length ? infos[i] : null;
            if (cd == null) continue; // the thread ended between the calls
            ThreadInfo ti = ThreadInfo.from(cd);
            threads.put(ids[i], new Counters(ti.getThreadName(), ti.getThreadState().name(),
                    i < cpu.length ? cpu[i] : -1, i < user.length ? user[i] : -1,
                    alloc != null && i < alloc.length ? alloc[i] : -1));
        }
        Map<String, Object> os = Jmx.attributes(c, Jmx.OS, "ProcessCpuTime", "AvailableProcessors");
        Long pc = Jmx.asLong(os.get("ProcessCpuTime"));
        Long np = Jmx.asLong(os.get("AvailableProcessors"));
        boolean allocOk = alloc != null && threads.values().stream().anyMatch(t -> t.allocBytes() > 0);
        return new Sample(nowNanos, nowMs, threads, pc == null ? -1 : pc, np == null ? 0 : np.intValue(), allocOk, bulk);
    }

    private static long asLong(Object o) {
        Long l = Jmx.asLong(o);
        return l == null ? -1 : l;
    }

    private static final Pattern HASH_NUM = Pattern.compile("#\\d+");
    private static final Pattern PAREN_NUM_IP = Pattern.compile("\\(\\d+\\)|-\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern TRAILING_NUM = Pattern.compile("([-:_ .#]*\\d+)+$");

    /**
     * Pool name of a thread: numeric suffixes stripped, e.g. "ReadStage-12" and "ReadStage:3" →
     * "ReadStage", "nioEventLoopGroup-2-3" → "nioEventLoopGroup", "GC Thread#3" → "GC Thread#",
     * "RMI TCP Connection(463)-10.0.0.1" → "RMI TCP Connection".
     */
    public static String poolName(String name) {
        String s = HASH_NUM.matcher(PAREN_NUM_IP.matcher(name).replaceAll("")).replaceAll("#");
        String t = TRAILING_NUM.matcher(s).replaceAll("");
        return t.isBlank() ? s : t;
    }

    /** Delta between two samples, top {@code limit} rows by CPU. {@code prev} null = first sample (totals only). */
    static TopView view(String node, Sample prev, Sample cur, int limit, boolean group) {
        long wallNs = prev == null ? 0 : Math.max(1, cur.atNanos() - prev.atNanos());
        Map<String, double[]> acc = new LinkedHashMap<>(); // key -> cpuNs, userNs, alloc, count, totalCpuNs
        Map<String, Long> idOf = new HashMap<>();
        Map<String, String> stateOf = new HashMap<>();
        double sumCpu = 0;
        for (var e : cur.threads().entrySet()) {
            Counters c = e.getValue();
            Counters p = prev == null ? null : prev.threads().get(e.getKey());
            long dCpu = delta(c.cpuNs(), p == null ? 0 : p.cpuNs(), prev != null);
            long dUser = delta(c.userNs(), p == null ? 0 : p.userNs(), prev != null);
            long dAlloc = delta(c.allocBytes(), p == null ? 0 : p.allocBytes(), prev != null);
            sumCpu += dCpu;
            String key = group ? poolName(c.name()) : c.name() + "#" + e.getKey();
            double[] a = acc.computeIfAbsent(key, k -> new double[5]);
            a[0] += dCpu;
            a[1] += dUser;
            a[2] += dAlloc;
            a[3] += 1;
            a[4] += Math.max(0, c.cpuNs());
            if (!group) idOf.put(key, e.getKey());
            stateOf.merge(key, c.state(), (x, y) -> x.equals(y) ? x : "mixed");
        }
        List<Row> rows = new ArrayList<>();
        for (var e : acc.entrySet()) {
            double[] a = e.getValue();
            String name = group ? e.getKey() : e.getKey().substring(0, e.getKey().lastIndexOf('#'));
            double cpuPct = prev == null ? 0 : 100.0 * a[0] / wallNs;
            double userPct = prev == null ? 0 : 100.0 * a[1] / wallNs;
            Double alloc = prev == null || !cur.allocSupported() ? null : a[2] * 1e9 / wallNs;
            rows.add(new Row(name, idOf.get(e.getKey()), (int) a[3], stateOf.get(e.getKey()), round(cpuPct),
                    round(userPct), alloc == null ? null : (double) Math.round(alloc), (long) (a[4] / 1_000_000)));
        }
        // before the first delta, rank by total CPU since start so the view is already useful
        Comparator<Row> order = prev == null ? Comparator.comparingLong(Row::cpuTotalMs).reversed()
                : Comparator.comparingDouble(Row::cpuPct).reversed()
                        .thenComparing(Comparator.comparing((Row r) -> r.allocBytesPerSec() == null ? 0 : r.allocBytesPerSec()).reversed());
        rows.sort(order.thenComparing(Row::name));
        Double proc = null;
        if (prev != null && cur.processCpuNs() >= 0 && prev.processCpuNs() >= 0 && cur.processors() > 0) {
            proc = round(100.0 * (cur.processCpuNs() - prev.processCpuNs()) / wallNs / cur.processors());
        }
        return new TopView(node, cur.atMs(), prev == null ? 0 : wallNs / 1_000_000, prev == null, proc,
                cur.processors() > 0 ? cur.processors() : null, prev == null ? 0 : round(100.0 * sumCpu / wallNs),
                cur.threads().size(), cur.allocSupported(), group, cur.bulk() ? "bulk" : "per-thread",
                rows.subList(0, Math.min(limit, rows.size())));
    }

    /** Counter delta; a thread new since the last sample counts from 0, an unavailable counter as 0. */
    private static long delta(long now, long before, boolean havePrev) {
        if (!havePrev || now < 0) return 0;
        return Math.max(0, now - Math.max(0, before));
    }

    private static double round(double d) {
        return Math.round(d * 10) / 10.0;
    }
}
