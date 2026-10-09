package com.cassandrastudio.engine.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HistoryTest {
    private static final long H = History.RAW_MS;

    @Test
    void windowQueriesPerNodeAndAllNodes() {
        History h = new History();
        for (int i = 0; i < 10; i++) {
            h.add("heap.used", "a", i * 10_000L, (double) i);
            h.add("heap.used", "b", i * 10_000L, 100.0 + i);
        }
        h.add("heap.used", "a", 200_000L, null); // nulls are skipped
        Map<String, List<double[]>> all = h.query("heap.used", null, 20_000, 50_000);
        assertThat(all).containsOnlyKeys("a", "b");
        assertThat(all.get("a")).extracting(p -> p[1]).containsExactly(2.0, 3.0, 4.0, 5.0);
        assertThat(h.query("heap.used", "b", 0, 0).get("b")).singleElement().satisfies(p -> assertThat(p[1]).isEqualTo(100.0));
        assertThat(h.query("heap.used", "zzz", 0, 1_000_000)).containsEntry("zzz", List.of());
        assertThat(h.query("cpu.process_pct", null, 0, 1_000_000)).isEmpty();
    }

    @Test
    void downsamplesToMinutesAfterOneHourAndKeeps24Hours() {
        History h = new History();
        long t0 = 1_700_000_040_000L - Math.floorMod(1_700_000_040_000L, 60_000L); // a minute boundary
        // 26 hours at one point per 10 s: value = minute index, so each minute's mean is known
        long end = t0 + 26 * H;
        for (long t = t0; t <= end; t += 10_000) h.add("m", "n", t, (double) ((t - t0) / 60_000));
        List<double[]> pts = h.query("m", "n", 0, Long.MAX_VALUE).get("n");
        // last hour raw: 361 points; older: one per minute back to 24 h
        long rawCount = pts.stream().filter(p -> p[0] >= end - H).count();
        assertThat(rawCount).isEqualTo(361);
        List<double[]> minutes = pts.stream().filter(p -> p[0] < end - H).toList();
        assertThat(minutes).allSatisfy(p -> assertThat((long) p[0] % 60_000).isZero());
        assertThat(minutes.get(0)[0]).isGreaterThanOrEqualTo(end - History.RETENTION_MS);
        assertThat(minutes.size()).isBetween(23 * 60 - 1, 23 * 60 + 1);
        double[] some = minutes.get(100);
        assertThat(some[1]).isEqualTo((some[0] - t0) / 60_000); // mean of the minute
        // ordered, no overlap between minute and raw parts
        for (int i = 1; i < pts.size(); i++) assertThat(pts.get(i)[0]).isGreaterThan(pts.get(i - 1)[0]);
        // bounded memory: raw hour + 24 h of minutes (plus the open minute)
        assertThat(h.pointCount()).isLessThanOrEqualTo(361 + 24 * 60 + 1);
    }

    @Test
    void retainNodesDropsDepartedNodes() {
        History h = new History();
        h.add("m", "a", 1, 1.0);
        h.add("m", "b", 1, 1.0);
        h.retainNodes(Set.of("a"));
        assertThat(h.query("m", null, 0, 10)).containsOnlyKeys("a");
    }
}
