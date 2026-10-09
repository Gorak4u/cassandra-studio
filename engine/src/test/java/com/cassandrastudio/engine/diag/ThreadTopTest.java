package com.cassandrastudio.engine.diag;

import static org.assertj.core.api.Assertions.assertThat;

import com.cassandrastudio.engine.diag.ThreadTop.Counters;
import com.cassandrastudio.engine.diag.ThreadTop.Sample;
import com.cassandrastudio.engine.diag.ThreadTop.TopView;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ThreadTopTest {
    @Test
    void poolNames() {
        assertThat(ThreadTop.poolName("ReadStage-12")).isEqualTo("ReadStage");
        assertThat(ThreadTop.poolName("CompactionExecutor:3")).isEqualTo("CompactionExecutor");
        assertThat(ThreadTop.poolName("nioEventLoopGroup-2-3")).isEqualTo("nioEventLoopGroup");
        assertThat(ThreadTop.poolName("Native-Transport-Requests-5")).isEqualTo("Native-Transport-Requests");
        assertThat(ThreadTop.poolName("GC Thread#3")).isEqualTo("GC Thread#");
        assertThat(ThreadTop.poolName("G1 Conc#0")).isEqualTo("G1 Conc#");
        assertThat(ThreadTop.poolName("RMI TCP Connection(463)-10.0.0.1")).isEqualTo("RMI TCP Connection");
        assertThat(ThreadTop.poolName("main")).isEqualTo("main");
        assertThat(ThreadTop.poolName("1234")).isEqualTo("1234");
    }

    private static Sample sample(long atMs, long procCpuNs, Object... rows) {
        Map<Long, Counters> m = new LinkedHashMap<>();
        for (int i = 0; i < rows.length; i += 4) {
            m.put((Long) rows[i], new Counters((String) rows[i + 1], "RUNNABLE", (Long) rows[i + 2], (Long) rows[i + 2] / 2,
                    (Long) rows[i + 3]));
        }
        return new Sample(atMs * 1_000_000, atMs, m, procCpuNs, 4, true, true);
    }

    @Test
    void deltasPerThreadAndGrouped() {
        Sample a = sample(0, 0, 1L, "ReadStage-1", 100_000_000L, 1000L, 2L, "ReadStage-2", 0L, 0L, 3L, "main", 5L, 0L);
        // 2 s later: thread 1 used 1 s CPU (50 % of a core), thread 2 0.5 s, thread 4 is new
        Sample b = sample(2000, 2_000_000_000L, 1L, "ReadStage-1", 1_100_000_000L, 2_001_000L, 2L, "ReadStage-2",
                500_000_000L, 0L, 3L, "main", 5L, 0L, 4L, "Native-Transport-Requests-1", 200_000_000L, 0L);

        TopView v = ThreadTop.view("n", a, b, 10, false);
        assertThat(v.firstSample()).isFalse();
        assertThat(v.intervalMs()).isEqualTo(2000);
        assertThat(v.rows().get(0).name()).isEqualTo("ReadStage-1");
        assertThat(v.rows().get(0).id()).isEqualTo(1L);
        assertThat(v.rows().get(0).cpuPct()).isEqualTo(50.0);
        assertThat(v.rows().get(0).userPct()).isEqualTo(25.0);
        assertThat(v.rows().get(0).allocBytesPerSec()).isEqualTo(1_000_000.0);
        assertThat(v.rows().get(1).cpuPct()).isEqualTo(25.0);
        assertThat(v.threadsCpuPct()).isEqualTo(85.0);
        // process: 2 s CPU over 2 s on 4 cores = 25 %
        assertThat(v.processCpuPct()).isEqualTo(25.0);

        TopView g = ThreadTop.view("n", a, b, 2, true);
        assertThat(g.rows()).hasSize(2);
        assertThat(g.rows().get(0).name()).isEqualTo("ReadStage");
        assertThat(g.rows().get(0).threads()).isEqualTo(2);
        assertThat(g.rows().get(0).cpuPct()).isEqualTo(75.0);
        assertThat(g.rows().get(0).id()).isNull();
    }

    @Test
    void firstSampleRanksByTotalCpu() {
        Sample a = sample(0, 0, 1L, "a", 5_000_000L, 0L, 2L, "b", 9_000_000L, 0L);
        TopView v = ThreadTop.view("n", null, a, 10, false);
        assertThat(v.firstSample()).isTrue();
        assertThat(v.processCpuPct()).isNull();
        assertThat(v.rows().get(0).name()).isEqualTo("b");
        assertThat(v.rows().get(0).cpuTotalMs()).isEqualTo(9);
    }

    @Test
    void samplesThisJvmOverJmx() {
        Sample s = ThreadTop.sample(ManagementFactory.getPlatformMBeanServer(), System.nanoTime(), System.currentTimeMillis());
        assertThat(s.bulk()).isTrue();
        assertThat(s.threads()).isNotEmpty();
        assertThat(s.processors()).isPositive();
        assertThat(s.threads().values()).anyMatch(c -> c.cpuNs() > 0);
    }
}
