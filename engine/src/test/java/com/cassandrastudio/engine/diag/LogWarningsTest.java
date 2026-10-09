package com.cassandrastudio.engine.diag;

import static org.assertj.core.api.Assertions.assertThat;

import com.cassandrastudio.engine.diag.LogWarnings.Kind;
import com.cassandrastudio.engine.diag.LogWarnings.Warning;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogWarningsTest {
    private static String fixture() throws Exception {
        try (InputStream in = LogWarningsTest.class.getResourceAsStream("system-log-warnings.txt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesEveryVersionsWording() throws Exception {
        List<Warning> w = LogWarnings.parse("10.0.0.1", fixture());
        assertThat(w).hasSize(7);

        // 4.1, 3.11 and 5.0 (ISO "T" timestamp) tombstone warnings
        for (Warning t : w.subList(0, 3)) {
            assertThat(t.kind()).isEqualTo(Kind.TOMBSTONE_WARN);
            assertThat(t.keyspace()).isEqualTo("t2_diag");
            assertThat(t.table()).isEqualTo("tomb");
            assertThat(t.tombstones()).isEqualTo(1200);
            assertThat(t.liveRows()).isEqualTo(1200);
            assertThat(t.detail()).startsWith("SELECT * FROM t2_diag.tomb WHERE pk = 7");
            assertThat(t.level()).isEqualTo("WARN");
        }
        assertThat(w.get(2).time()).isEqualTo("2026-10-09T09:01:31,839");

        Warning write = w.get(3);
        assertThat(write.kind()).isEqualTo(Kind.LARGE_PARTITION_WRITE);
        assertThat(write.keyspace()).isEqualTo("shop");
        assertThat(write.table()).isEqualTo("orders");
        assertThat(write.partitionKey()).isEqualTo("customer-17");
        assertThat(write.sizeBytes()).isEqualTo(157_286_400L);
        assertThat(w.get(4).kind()).isEqualTo(Kind.LARGE_PARTITION_COMPACT);

        Warning pretty = w.get(5);
        assertThat(pretty.table()).isEqualTo("events_e2e");
        assertThat(pretty.partitionKey()).isEqualTo("2026-10-08:eu"); // keys may contain ':'
        assertThat(pretty.sizeBytes()).isEqualTo(Math.round(110.123 * 1024 * 1024));

        Warning abort = w.get(6);
        assertThat(abort.kind()).isEqualTo(Kind.TOMBSTONE_ABORT);
        assertThat(abort.level()).isEqualTo("ERROR");
        assertThat(abort.tombstones()).isEqualTo(100_001);
        assertThat(abort.table()).isEqualTo("inbox");
    }

    @Test
    void sortsNewestFirstAndSizes() {
        List<Warning> w = LogWarnings.sorted(LogWarnings.parse("n",
                "WARN  [a] 2026-01-01 00:00:00,000 X.java:1 - Writing large partition k/t:1 (2 bytes)\n"
                        + "WARN  [a] 2026-02-01 00:00:00,000 X.java:1 - Writing large partition k/t:2 (1.5GiB)\n"));
        assertThat(w.get(0).partitionKey()).isEqualTo("2");
        assertThat(LogWarnings.sizeBytes("1.5GiB")).isEqualTo(1_610_612_736L);
        assertThat(LogWarnings.sizeBytes("512KiB")).isEqualTo(524_288L);
        assertThat(LogWarnings.sizeBytes("12 bytes")).isEqualTo(12L);
    }

    @Test
    void commandQuotesThePath() {
        String cmd = LogWarnings.command("/var/log/it's/system.log", 100);
        assertThat(cmd).contains("'/var/log/it'\\''s/system.log'").contains("tail -n 100").contains(LogWarnings.NO_LOG);
        assertThat(LogWarnings.scanCommand("/usr/local/bin/tombstone-scan.sh", "shop", "orders"))
                .contains("'/usr/local/bin/tombstone-scan.sh' -k 'shop' -t 'orders' 2>&1");
    }

    @Test
    void parsesTheEstateTombstoneScan() {
        String out = "\u001B[0;34m--- Scanning Cluster-Wide Tombstone Statistics ---\u001B[0m\n"
                + "KEYSPACE  TABLE   AVG_LIVE  AVG_TOMBS  MAX_TOMBS\n"
                + "shop      inbox   12.0      840.5      4021.0\n"
                + "shop      orders  3.0       0.0        0.0\n"
                + "\n\u001B[1;33mReport sorted by \u001B[1mAVG_TOMBS\u001B[0m (Average tombstones per slice).\u001B[0m\n";
        LogWarnings.ScanResult r = LogWarnings.parseScan("n", "cmd", out);
        assertThat(r.available()).isTrue();
        assertThat(r.header()).containsExactly("KEYSPACE", "TABLE", "AVG_LIVE", "AVG_TOMBS", "MAX_TOMBS");
        assertThat(r.rows()).hasSize(2);
        assertThat(r.rows().get(0).cells()).containsExactly("shop", "inbox", "12.0", "840.5", "4021.0");
        assertThat(r.output()).doesNotContain("\u001B");

        assertThat(LogWarnings.parseScan("n", "cmd", LogWarnings.NO_SCRIPT + "\n").available()).isFalse();
    }
}
