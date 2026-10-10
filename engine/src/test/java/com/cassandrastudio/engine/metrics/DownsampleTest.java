package com.cassandrastudio.engine.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DownsampleTest {
    @Test
    void keepsEndsAndPeaksWithinTheLimit() {
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < 1_800; i++) pts.add(new double[] {i * 10_000.0, i == 900 ? 500.0 : 1.0});
        List<double[]> out = Downsample.lttb(pts, 300);
        assertThat(out).hasSize(300);
        assertThat(out.get(0)).isSameAs(pts.get(0));
        assertThat(out.get(299)).isSameAs(pts.get(1_799));
        assertThat(out).anySatisfy(p -> assertThat(p[1]).isEqualTo(500.0)); // the spike survives
        for (int i = 1; i < out.size(); i++) assertThat(out.get(i)[0]).isGreaterThan(out.get(i - 1)[0]);
    }

    @Test
    void shortSeriesAreUnchanged() {
        List<double[]> pts = List.of(new double[] {1, 1}, new double[] {2, 2});
        assertThat(Downsample.lttb(pts, 300)).isSameAs(pts);
    }
}
