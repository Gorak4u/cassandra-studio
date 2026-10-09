package com.cassandrastudio.engine.gclog;

import static org.assertj.core.api.Assertions.assertThat;

import com.cassandrastudio.engine.gclog.GcReport.Finding;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every collector and log format against real fixtures (test-env nodes and the local JDK 21). */
class GcLogParserTest {

    static GcLog parse(String fixture) throws IOException {
        GcLogParser p = new GcLogParser();
        try (InputStream in = GcLogParserTest.class.getResourceAsStream("/gclog/" + fixture)) {
            assertThat(in).as(fixture).isNotNull();
            p.feed(in);
        }
        return p.finish();
    }

    static GcReport report(GcLog log) {
        return GcAnalyzer.analyze(log, "a1", "test", new GcReport.Source("upload", null, List.of()), 0, null, null);
    }

    static List<String> findingIds(GcReport r) {
        return r.findings().stream().map(Finding::id).toList();
    }

    static GcEvent firstPause(GcLog log, String type) {
        return log.events.stream().filter(e -> e.isPause() && type.equals(e.type)).findFirst().orElseThrow();
    }

    @Test
    void java8CmsFromCassandra311() throws IOException {
        GcLog log = parse("java8-cms-cassandra311.log");
        assertThat(log.format).isEqualTo("java8");
        assertThat(log.collector).isEqualTo("CMS");
        assertThat(log.javaMajor()).isEqualTo(8);
        assertThat(log.timeAxis).isEqualTo("wall");
        assertThat(log.heapMaxK).isEqualTo(512 * 1024L);
        GcEvent young = firstPause(log, "Young");
        // ParNew line split by PrintTenuringDistribution, PrintHeapAtGC blocks around it
        assertThat(young.cause).isEqualTo("Allocation Failure");
        assertThat(young.durationMs).isEqualTo(27.177);
        assertThat(young.heapBeforeK).isEqualTo(81920L);
        assertThat(young.heapAfterK).isEqualTo(12765L);
        assertThat(young.heapTotalK).isEqualTo(514048L);
        assertThat(young.youngAfterK).isEqualTo(10239L);
        assertThat(young.oldAfterK).isEqualTo(2526L);
        assertThat(young.oldTotalK).isEqualTo(514048L - 92160L);
        assertThat(young.metaBeforeK).isEqualTo(20556L);
        assertThat(young.metaAfterK).isEqualTo(20556L);
        assertThat(firstPause(log, "Initial Mark").heapAfterK).isEqualTo(29110L);
        assertThat(log.events).anyMatch(e -> "Concurrent Mark".equals(e.type) && e.durationMs == 22.0);
        assertThat(log.events).anyMatch(e -> "Remark".equals(e.type));
        assertThat(log.safepoints).isGreaterThan(100);

        GcReport r = report(log);
        assertThat(r.summary().pauses().count()).isEqualTo(11);
        assertThat(r.summary().fullGcCount()).isZero();
        assertThat(findingIds(r)).contains("small-heap", "cms-deprecated");
    }

    @Test
    void java11CmsUnifiedFromCassandra41() throws IOException {
        GcLog log = parse("java11-cms-cassandra41.log");
        assertThat(log.format).isEqualTo("unified");
        assertThat(log.collector).isEqualTo("CMS");
        GcEvent young = firstPause(log, "Young");
        assertThat(young.durationMs).isEqualTo(14.706);
        assertThat(young.youngBeforeK).isEqualTo(81920L);
        assertThat(young.youngAfterK).isEqualTo(7829L);
        assertThat(young.oldTotalK).isEqualTo(421888L);
        assertThat(young.heapBeforeK).isEqualTo(80 * 1024L);
        // heap*=trace "Heap after GC" block follows the summary line on Java 11
        assertThat(young.metaAfterK).isNotNull();
        assertThat(log.events).anyMatch(e -> "Initial Mark".equals(e.type));
        assertThat(log.events).anyMatch(e -> "Concurrent Mark".equals(e.type) && !e.isPause());
        assertThat(log.safepointReasons).containsKey("Deoptimize");
        GcReport r = report(log);
        assertThat(findingIds(r)).contains("cms-deprecated", "small-heap");
        assertThat(r.findings().stream().filter(f -> f.id().equals("cms-deprecated")).findFirst().orElseThrow().file())
                .isEqualTo("conf/jvm11-server.options");
    }

    @Test
    void java17G1FromCassandra50() throws IOException {
        GcLog log = parse("java17-g1-cassandra50.log");
        assertThat(log.collector).isEqualTo("G1");
        assertThat(log.regionSizeK).isEqualTo(16384L);
        GcEvent first = log.events.stream().filter(GcEvent::isPause).findFirst().orElseThrow();
        assertThat(first.type).isEqualTo("Young (Concurrent Start)");
        assertThat(first.cause).isEqualTo("Metadata GC Threshold");
        assertThat(first.durationMs).isEqualTo(131.543);
        assertThat(first.heapBeforeK).isEqualTo(142 * 1024L);
        assertThat(first.youngBeforeK).isEqualTo(8 * 16384L);
        // the heap trace block comes before the summary line on Java 17
        assertThat(first.metaBeforeK).isEqualTo(21104L);
        assertThat(first.metaAfterK).isEqualTo(21104L);
        assertThat(log.events).anyMatch(e -> "Concurrent Mark Cycle".equals(e.type));
        assertThat(log.safepoints).isPositive();

        GcReport r = report(log);
        assertThat(r.summary().byType()).extracting(GcReport.TypeStats::type).contains("Remark", "Cleanup", "Young");
        assertThat(findingIds(r)).contains("metaspace-threshold", "small-heap");
        assertThat(r.findings()).allSatisfy(f -> assertThat(f.hint()).isNotBlank());
    }

    @Test
    void java21G1WithHumongousAndEvacuationFailures() throws IOException {
        GcLog log = parse("java21-g1.log");
        assertThat(log.collector).isEqualTo("G1");
        assertThat(log.javaMajor()).isEqualTo(21);
        assertThat(log.regionSizeK).isEqualTo(1024L);
        GcEvent y = firstPause(log, "Young");
        assertThat(y.metaAfterK).isEqualTo(74L);
        assertThat(y.oldBeforeK).isEqualTo(2048L);
        assertThat(log.events).anyMatch(e -> e.hasFlag("humongous"));
        GcReport r = report(log);
        assertThat(r.summary().humongousAllocations()).isPositive();
        assertThat(findingIds(r)).contains("humongous");
    }

    @Test
    void zgcBothModesAndShenandoahAndParallel() throws IOException {
        GcLog z = parse("java21-zgc.log");
        assertThat(z.collector).isEqualTo("ZGC");
        assertThat(z.events).anyMatch(e -> "ZGC Cycle".equals(e.type) && e.heapBeforeK != null && e.durationMs > 0);
        assertThat(z.events).anyMatch(e -> "Mark Start".equals(e.type) && e.isPause());
        assertThat(z.allocationStalls).isPositive();
        assertThat(findingIds(report(z))).contains("allocation-stalls");

        GcLog zg = parse("java21-zgc-generational.log");
        assertThat(zg.collector).isEqualTo("ZGC (generational)");
        assertThat(zg.events).anyMatch(e -> "ZGC Minor".equals(e.type));
        assertThat(zg.events).anyMatch(e -> "ZGC Major".equals(e.type) && e.durationMs == 41.0);
        assertThat(zg.events).anyMatch(e -> "Young Mark Start".equals(e.type));

        GcLog sh = parse("java21-shenandoah.log");
        assertThat(sh.collector).isEqualTo("Shenandoah");
        assertThat(sh.events).anyMatch(e -> "Init Mark".equals(e.type));
        assertThat(sh.events).anyMatch(e -> "Concurrent marking".equals(e.type));

        GcLog par = parse("java21-parallel.log");
        assertThat(par.collector).isEqualTo("Parallel");
        GcEvent y = firstPause(par, "Young");
        assertThat(y.youngBeforeK).isEqualTo(16384L);
        assertThat(y.youngTotalK).isEqualTo(18944L);
        assertThat(y.oldAfterK).isEqualTo(1108L);
        assertThat(par.events).anyMatch(e -> "Full".equals(e.type) && "Ergonomics".equals(e.cause));
    }

    @Test
    void java8CmsFailuresGiveFindings() throws IOException {
        GcLog log = parse("java8-cms-failures.log");
        assertThat(log.heapMaxK).isEqualTo(8L * 1024 * 1024);
        assertThat(log.concurrentModeFailures).isEqualTo(1);
        assertThat(log.promotionFailures).isEqualTo(1);
        GcEvent cmf = log.events.stream().filter(e -> e.hasFlag("concurrent mode failure")).findFirst().orElseThrow();
        assertThat(cmf.category).isEqualTo(GcEvent.FULL);
        assertThat(cmf.durationMs).isEqualTo(6900.0);
        assertThat(cmf.oldAfterK).isEqualTo(5900000L);
        assertThat(cmf.metaAfterK).isEqualTo(45000L);
        // concurrent phases printed inside the failed collection are still events
        assertThat(log.events).filteredOn(e -> "Concurrent Mark".equals(e.type)).hasSize(2);
        assertThat(log.events).anyMatch(e -> "Concurrent Abortable Preclean".equals(e.type) && e.durationMs == 5100.0);

        GcReport r = report(log);
        assertThat(findingIds(r)).contains("long-pauses", "gc-time", "heap-too-small", "concurrent-mode-failure",
                "promotion-failed", "full-gc-metaspace", "full-gc-explicit", "time-to-safepoint");
        assertThat(r.findings().get(0).severity()).isEqualTo("critical");
        Finding meta = r.findings().stream().filter(f -> f.id().equals("full-gc-metaspace")).findFirst().orElseThrow();
        assertThat(meta.options()).contains("-XX:MetaspaceSize");
        assertThat(meta.file()).contains("jvm.options");
        assertThat(r.summary().fullGcCauses()).extracting(GcReport.Count::name)
                .contains("Metadata GC Threshold", "System.gc()", "Allocation Failure");
        assertThat(r.summary().pauses().maxMs()).isEqualTo(6900.0);
        assertThat(r.summary().histogram().get(r.summary().histogram().size() - 1).count()).isEqualTo(1); // >= 5 s
    }

    @Test
    void java8G1AndParallel() throws IOException {
        GcLog log = parse("java8-g1-parallel.log");
        assertThat(log.timeAxis).isEqualTo("uptime");
        GcEvent y = firstPause(log, "Young");
        assertThat(y.cause).isEqualTo("G1 Evacuation Pause");
        assertThat(y.heapBeforeK).isEqualTo(24 * 1024L);
        assertThat(y.heapAfterK).isEqualTo(4004L);
        assertThat(log.events).anyMatch(e -> e.hasFlag("humongous") && e.hasFlag("initial-mark"));
        assertThat(log.events).anyMatch(e -> "Mixed".equals(e.type) && e.hasFlag("to-space exhausted"));
        GcEvent full = firstPause(log, "Full");
        assertThat(full.heapAfterK).isEqualTo(200 * 1024L);
        assertThat(full.metaAfterK).isEqualTo(2984L);
        assertThat(firstPause(log, "Cleanup").heapAfterK).isEqualTo(100 * 1024L);
        assertThat(log.events).anyMatch(e -> "Concurrent Mark".equals(e.type) && e.durationMs == 100.0);
        assertThat(log.events).anyMatch(e -> "Ergonomics".equals(e.cause) && e.oldAfterK == 4900L);
        assertThat(findingIds(report(log))).contains("evacuation-failure", "full-gc", "long-pauses");
    }

    @Test
    void timeWindowRecomputesSummary() throws IOException {
        GcLog log = parse("java8-cms-failures.log");
        GcReport all = report(log);
        GcReport early = GcAnalyzer.analyze(log, "a1", "t", null, 0, 0.0, 4.5);
        assertThat(early.range().whole()).isFalse();
        assertThat(early.summary().pauses().count()).isEqualTo(2);
        assertThat(early.summary().fullGcCount()).isZero();
        assertThat(early.events()).hasSize(2);
        assertThat(all.summary().pauses().count()).isGreaterThan(early.summary().pauses().count());
    }

    @Test
    void unknownLinesAreSkipped() {
        GcLogParser p = new GcLogParser();
        p.feed("hello world");
        p.feed("[not a decoration at all");
        p.feed("[2024-01-01T00:00:00.000+0000][1.000s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 10M->2M(64M) 1.500ms");
        p.feed("[2024-01-01T00:00:01.000+0000][2.000s][info][gc] GC(1) Something new 1->2");
        GcLog log = p.finish();
        assertThat(log.events).hasSize(1);
        assertThat(log.events.get(0).heapAfterK).isEqualTo(2048L);
        assertThat(log.lines).isEqualTo(4);
    }

    @Test
    void decorationsAndDates() {
        assertThat(GcLogParser.parseDate("2026-10-09T08:28:37.697+0000")).isEqualTo(1791534517697L);
        assertThat(GcLogParser.parseDate("2026-10-09T10:28:37.697+02:00")).isEqualTo(1791534517697L);
        assertThat(GcLogParser.parseDate("nonsense")).isNull();
        GcLogParser p = new GcLogParser();
        p.feed("[123456ms][info][gc] GC(3) Pause Full (System.gc()) 100M->50M(512M) 300.000ms");
        GcLog log = p.finish();
        GcEvent e = log.events.get(0);
        assertThat(e.uptime).isEqualTo(123.456);
        assertThat(e.category).isEqualTo(GcEvent.FULL);
        assertThat(e.cause).isEqualTo("System.gc()");
        assertThat(log.timeAxis).isEqualTo("uptime");
    }

    @Test
    void nameParts() {
        var np = GcLogParser.nameParts("Young (Normal) (G1 Evacuation Pause) (Evacuation Failure: Allocation)");
        assertThat(np.base()).isEqualTo("Young");
        assertThat(np.groups()).containsExactly("Normal", "G1 Evacuation Pause", "Evacuation Failure: Allocation");
        assertThat(GcLogParser.nameParts("Full (System.gc())").groups()).containsExactly("System.gc()");
    }

    @Test
    void percentilesAndBuckets() {
        double[] d = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        assertThat(GcAnalyzer.percentile(d, 50)).isEqualTo(5);
        assertThat(GcAnalyzer.percentile(d, 99)).isEqualTo(10);
        assertThat(GcAnalyzer.bucketSec(100)).isEqualTo(1);
        assertThat(GcAnalyzer.bucketSec(3600)).isEqualTo(15);
        assertThat(GcAnalyzer.bucketSec(7 * 86400)).isEqualTo(3600);
    }
}
