package com.cassandrastudio.engine.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.cassandrastudio.engine.metrics.MonitoringModel.DataDir;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiskUsageTest {
    private static final List<DataDir> DIRS = List.of(new DataDir("/var/lib/cassandra/data", null, null),
            new DataDir("/data 2/it's", null, null));

    @Test
    void commandQuotesEveryPath() {
        assertThat(DiskUsage.command(DIRS)).isEqualTo("df -Pk -- '/var/lib/cassandra/data' '/data 2/it'\\''s'");
    }

    @Test
    void fillsTotalAndFreeInArgumentOrder() {
        String out = """
                Filesystem     1024-blocks      Used Available Capacity Mounted on
                /dev/sda1        102400000  81920000  20480000      80% /
                /dev/sdb1             1000       250       750      25% /data 2
                """;
        assertThat(DiskUsage.apply(DIRS, out)).containsExactly(
                new DataDir("/var/lib/cassandra/data", 102400000L * 1024, 20480000L * 1024),
                new DataDir("/data 2/it's", 1000L * 1024, 750L * 1024));
    }

    @Test
    void reservedBlocksAreNotCountedAsUsed() {
        // 45 % in df's Capacity column: 18 used, 22 available, 264 blocks in all (the rest reserved).
        String out = "Filesystem 1024-blocks Used Available Capacity Mounted on\n"
                + "/dev/vda 264212084 18065816 22777796 45% /var/lib/cassandra\n";
        DataDir d = DiskUsage.apply(DIRS.subList(0, 1), out).get(0);
        assertThat(d.totalBytes()).isEqualTo((18065816L + 22777796L) * 1024);
        assertThat(100.0 * (d.totalBytes() - d.freeBytes()) / d.totalBytes()).isCloseTo(44.2, within(0.1));
    }

    @Test
    void missingOrGarbledLinesLeaveNulls() {
        assertThat(DiskUsage.apply(DIRS, "Filesystem 1024-blocks Used Available Capacity Mounted on\n"))
                .isEqualTo(DIRS);
        assertThat(DiskUsage.apply(DIRS, "")).isEqualTo(DIRS);
    }
}
