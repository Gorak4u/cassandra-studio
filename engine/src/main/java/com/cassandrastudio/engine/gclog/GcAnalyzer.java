package com.cassandrastudio.engine.gclog;

import com.cassandrastudio.engine.gclog.GcReport.Bucket;
import com.cassandrastudio.engine.gclog.GcReport.Count;
import com.cassandrastudio.engine.gclog.GcReport.Finding;
import com.cassandrastudio.engine.gclog.GcReport.Heap;
import com.cassandrastudio.engine.gclog.GcReport.Rate;
import com.cassandrastudio.engine.gclog.GcReport.Safepoints;
import com.cassandrastudio.engine.gclog.GcReport.Stalls;
import com.cassandrastudio.engine.gclog.GcReport.Stats;
import com.cassandrastudio.engine.gclog.GcReport.Summary;
import com.cassandrastudio.engine.gclog.GcReport.TypeStats;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Turns a parsed {@link GcLog} into a {@link GcReport} for the whole log or a time window:
 * summary statistics (GCL-3), bucketed chart series (GCL-2) and tuning findings (GCL-4).
 * Pure: no I/O, so the rules are unit-tested against fixtures.
 */
public final class GcAnalyzer {
    /** At most this many events go to the UI; the summary always uses all of them. */
    public static final int MAX_EVENTS = 20_000;
    static final double LONG_PAUSE_MS = 500;
    private static final double[] HISTOGRAM = {0, 1, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000};
    private static final double[] BUCKET_STEPS = {1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 10800,
            21600, 43200, 86400, 172800, 604800};
    private static final int MAX_BUCKETS = 240;

    private GcAnalyzer() {}

    public static GcReport analyze(GcLog log, String id, String name, GcReport.Source source, long createdAtMs,
                                   Double fromX, Double toX) {
        double start = log.startX, end = Math.max(log.endX, log.startX);
        boolean whole = fromX == null && toX == null;
        double from = fromX == null ? start : Math.max(start, fromX);
        double to = toX == null ? end : Math.min(end, toX);
        if (to < from) to = from;
        final double f = from, t = to;
        Predicate<Double> in = x -> x >= f && x <= t;
        List<GcEvent> ev = new ArrayList<>();
        for (GcEvent e : log.events) if (in.test(e.x)) ev.add(e);
        double duration = Math.max(to - from, 0.001);

        List<GcEvent> pauses = ev.stream().filter(GcEvent::isPause).toList();
        double[] d = pauses.stream().mapToDouble(e -> e.durationMs).sorted().toArray();
        Stats stats = stats(d);
        double pauseTotal = stats.totalMs();
        double gcPct = Math.min(100, pauseTotal / (duration * 1000) * 100);

        List<GcEvent> fulls = pauses.stream().filter(e -> GcEvent.FULL.equals(e.category)).toList();
        long cmf = count(ev, "concurrent mode failure"), pf = count(ev, "promotion failed");
        if (whole) {
            cmf = Math.max(cmf, log.concurrentModeFailures);
            pf = Math.max(pf, log.promotionFailures);
        }

        // safepoints and stalls inside the window
        double spTotal = 0, spMax = 0;
        long spCount = 0;
        for (int i = 0; i < log.safepointPoints.size(); i++) {
            if (!in.test(log.safepointPoints.x(i))) continue;
            spCount++;
            spTotal += log.safepointPoints.v(i);
            spMax = Math.max(spMax, log.safepointPoints.v(i));
        }
        double stTotal = 0, stMax = 0;
        long stCount = 0;
        for (int i = 0; i < log.stallPoints.size(); i++) {
            if (!in.test(log.stallPoints.x(i))) continue;
            stCount++;
            stTotal += log.stallPoints.v(i);
            stMax = Math.max(stMax, log.stallPoints.v(i));
        }
        List<TypeStats> reasons = new ArrayList<>();
        log.safepointReasons.forEach((k, v) -> reasons.add(new TypeStats(k, null, "safepoint", (long) v[0], round(v[1]),
                round(v[1] / Math.max(1, v[0])), round(v[2]))));
        reasons.sort(Comparator.comparingDouble(TypeStats::totalMs).reversed());
        Safepoints sp = new Safepoints(spCount, round(spTotal), round(spMax), whole ? round(log.ttspTotalMs) : 0,
                whole ? round(log.ttspMaxMs) : 0, round(Math.min(100, spTotal / (duration * 1000) * 100)),
                reasons.subList(0, Math.min(10, reasons.size())));

        // rates
        double bucket = bucketSec(duration);
        int nb = (int) Math.ceil(duration / bucket) + 1;
        double[] gcB = new double[nb], allocB = new double[nb], promoB = new double[nb], spB = new double[nb];
        for (GcEvent e : pauses) gcB[idx(e.x, from, bucket, nb)] += e.durationMs;
        for (int i = 0; i < log.safepointPoints.size(); i++) {
            double x = log.safepointPoints.x(i);
            if (in.test(x)) spB[idx(x, from, bucket, nb)] += log.safepointPoints.v(i);
        }
        double allocTotalK = 0;
        boolean haveAlloc = false;
        GcEvent prev = null;
        for (GcEvent e : ev) {
            if (e.heapBeforeK == null || e.heapAfterK == null) continue;
            if (prev != null) {
                long a = e.heapBeforeK - prev.heapAfterK;
                if (a >= 0) {
                    allocTotalK += a;
                    allocB[idx(e.x, from, bucket, nb)] += a;
                    haveAlloc = true;
                }
            }
            prev = e;
        }
        double promoTotalK = 0;
        boolean havePromo = false;
        for (GcEvent e : pauses) {
            if ((GcEvent.YOUNG.equals(e.category) || GcEvent.MIXED.equals(e.category)) && e.oldBeforeK != null
                    && e.oldAfterK != null && !GcEvent.MIXED.equals(e.category)) {
                long p = Math.max(0, e.oldAfterK - e.oldBeforeK);
                promoTotalK += p;
                promoB[idx(e.x, from, bucket, nb)] += p;
                havePromo = true;
            }
        }
        List<double[]> gcSeries = new ArrayList<>(), allocSeries = new ArrayList<>(), promoSeries = new ArrayList<>(),
                spSeries = new ArrayList<>();
        double allocPeak = 0, promoPeak = 0;
        for (int i = 0; i < nb; i++) {
            double x = from + i * bucket;
            if (x > to) break;
            double width = Math.min(bucket, Math.max(to - x, 0.001));
            gcSeries.add(new double[] {round(x), round(Math.min(100, gcB[i] / (width * 1000) * 100))});
            double ar = allocB[i] / 1024 / bucket, pr = promoB[i] / 1024 / bucket;
            if (haveAlloc) allocSeries.add(new double[] {round(x), round(ar)});
            if (havePromo) promoSeries.add(new double[] {round(x), round(pr)});
            if (spCount > 0) spSeries.add(new double[] {round(x), round(spB[i])});
            if (width >= bucket * 0.999) {
                allocPeak = Math.max(allocPeak, ar);
                promoPeak = Math.max(promoPeak, pr);
            }
        }
        Rate alloc = haveAlloc ? new Rate(round(allocTotalK / 1024 / duration), round(Math.max(allocPeak, allocTotalK / 1024 / duration)),
                round(allocTotalK / 1024)) : new Rate(null, null, null);
        Rate promo = havePromo ? new Rate(round(promoTotalK / 1024 / duration), round(Math.max(promoPeak, promoTotalK / 1024 / duration)),
                round(promoTotalK / 1024)) : new Rate(null, null, null);

        Long heapMax = log.heapMaxK != null ? log.heapMaxK : maxOf(ev, e -> e.heapTotalK);
        Heap heap = new Heap(heapMax, maxOf(ev, e -> e.heapBeforeK), maxOf(pauses, e -> e.heapAfterK),
                avgOf(pauses, e -> e.heapAfterK), maxOf(pauses, e -> e.oldAfterK),
                maxOf(ev, e -> e.metaAfterK != null ? e.metaAfterK : e.metaBeforeK));

        long humongous = pauses.stream().filter(e -> e.hasFlag("humongous")).count();
        Summary summary = new Summary(round(duration), stats, histogram(d), byType(pauses), round(gcPct),
                round(100 - gcPct), fulls.size(), causes(fulls), byType(ev.stream().filter(e -> !e.isPause()).toList()),
                humongous, maxOf(pauses, e -> e.humongousBeforeK), count(ev, "to-space exhausted"),
                count(ev, "evacuation failure"), cmf, pf, count(ev, "degenerated"),
                new Stalls(stCount, round(stTotal), round(stMax)), sp, heap, alloc, promo, causes(pauses));

        List<Finding> findings = findings(log, ev, pauses, summary, duration);
        GcReport.Series series = new GcReport.Series(bucket, gcSeries, allocSeries, promoSeries, spSeries);
        List<GcEvent> shown = sample(ev, stats.p99Ms());
        GcReport.LogInfo info = new GcReport.LogInfo(log.format, log.collector, log.jvmVersion, log.javaMajor(),
                log.jvmFlags, log.heapMaxK, log.regionSizeK, log.timeAxis, log.startTs, round(start), round(end),
                log.lines, log.bytes, log.events.size(), log.warnings);
        return new GcReport(id, name, source, createdAtMs, info, new GcReport.Range(round(from), round(to), whole), summary,
                series, findings, shown, shown.size() < ev.size());
    }

    // ---- findings (GCL-4) ----------------------------------------------------------------

    static List<Finding> findings(GcLog log, List<GcEvent> ev, List<GcEvent> pauses, Summary s, double duration) {
        List<Finding> out = new ArrayList<>();
        Integer java = log.javaMajor();
        if (java == null && "CMS".equals(log.collector) && "unified".equals(log.format)) java = 11;
        String gcFile = gcOptionsFile(java);
        String heapFile = heapOptionsFile(java);
        String collector = log.collector == null ? "" : log.collector;

        // long pauses
        List<GcEvent> longOnes = pauses.stream().filter(e -> e.durationMs > LONG_PAUSE_MS).toList();
        if (!longOnes.isEmpty()) {
            GcEvent worst = longOnes.stream().max(Comparator.comparingDouble(e -> e.durationMs)).orElseThrow();
            String hint = switch (collector) {
                case "G1" -> "Check that the heap is large enough and not over-committed; keep -XX:MaxGCPauseMillis at "
                        + "200-500 ms and avoid fixing the young generation size (-Xmn) with G1. Full GCs in this list "
                        + "have their own finding.";
                case "CMS" -> "Long ParNew pauses come from a large young generation or many surviving objects: lower "
                        + "-Xmn (HEAP_NEWSIZE) or consider G1. Long full collections mean CMS could not keep up.";
                default -> "Reduce the live set or the young generation size, or use a concurrent collector (G1).";
            };
            out.add(new Finding("long-pauses", worst.durationMs >= 2000 ? "critical" : "warning",
                    "Pauses longer than " + (int) LONG_PAUSE_MS + " ms",
                    "Every pause stops reads and writes on the node. Pauses over 500 ms add directly to request "
                            + "latency, and pauses near write_request_timeout (2 s) or read_request_timeout (5 s) make "
                            + "coordinators time out and the node look down to gossip.",
                    List.of(longOnes.size() + " of " + pauses.size() + " pauses over " + (int) LONG_PAUSE_MS + " ms",
                            "Longest: " + fmtMs(worst.durationMs) + " (" + describe(worst) + ")"),
                    hint, List.of("-XX:MaxGCPauseMillis", "-Xmn", "-Xmx"), gcFile, worst.x));
        }

        // time in GC
        if (s.gcTimePct() >= 5 && duration >= 60) {
            out.add(new Finding("gc-time", s.gcTimePct() >= 10 ? "critical" : "warning",
                    "High share of time in GC: " + pct(s.gcTimePct()),
                    "The JVM spent " + pct(s.gcTimePct()) + " of the analysed time in stop-the-world pauses "
                            + "(throughput " + pct(s.throughputPct()) + "). Healthy nodes stay under 5 %.",
                    List.of(fmtMs(s.pauses().totalMs()) + " paused in " + fmtDuration(duration),
                            s.pauses().count() + " pauses, average " + fmtMs(s.pauses().avgMs())),
                    "Usually the heap is too small for the live data or the allocation rate is high (large partitions, "
                            + "big batches, wide reads). Give the heap more room (-Xmx) and look at the allocation rate.",
                    List.of("-Xmx", "-Xmn"), heapFile, null));
        }

        // full GCs
        List<GcEvent> fulls = pauses.stream().filter(e -> GcEvent.FULL.equals(e.category)).toList();
        List<GcEvent> metaFulls = fulls.stream().filter(e -> metaCause(e.cause)).toList();
        List<GcEvent> explicitFulls = fulls.stream().filter(e -> e.cause != null && e.cause.contains("System.gc()")).toList();
        if (!metaFulls.isEmpty()) {
            out.add(new Finding("full-gc-metaspace", "warning", "Full GC caused by metaspace",
                    "Metaspace (class metadata) reached its threshold and the JVM ran a full collection to unload classes.",
                    List.of(metaFulls.size() + " full GC(s) with cause " + metaFulls.get(0).cause,
                            "Peak metaspace: " + fmtK(s.heap().peakMetaK())),
                    "Raise the initial threshold with -XX:MetaspaceSize (e.g. 256m) so start-up does not trigger "
                            + "collections; if -XX:MaxMetaspaceSize is set, raise it or remove it.",
                    List.of("-XX:MetaspaceSize", "-XX:MaxMetaspaceSize"), gcFile, metaFulls.get(0).x));
        }
        if (!explicitFulls.isEmpty()) {
            out.add(new Finding("full-gc-explicit", "warning", "Explicit System.gc() full collections",
                    "Something called System.gc() (a tool, RMI distributed GC, or a direct buffer cleaner).",
                    List.of(explicitFulls.size() + " full GC(s) with cause System.gc()"),
                    "Add -XX:+ExplicitGCInvokesConcurrent so explicit calls run a concurrent cycle instead of a full "
                            + "stop-the-world collection, or -XX:+DisableExplicitGC.",
                    List.of("-XX:+ExplicitGCInvokesConcurrent", "-XX:+DisableExplicitGC"), gcFile, explicitFulls.get(0).x));
        }
        List<GcEvent> otherFulls = fulls.stream().filter(e -> !metaFulls.contains(e) && !explicitFulls.contains(e)).toList();
        if (!otherFulls.isEmpty()) {
            GcEvent worst = otherFulls.stream().max(Comparator.comparingDouble(e -> e.durationMs)).orElseThrow();
            Map<String, Long> by = new LinkedHashMap<>();
            otherFulls.forEach(e -> by.merge(e.cause == null ? "unknown" : e.cause, 1L, Long::sum));
            String hint = switch (collector) {
                case "G1" -> "G1 fell back to a full collection: give it more headroom (-Xmx), start marking earlier "
                        + "(-XX:InitiatingHeapOccupancyPercent lower, e.g. 35) or keep more reserve (-XX:G1ReservePercent=15..20).";
                case "CMS" -> "CMS could not finish its concurrent cycle in time: start it earlier with "
                        + "-XX:CMSInitiatingOccupancyFraction (e.g. 70 → 60) and -XX:+UseCMSInitiatingOccupancyOnly, or add heap.";
                default -> "The old generation filled up: add heap (-Xmx) or reduce the live set.";
            };
            out.add(new Finding("full-gc", otherFulls.size() >= 5 ? "critical" : "warning",
                    otherFulls.size() + " full GC" + (otherFulls.size() == 1 ? "" : "s"),
                    "Full collections stop the node for the whole heap. A healthy Cassandra node should have none.",
                    List.of("Causes: " + by.entrySet().stream().map(en -> en.getKey() + " ×" + en.getValue()).toList(),
                            "Longest: " + fmtMs(worst.durationMs)),
                    hint, List.of("-Xmx", "-XX:InitiatingHeapOccupancyPercent", "-XX:CMSInitiatingOccupancyFraction"),
                    gcFile, worst.x));
        }

        // occupancy after GC
        Long heapMax = s.heap().maxK();
        List<GcEvent> full90 = new ArrayList<>();
        for (GcEvent e : pauses) {
            Double occ = null;
            if (e.oldAfterK != null && e.oldTotalK != null && e.oldTotalK > 0) occ = (double) e.oldAfterK / e.oldTotalK;
            else if (e.heapAfterK != null && heapMax != null && heapMax > 0
                    && (GcEvent.FULL.equals(e.category) || "G1".equals(collector))) occ = (double) e.heapAfterK / heapMax;
            if (occ != null && occ >= 0.9) full90.add(e);
        }
        boolean afterFull = full90.stream().anyMatch(e -> GcEvent.FULL.equals(e.category));
        if (afterFull || full90.size() >= 3) {
            GcEvent last = full90.get(full90.size() - 1);
            out.add(new Finding("heap-too-small", "critical", "Heap too small: old generation above 90 % after GC",
                    "After collections the old generation (or the whole heap) stays more than 90 % full, so the live "
                            + "data barely fits. Expect frequent and long collections and, eventually, OutOfMemoryError.",
                    List.of(full90.size() + " collection(s) left the old generation ≥ 90 % full",
                            "Last: " + fmtK(last.oldAfterK != null ? last.oldAfterK : last.heapAfterK) + " used after GC"),
                    "Increase the heap (MAX_HEAP_SIZE / -Xmx; Cassandra: 8-16 GB with CMS, up to 31 GB with G1), or "
                            + "reduce what lives on heap (key/row caches, memtable_heap_space, large partitions).",
                    List.of("-Xmx", "-Xms"), heapFile, last.x));
        }

        // G1 specifics
        long humongous = s.humongousAllocations();
        if (humongous > 0) {
            GcEvent first = pauses.stream().filter(e -> e.hasFlag("humongous")).findFirst().orElse(null);
            String region = log.regionSizeK == null ? "unknown" : fmtK(log.regionSizeK);
            out.add(new Finding("humongous", humongous >= 10 ? "warning" : "info", "Humongous allocations (G1)",
                    "Objects of half a region or more (region size " + region + ") are allocated straight into old-"
                            + "generation regions; they fragment the heap and trigger extra collections.",
                    List.of(humongous + " pause(s) triggered by G1 Humongous Allocation",
                            "Peak humongous occupancy: " + fmtK(s.humongousPeakK())),
                    "Raise -XX:G1HeapRegionSize (16m or 32m) so the large buffers (commit log segments, big "
                            + "partitions, batches) fit in a region; or reduce those object sizes.",
                    List.of("-XX:G1HeapRegionSize"), gcFile, first == null ? null : first.x));
        }
        long evac = s.toSpaceExhausted() + s.evacuationFailures();
        if (evac > 0) {
            GcEvent first = pauses.stream().filter(e -> e.hasFlag("to-space exhausted") || e.hasFlag("evacuation failure"))
                    .findFirst().orElse(null);
            out.add(new Finding("evacuation-failure", "critical", "Evacuation failure / to-space exhausted",
                    "G1 ran out of free regions while copying live objects; such pauses are very long and often "
                            + "followed by a full GC.",
                    List.of(evac + " pause(s) with to-space exhausted or evacuation failure"),
                    "Add heap (-Xmx), keep a larger reserve (-XX:G1ReservePercent=20) and start marking earlier "
                            + "(-XX:InitiatingHeapOccupancyPercent).",
                    List.of("-Xmx", "-XX:G1ReservePercent", "-XX:InitiatingHeapOccupancyPercent"), gcFile,
                    first == null ? null : first.x));
        }

        // CMS specifics
        if (s.concurrentModeFailures() > 0) {
            GcEvent first = ev.stream().filter(e -> e.hasFlag("concurrent mode failure")).findFirst().orElse(null);
            out.add(new Finding("concurrent-mode-failure", "critical", "CMS concurrent mode failure",
                    "The old generation filled up before the concurrent CMS cycle finished, so the JVM stopped for a "
                            + "single-threaded full collection.",
                    List.of(s.concurrentModeFailures() + " concurrent mode failure(s)"),
                    "Start CMS earlier: lower -XX:CMSInitiatingOccupancyFraction (Cassandra ships 75) with "
                            + "-XX:+UseCMSInitiatingOccupancyOnly, add heap, or move to G1.",
                    List.of("-XX:CMSInitiatingOccupancyFraction", "-XX:+UseCMSInitiatingOccupancyOnly", "-Xmx"), gcFile,
                    first == null ? null : first.x));
        }
        if (s.promotionFailures() > 0) {
            GcEvent first = ev.stream().filter(e -> e.hasFlag("promotion failed")).findFirst().orElse(null);
            out.add(new Finding("promotion-failed", "critical", "Promotion failed",
                    "A young collection could not move surviving objects to the old generation (no free space or "
                            + "fragmentation) and fell back to a full collection.",
                    List.of(s.promotionFailures() + " promotion failure(s)"),
                    "Add heap or start CMS earlier (-XX:CMSInitiatingOccupancyFraction); fragmentation is solved by G1.",
                    List.of("-Xmx", "-XX:CMSInitiatingOccupancyFraction"), gcFile, first == null ? null : first.x));
        }

        // premature promotion: much of what is allocated survives into the old generation
        Rate a = s.allocation(), p = s.promotion();
        long youngCount = pauses.stream().filter(e -> GcEvent.YOUNG.equals(e.category) && e.oldBeforeK != null).count();
        if (a.totalMB() != null && p.totalMB() != null && a.totalMB() > 0 && youngCount >= 10
                && p.totalMB() / a.totalMB() >= 0.25) {
            double ratio = p.totalMB() / a.totalMB() * 100;
            out.add(new Finding("premature-promotion", "warning", "Premature promotion",
                    pct(ratio) + " of the allocated memory was promoted to the old generation. Short-lived objects "
                            + "that reach the old generation make old-gen collections more frequent.",
                    List.of("Promoted " + fmtMB(p.totalMB()) + " of " + fmtMB(a.totalMB()) + " allocated",
                            "Promotion rate " + fmtMBs(p.avgMBs()) + ", allocation rate " + fmtMBs(a.avgMBs())),
                    "G1".equals(collector)
                            ? "Let the young generation grow (do not set -Xmn; check -XX:G1NewSizePercent) and keep "
                            + "-XX:MaxTenuringThreshold at its default."
                            : "Give the young generation more room (-Xmn / HEAP_NEWSIZE) and survivors more age "
                            + "(-XX:MaxTenuringThreshold, -XX:SurvivorRatio; Cassandra's CMS defaults are 1 and 8).",
                    List.of("-Xmn", "-XX:MaxTenuringThreshold", "-XX:SurvivorRatio"), gcFile, null));
        }

        // metaspace-triggered (non-full) collections
        List<GcEvent> metaYoung = pauses.stream().filter(e -> !GcEvent.FULL.equals(e.category) && metaCause(e.cause)).toList();
        if (!metaYoung.isEmpty() && metaFulls.isEmpty()) {
            out.add(new Finding("metaspace-threshold", "info", "Collections triggered by metaspace growth",
                    "Metaspace grew past its current threshold, which forced a collection (usually during start-up).",
                    List.of(metaYoung.size() + " pause(s) with cause " + metaYoung.get(0).cause,
                            "Peak metaspace: " + fmtK(s.heap().peakMetaK())),
                    "Set -XX:MetaspaceSize (e.g. 128m-256m) above what the node uses after start-up to avoid these.",
                    List.of("-XX:MetaspaceSize"), gcFile, metaYoung.get(0).x));
        }

        // Shenandoah / ZGC
        if (s.degenerated() > 0) {
            out.add(new Finding("degenerated", "warning", "Shenandoah degenerated cycles",
                    "Shenandoah ran out of memory during a concurrent cycle and finished it stop-the-world.",
                    List.of(s.degenerated() + " degenerated cycle(s)", log.shenandoahCancels + " cycle(s) cancelled by allocation failure"),
                    "Add heap or start cycles earlier (-XX:ShenandoahMinFreeThreshold, -XX:ConcGCThreads).",
                    List.of("-Xmx", "-XX:ConcGCThreads"), gcFile, null));
        }
        if (s.stalls().count() > 0) {
            out.add(new Finding("allocation-stalls", s.stalls().maxMs() >= 100 ? "warning" : "info",
                    "Allocation stalls (ZGC)",
                    "Application threads waited for ZGC to free memory: the collector could not keep up with allocation.",
                    List.of(s.stalls().count() + " stall(s), " + fmtMs(s.stalls().totalMs()) + " in total, longest "
                            + fmtMs(s.stalls().maxMs())),
                    "Add heap (-Xmx; ZGC needs headroom) or more concurrent GC threads (-XX:ConcGCThreads).",
                    List.of("-Xmx", "-XX:ConcGCThreads", "-XX:SoftMaxHeapSize"), gcFile, null));
        }

        // safepoints
        if (s.safepoints().ttspMaxMs() >= 100) {
            out.add(new Finding("time-to-safepoint", "warning", "Slow time to safepoint",
                    "Threads took up to " + fmtMs(s.safepoints().ttspMaxMs()) + " to reach a safepoint; the pause "
                            + "that follows is extended by that long.",
                    List.of(s.safepoints().count() + " safepoints, " + fmtMs(s.safepoints().ttspTotalMs()) + " spent reaching them"),
                    "Usually page faults (swap, transparent huge pages, -XX:+AlwaysPreTouch missing) or long counted "
                            + "loops; disable swap and THP, and log with -Xlog:safepoint to see the operation.",
                    List.of("-XX:+AlwaysPreTouch", "-XX:+UseCountedLoopSafepoints"), gcFile, null));
        }

        // frequent young collections
        List<GcEvent> young = pauses.stream().filter(e -> GcEvent.YOUNG.equals(e.category)).toList();
        if (young.size() >= 20) {
            double interval = (young.get(young.size() - 1).x - young.get(0).x) / (young.size() - 1);
            if (interval < 1) {
                out.add(new Finding("frequent-young-gc", "info", "Very frequent young collections",
                        "Young collections run every " + fmtMs(interval * 1000) + " on average: the young "
                                + "generation is small for the allocation rate.",
                        List.of(young.size() + " young collections", "Allocation rate " + fmtMBs(a.avgMBs())),
                        "G1".equals(collector) ? "Give G1 more heap so it can size the young generation, or raise "
                                + "-XX:MaxGCPauseMillis." : "Increase the young generation (-Xmn / HEAP_NEWSIZE).",
                        List.of("-Xmn", "-XX:MaxGCPauseMillis"), gcFile, null));
            }
        }

        // configuration
        if (heapMax != null && heapMax > 0 && heapMax < 2L * 1024 * 1024) {
            out.add(new Finding("small-heap", "info", "Small heap: " + fmtK(heapMax),
                    "The heap is below the 2 GB minimum Cassandra needs for production work; even moderate load "
                            + "will cause frequent collections.",
                    List.of("Maximum heap " + fmtK(heapMax)),
                    "Size the heap with MAX_HEAP_SIZE / -Xmx: 8 GB is a common start (CMS: up to 8-12 GB with "
                            + "HEAP_NEWSIZE ≈ 100 MB per core; G1: 16-31 GB). Keep -Xms equal to -Xmx.",
                    List.of("-Xmx", "-Xms"), heapFile, null));
        }
        if ("CMS".equals(collector)) {
            out.add(new Finding("cms-deprecated", "info", "CMS collector is deprecated",
                    "CMS was deprecated in Java 9 and removed in Java 14; Cassandra 5.0 on Java 17 runs G1.",
                    List.of("Collector: CMS" + (java == null ? "" : " on Java " + java)),
                    "Plan the move to G1: -XX:+UseG1GC with -XX:MaxGCPauseMillis=300 (remove the CMS and -Xmn options) "
                            + "in " + gcOptionsFile(java == null || java < 11 ? 11 : java) + ".",
                    List.of("-XX:+UseG1GC", "-XX:MaxGCPauseMillis"), gcFile, null));
        }
        if (out.isEmpty()) {
            out.add(new Finding("healthy", "info", "No GC problem found",
                    "Pauses, GC time and occupancy are within the usual limits for this window.",
                    List.of(s.pauses().count() + " pauses, max " + fmtMs(s.pauses().maxMs()) + ", GC time " + pct(s.gcTimePct())),
                    "Nothing to change.", List.of(), gcFile, null));
        }
        Map<String, Integer> order = GcReport.severityOrder();
        out.sort(Comparator.comparingInt(fd -> order.getOrDefault(fd.severity(), 3)));
        return out;
    }

    private static boolean metaCause(String cause) {
        return cause != null && (cause.contains("Metadata GC Threshold") || cause.contains("Last ditch")
                || cause.contains("Metadata GC Clear Soft References"));
    }

    /** Where Cassandra sets GC options for this Java version. */
    static String gcOptionsFile(Integer java) {
        if (java == null) return "conf/jvm11-server.options or conf/jvm17-server.options";
        if (java <= 8) return "conf/jvm.options (Cassandra 3.11) or conf/jvm8-server.options (4.x)";
        if (java <= 11) return "conf/jvm11-server.options";
        return "conf/jvm17-server.options";
    }

    static String heapOptionsFile(Integer java) {
        if (java != null && java <= 8) return "conf/jvm.options or MAX_HEAP_SIZE in conf/cassandra-env.sh";
        return "conf/jvm-server.options or MAX_HEAP_SIZE in conf/cassandra-env.sh";
    }

    // ---- statistics --------------------------------------------------------------------

    static Stats stats(double[] sorted) {
        if (sorted.length == 0) return new Stats(0, 0, 0, 0, 0, 0, 0, 0);
        double total = 0;
        for (double v : sorted) total += v;
        return new Stats(sorted.length, round(total), round(total / sorted.length), round(sorted[0]),
                round(sorted[sorted.length - 1]), round(percentile(sorted, 50)), round(percentile(sorted, 95)),
                round(percentile(sorted, 99)));
    }

    /** Nearest-rank percentile of sorted values. */
    static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) return 0;
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    private static List<Bucket> histogram(double[] d) {
        List<Bucket> out = new ArrayList<>();
        for (int i = 0; i < HISTOGRAM.length; i++) {
            double lo = HISTOGRAM[i];
            Double hi = i + 1 < HISTOGRAM.length ? HISTOGRAM[i + 1] : null;
            long n = Arrays.stream(d).filter(v -> v >= lo && (hi == null || v < hi)).count();
            String label = hi == null ? "≥ " + fmtMs(lo) : fmtMs(lo) + "–" + fmtMs(hi);
            out.add(new Bucket(label, lo, hi, n));
        }
        return out;
    }

    private static List<TypeStats> byType(List<GcEvent> events) {
        Map<String, double[]> m = new LinkedHashMap<>();
        Map<String, GcEvent> sample = new LinkedHashMap<>();
        for (GcEvent e : events) {
            double[] v = m.computeIfAbsent(e.type, k -> new double[3]);
            v[0]++;
            v[1] += e.durationMs;
            v[2] = Math.max(v[2], e.durationMs);
            sample.putIfAbsent(e.type, e);
        }
        List<TypeStats> out = new ArrayList<>();
        m.forEach((k, v) -> out.add(new TypeStats(k, sample.get(k).category, sample.get(k).kind, (long) v[0], round(v[1]),
                round(v[1] / v[0]), round(v[2]))));
        out.sort(Comparator.comparingDouble(TypeStats::totalMs).reversed());
        return out;
    }

    private static List<Count> causes(List<GcEvent> events) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (GcEvent e : events) m.merge(e.cause == null ? "(none)" : e.cause, 1L, Long::sum);
        List<Count> out = new ArrayList<>();
        m.forEach((k, v) -> out.add(new Count(k, v)));
        out.sort(Comparator.comparingLong(Count::count).reversed());
        return out;
    }

    private static long count(List<GcEvent> ev, String flag) {
        return ev.stream().filter(e -> e.hasFlag(flag)).count();
    }

    private static Long maxOf(List<GcEvent> ev, java.util.function.Function<GcEvent, Long> f) {
        Long m = null;
        for (GcEvent e : ev) {
            Long v = f.apply(e);
            if (v != null && (m == null || v > m)) m = v;
        }
        return m;
    }

    private static Long avgOf(List<GcEvent> ev, java.util.function.Function<GcEvent, Long> f) {
        long sum = 0, n = 0;
        for (GcEvent e : ev) {
            Long v = f.apply(e);
            if (v != null) {
                sum += v;
                n++;
            }
        }
        return n == 0 ? null : sum / n;
    }

    static double bucketSec(double duration) {
        for (double s : BUCKET_STEPS) if (duration / s <= MAX_BUCKETS) return s;
        return Math.ceil(duration / MAX_BUCKETS);
    }

    private static int idx(double x, double from, double bucket, int n) {
        return (int) Math.max(0, Math.min(n - 1, Math.floor((x - from) / bucket)));
    }

    /** Keeps every notable event and an even sample of the rest when there are too many. */
    private static List<GcEvent> sample(List<GcEvent> ev, double p99) {
        if (ev.size() <= MAX_EVENTS) return ev;
        List<GcEvent> keep = new ArrayList<>();
        int notable = 0;
        boolean[] mark = new boolean[ev.size()];
        for (int i = 0; i < ev.size(); i++) {
            GcEvent e = ev.get(i);
            if (e.isPause() && (e.durationMs >= p99 || e.flags != null || GcEvent.FULL.equals(e.category))) {
                mark[i] = true;
                notable++;
            }
        }
        int room = Math.max(0, MAX_EVENTS - notable);
        double stride = (double) (ev.size() - notable) / Math.max(1, room);
        double next = 0;
        int seen = 0;
        for (int i = 0; i < ev.size(); i++) {
            if (mark[i]) {
                keep.add(ev.get(i));
            } else {
                if (seen >= next && keep.size() < MAX_EVENTS) {
                    keep.add(ev.get(i));
                    next += stride;
                }
                seen++;
            }
        }
        return keep;
    }

    // ---- formatting --------------------------------------------------------------------

    static double round(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
        return Math.round(v * 1000) / 1000.0;
    }

    static String fmtMs(double ms) {
        if (ms >= 10_000) return String.format(Locale.ROOT, "%.1f s", ms / 1000);
        if (ms >= 1000) return String.format(Locale.ROOT, "%.2f s", ms / 1000);
        if (ms >= 10) return String.format(Locale.ROOT, "%.0f ms", ms);
        if (ms >= 1) return String.format(Locale.ROOT, "%.1f ms", ms);
        return String.format(Locale.ROOT, "%.2f ms", ms);
    }

    static String fmtDuration(double sec) {
        if (sec >= 2 * 3600) return String.format(Locale.ROOT, "%.1f h", sec / 3600);
        if (sec >= 120) return String.format(Locale.ROOT, "%.0f min", sec / 60);
        return String.format(Locale.ROOT, "%.0f s", sec);
    }

    static String fmtK(Long k) {
        if (k == null) return "n/a";
        if (k >= 1024L * 1024) return String.format(Locale.ROOT, "%.1f GB", k / 1024.0 / 1024);
        if (k >= 1024) return String.format(Locale.ROOT, "%.0f MB", k / 1024.0);
        return k + " KB";
    }

    private static String fmtMB(Double mb) {
        return mb == null ? "n/a" : mb >= 1024 ? String.format(Locale.ROOT, "%.1f GB", mb / 1024)
                : String.format(Locale.ROOT, "%.0f MB", mb);
    }

    private static String fmtMBs(Double mbs) {
        return mbs == null ? "n/a" : String.format(Locale.ROOT, "%.1f MB/s", mbs);
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f %%", v);
    }

    private static String describe(GcEvent e) {
        return e.type + (e.cause == null ? "" : ", " + e.cause);
    }
}
