package com.cassandrastudio.engine.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cassandrastudio.engine.util.ApiException;
import java.util.List;
import java.util.Map;
import javax.management.Notification;
import org.junit.jupiter.api.Test;

/** Parsing, formatting and command building of the operations feature (no JMX). */
class OpsParsingTest {

    @Test
    void histogramOffsetsMatchCassandra() {
        assertThat(Histograms.offsets(12)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 17);
    }

    @Test
    void histogramPercentiles() {
        long[] buckets = new long[11]; // 10 offsets + overflow
        buckets[2] = 50; // value 3
        buckets[8] = 50; // value 10
        assertThat(Histograms.percentile(buckets, 0.5)).isEqualTo(3);
        assertThat(Histograms.percentile(buckets, 0.75)).isEqualTo(10);
        assertThat(Histograms.min(buckets)).isEqualTo(3); // offsets[1] + 1
        assertThat(Histograms.max(buckets)).isEqualTo(10);
        assertThat(Histograms.percentile(new long[11], 0.5)).isNull();
        buckets[10] = 1;
        assertThat(Histograms.percentile(buckets, 0.5)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void parsesGossipOf4xAnd311() {
        String v4 = "/10.0.0.3:7000\n  generation:1791534079\n  heartbeat:1918\n  LOAD:1865:245273.0\n"
                + "  STATUS_WITH_PORT:28:NORMAL,-1782728325173106111\n  TOKENS:27:<hidden>\n/10.0.0.2:7000\n  generation:1\n";
        List<Gossip.Endpoint> eps = Gossip.parse(v4);
        assertThat(eps).hasSize(2);
        assertThat(eps.get(0).endpoint()).isEqualTo("10.0.0.3:7000");
        assertThat(eps.get(0).states()).contains(new Gossip.State("generation", null, "1791534079"),
                new Gossip.State("LOAD", "1865", "245273.0"),
                new Gossip.State("STATUS_WITH_PORT", "28", "NORMAL,-1782728325173106111"));
        List<Gossip.Endpoint> old = Gossip.parse("/127.0.0.1\n  generation:5\n  STATUS:NORMAL,-1\n");
        assertThat(old.get(0).states()).contains(new Gossip.State("STATUS", null, "NORMAL,-1"));
    }

    @Test
    void formatsAndParsesSizes() {
        assertThat(Fmt.bytes(512L)).isEqualTo("512 bytes");
        assertThat(Fmt.bytes(1536L)).isEqualTo("1.50 KiB");
        assertThat(Fmt.parseSize("6.09 KiB")).isEqualTo(6236L);
        assertThat(Fmt.parseSize("1024")).isEqualTo(1024L);
        assertThat(Fmt.parseSize("n/a")).isNull();
        assertThat(Beans.bareAddress("/10.0.0.1:7000")).isEqualTo("10.0.0.1");
        assertThat(Beans.bareAddress("host/10.0.0.1")).isEqualTo("10.0.0.1");
    }

    private static Maintenance.Request req(Maintenance.Kind k, String ks, List<String> tables) {
        return new Maintenance.Request(k, List.of("10.0.0.1"), ks, tables, false, 2, false, false, false, false, false, null,
                List.of(), false);
    }

    @Test
    void maintenancePreviewsAreNodetoolCommands() {
        assertThat(Maintenance.preview(req(Maintenance.Kind.FLUSH, "shop", List.of("orders")), "10.0.0.1"))
                .isEqualTo("nodetool -h 10.0.0.1 flush -- shop orders");
        assertThat(Maintenance.preview(req(Maintenance.Kind.CLEANUP, "shop", List.of()), "10.0.0.1"))
                .isEqualTo("nodetool -h 10.0.0.1 cleanup -j 2 -- shop");
        assertThat(Maintenance.preview(req(Maintenance.Kind.GARBAGECOLLECT, "shop", List.of()), "h"))
                .isEqualTo("nodetool -h h garbagecollect -g ROW -j 2 -- shop");
        Maintenance.Request scrub = new Maintenance.Request(Maintenance.Kind.SCRUB, List.of(), "shop", List.of("t"), false, 0,
                true, true, false, false, false, null, List.of(), false);
        assertThat(Maintenance.preview(scrub, "h")).isEqualTo("nodetool -h h scrub -ns -s -- shop t");
        assertThat(Maintenance.destructive(scrub)).isTrue();
        assertThat(Maintenance.warnings(scrub, 1)).anyMatch(w -> w.contains("DROPPED"));
        assertThat(Maintenance.warnings(req(Maintenance.Kind.CLEANUP, "shop", List.of()), 3))
                .anyMatch(w -> w.contains("rewrites all SSTables")).anyMatch(w -> w.contains("3 nodes"));
        assertThat(Maintenance.warnings(req(Maintenance.Kind.COMPACT, "shop", List.of()), 1))
                .anyMatch(w -> w.contains("one large SSTable"));
        Maintenance.Request user = new Maintenance.Request(Maintenance.Kind.USER_COMPACT, List.of(), null, List.of(), false, 0,
                false, false, false, false, false, null, List.of("/var/lib/cassandra/data/ks/t/nb-1-big-Data.db"), false);
        Maintenance.validate(user);
        assertThat(Maintenance.preview(user, "h"))
                .isEqualTo("nodetool -h h compact --user-defined '/var/lib/cassandra/data/ks/t/nb-1-big-Data.db'");
    }

    @Test
    void maintenanceValidation() {
        assertThatThrownBy(() -> Maintenance.validate(req(Maintenance.Kind.FLUSH, null, List.of())))
                .isInstanceOf(ApiException.class).hasMessageContaining("keyspace is required");
        assertThatThrownBy(() -> Maintenance.validate(req(Maintenance.Kind.FLUSH, "shop; drop", List.of())))
                .hasMessageContaining("Invalid keyspace");
        assertThatThrownBy(() -> Maintenance.Kind.of("decommission")).hasMessageContaining("Unknown operation");
        assertThatThrownBy(() -> Maintenance.checkStatus(Maintenance.Kind.CLEANUP, 1)).isInstanceOf(OpsException.class)
                .hasMessageContaining("aborted");
        assertThat(Maintenance.checkStatus(Maintenance.Kind.CLEANUP, 0)).isEqualTo("done");
    }

    private static Repair.Request repair(boolean full, boolean pr, List<Repair.Range> ranges) {
        return new Repair.Request(List.of("h"), "shop", List.of("orders"), full, pr, List.of("dc1"),
                Repair.Parallelism.DC_PARALLEL, ranges, 2, false);
    }

    @Test
    void repairOptionsAndPreview() {
        Repair.Request r = repair(true, true, List.of());
        assertThat(Repair.preview(r, "10.0.0.1"))
                .containsExactly("nodetool -h 10.0.0.1 repair -full -pr -dcpar -dc dc1 -j 2 -- shop orders");
        assertThat(Repair.options(r, null)).containsEntry("parallelism", "dc_parallel").containsEntry("primaryRange", "true")
                .containsEntry("incremental", "false").containsEntry("columnFamilies", "orders")
                .containsEntry("dataCenters", "dc1").containsEntry("jobThreads", "2").doesNotContainKey("ranges");
        Repair.Request sub = repair(false, false, List.of(new Repair.Range("-100", "200"), new Repair.Range("300", "400")));
        assertThat(Repair.preview(sub, "h")).hasSize(2).first().asString().contains("-st -100 -et 200").doesNotContain("-full");
        assertThat(Repair.options(sub, sub.ranges().get(0))).containsEntry("ranges", "-100:200").containsEntry("incremental", "true");
        assertThat(Repair.warnings(sub, 1, true)).anyMatch(w -> w.contains("3.x"));
        assertThatThrownBy(() -> Repair.validate(repair(true, true, List.of(new Repair.Range("1", "2")))))
                .hasMessageContaining("cannot be combined");
        assertThatThrownBy(() -> Repair.validate(repair(true, false, List.of(new Repair.Range("a", "2")))))
                .hasMessageContaining("integers");
    }

    @Test
    void repairTrackerFollowsOneCommand() {
        Repair.Tracker t = new Repair.Tracker(7);
        assertThat(t.apply(progress("repair:8", 1, 1, 2, "other"))).isNull();
        assertThat(t.apply(progress("repair:7", Repair.PROGRESS, 1, 4, "session 1 done"))).isEqualTo("session 1 done");
        assertThat(t.fraction).isEqualTo(0.25);
        t.apply(progress("repair:7", Repair.ERROR, 2, 4, "boom"));
        t.apply(progress("repair:7", Repair.COMPLETE, 4, 4, "finished"));
        assertThat(t.failed).isTrue();
        assertThat(t.lastError).isEqualTo("boom");
        assertThat(t.complete).isTrue();
    }

    static Notification progress(String source, int type, int count, int total, String msg) {
        Notification n = new Notification("progress", source, 1, msg);
        n.setUserData(Map.of("type", type, "progressCount", count, "total", total));
        return n;
    }

    @Test
    void snapshotPreviewAndValidation() {
        Snapshots.Create c = new Snapshots.Create(List.of(), "pre-upgrade", List.of(), List.of("shop.orders", "shop.items"), true);
        Snapshots.validate(c);
        assertThat(Snapshots.preview(c, "h")).isEqualTo("nodetool -h h snapshot -t pre-upgrade -sf -kt shop.orders,shop.items");
        assertThat(Snapshots.preview(new Snapshots.Clear(List.of(), null, List.of("shop"), true), "h"))
                .isEqualTo("nodetool -h h clearsnapshot --all -- shop");
        assertThatThrownBy(() -> Snapshots.validate(new Snapshots.Create(List.of(), "../x", List.of(), List.of(), false)))
                .hasMessageContaining("tag");
        assertThatThrownBy(() -> Snapshots.validate(new Snapshots.Clear(List.of(), null, List.of(), false)))
                .hasMessageContaining("tag is required");
        assertThatThrownBy(() -> Snapshots.validate(new Snapshots.Create(List.of(), "t", List.of(), List.of("shop"), false)))
                .hasMessageContaining("keyspace.table");
    }
}
