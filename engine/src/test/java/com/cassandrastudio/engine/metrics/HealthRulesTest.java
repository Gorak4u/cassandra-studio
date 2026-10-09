package com.cassandrastudio.engine.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.cassandrastudio.engine.metrics.MonitoringModel.Alert;
import com.cassandrastudio.engine.metrics.MonitoringModel.ClientRequests;
import com.cassandrastudio.engine.metrics.MonitoringModel.DataDir;
import com.cassandrastudio.engine.metrics.MonitoringModel.Health;
import com.cassandrastudio.engine.metrics.MonitoringModel.Level;
import com.cassandrastudio.engine.metrics.MonitoringModel.NodeSnapshot;
import com.cassandrastudio.engine.metrics.MonitoringModel.ThreadPool;
import com.cassandrastudio.engine.util.ApiException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class HealthRulesTest {

    static NodeSnapshot node(String address, Consumer<NodeBuilder> c) {
        NodeBuilder b = new NodeBuilder();
        b.address = address;
        b.hostId = "h-" + address;
        b.datacenter = "dc1";
        b.state = "UN";
        c.accept(b);
        return b.build();
    }

    private static List<Alert> eval(HealthRules r, long now, NodeSnapshot... nodes) {
        return r.evaluate(List.of(nodes), true, null, now);
    }

    @Test
    void healthyClusterIsGreen() {
        List<Alert> alerts = eval(new HealthRules(), 1, node("a", b -> {}), node("b", b -> {}));
        assertThat(alerts).isEmpty();
        assertThat(HealthRules.health(alerts)).isEqualTo(new Health(Level.GREEN, List.of()));
    }

    @Test
    void nodeDownAndUnreachable() {
        List<Alert> alerts = eval(new HealthRules(), 1,
                node("a", b -> b.state = "DN"),
                node("b", b -> b.error = "Connection refused"),
                node("c", b -> b.state = "UJ"),
                node("d", b -> {
                    b.state = "?N";
                    b.error = "timeout";
                }));
        assertThat(alerts).extracting(Alert::id).containsExactly("node.down:a", "node.down:c", "node.unreachable:b",
                "node.unreachable:d");
        assertThat(alerts.get(0).level()).isEqualTo(Level.RED);
        assertThat(alerts.get(0).message()).isEqualTo("a is DN");
        Health h = HealthRules.health(alerts);
        assertThat(h.level()).isEqualTo(Level.RED);
        assertThat(h.reasons()).hasSize(4);
    }

    @Test
    void schemaDisagreement() {
        List<Alert> alerts = new HealthRules().evaluate(List.of(node("a", b -> {})), false, null, 1);
        assertThat(alerts).singleElement().satisfies(a -> {
            assertThat(a.id()).isEqualTo("schema.disagreement");
            assertThat(a.node()).isNull();
            assertThat(a.level()).isEqualTo(Level.YELLOW);
        });
    }

    @Test
    void heapAndGcLevels() {
        List<Alert> alerts = eval(new HealthRules(), 1,
                node("a", b -> { b.heapUsedBytes = 86L; b.heapMaxBytes = 100L; }),
                node("b", b -> { b.heapUsedBytes = 96L; b.heapMaxBytes = 100L; b.gcTimePct = 11.0; }),
                node("c", b -> { b.heapUsedBytes = 85L; b.heapMaxBytes = 100L; b.gcTimePct = 30.0; }));
        assertThat(alerts).extracting(Alert::id, Alert::level).containsExactlyInAnyOrder(
                tuple("heap.high:a", Level.YELLOW),
                tuple("heap.high:b", Level.RED),
                tuple("gc.pressure:b", Level.YELLOW),
                tuple("gc.pressure:c", Level.RED));
        Alert heapB = alerts.stream().filter(a -> a.id().equals("heap.high:b")).findFirst().orElseThrow();
        assertThat(heapB.value()).isEqualTo(96.0);
        assertThat(heapB.threshold()).isEqualTo(95.0);
        assertThat(heapB.message()).isEqualTo("Heap on b at 96 % of max");
    }

    @Test
    void increaseRulesNeedAPreviousPoll() {
        HealthRules r = new HealthRules();
        ClientRequests none = new ClientRequests(null, null, null, null, null, 0L, 0L, 0L, 0L, 0L, 0L);
        ClientRequests some = new ClientRequests(null, null, null, null, null, 2L, 0L, 0L, 1L, 0L, 0L);
        List<ThreadPool> pools0 = List.of(new ThreadPool("MutationStage", 0L, 0L, 0L, 10L, 4L));
        List<ThreadPool> pools1 = List.of(new ThreadPool("MutationStage", 0L, 0L, 0L, 10L, 5L));
        assertThat(eval(r, 1, node("a", b -> {
            b.dropped = Map.of("MUTATION", 10L, "READ", 0L);
            b.clientRequests = some;
            b.threadPools = pools0;
        }))).isEmpty(); // first poll: no baseline, nothing currently blocked
        List<Alert> alerts = eval(r, 2, node("a", b -> {
            b.dropped = Map.of("MUTATION", 13L, "READ", 0L);
            b.clientRequests = some;
            b.threadPools = pools1;
        }));
        assertThat(alerts).extracting(Alert::id).containsExactly("dropped.messages:a", "threadpool.blocked:a");
        assertThat(alerts.get(0).value()).isEqualTo(3.0);
        assertThat(alerts.get(0).message()).contains("(MUTATION)");
        alerts = eval(r, 3, node("a", b -> {
            b.dropped = Map.of("MUTATION", 13L, "READ", 0L);
            b.clientRequests = new ClientRequests(null, null, null, null, null, 4L, 0L, 0L, 2L, 0L, 0L);
            b.threadPools = pools1;
        }));
        assertThat(alerts).extracting(Alert::id).containsExactly("client.timeouts:a");
        assertThat(alerts.get(0).value()).isEqualTo(3.0);
        assertThat(eval(r, 4, node("a", b -> b.clientRequests = none))).isEmpty(); // counter reset is not an increase
    }

    @Test
    void currentlyBlockedPoolAlertsImmediately() {
        List<Alert> alerts = eval(new HealthRules(), 1, node("a",
                b -> b.threadPools = List.of(new ThreadPool("ReadStage", 0L, 0L, 1L, 0L, 0L))));
        assertThat(alerts).extracting(Alert::id).containsExactly("threadpool.blocked:a");
        assertThat(alerts.get(0).message()).contains("ReadStage");
    }

    @Test
    void compactionDiskAndLoadImbalance() {
        List<Alert> alerts = eval(new HealthRules(), 1,
                node("a", b -> { b.pendingCompactions = 101L; b.loadBytes = 100L; }),
                node("b", b -> { b.pendingCompactions = 100L; b.loadBytes = 100L;
                    b.dataDirs = List.of(new DataDir("/d1", 100L, 15L), new DataDir("/d2", 100L, 50L)); }),
                node("c", b -> { b.loadBytes = 400L;
                    b.dataDirs = List.of(new DataDir("/d1", 100L, 5L)); }),
                node("x", b -> { b.datacenter = "dc2"; b.loadBytes = 10_000L; }));
        assertThat(alerts).extracting(Alert::id, Alert::level).containsExactlyInAnyOrder(
                tuple("compaction.backlog:a", Level.YELLOW),
                tuple("disk.usage:b", Level.YELLOW),
                tuple("disk.usage:c", Level.RED),
                tuple("load.imbalance:c", Level.YELLOW));
        Alert disk = alerts.stream().filter(a -> a.id().equals("disk.usage:b")).findFirst().orElseThrow();
        assertThat(disk.message()).isEqualTo("Data directory /d1 on b is 85 % full");
        Alert load = alerts.stream().filter(a -> a.id().equals("load.imbalance:c")).findFirst().orElseThrow();
        assertThat(load.value()).isEqualTo(2.0); // 400 / avg(100, 100, 400)
    }

    @Test
    void hintsBacklogAfterThreeConsecutivePolls() {
        HealthRules r = new HealthRules();
        assertThat(eval(r, 1, node("a", b -> b.hintsInProgress = 5L))).isEmpty();
        assertThat(eval(r, 2, node("a", b -> b.hintsInProgress = 5L))).isEmpty();
        assertThat(eval(r, 3, node("a", b -> b.hintsInProgress = 5L))).extracting(Alert::id)
                .containsExactly("hints.backlog:a");
        assertThat(eval(r, 4, node("a", b -> b.hintsInProgress = 0L))).isEmpty();
        assertThat(eval(r, 5, node("a", b -> b.hintsInProgress = 5L))).isEmpty(); // counter restarted
    }

    @Test
    void sinceIsStableWhileActiveAndResetsAfterClearing() {
        HealthRules r = new HealthRules();
        NodeSnapshot hot = node("a", b -> { b.heapUsedBytes = 90L; b.heapMaxBytes = 100L; });
        NodeSnapshot hotter = node("a", b -> { b.heapUsedBytes = 99L; b.heapMaxBytes = 100L; });
        NodeSnapshot ok = node("a", b -> { b.heapUsedBytes = 10L; b.heapMaxBytes = 100L; });
        assertThat(eval(r, 1_000, hot).get(0).sinceEpochMs()).isEqualTo(1_000);
        Alert later = eval(r, 2_000, hotter).get(0);
        assertThat(later.sinceEpochMs()).isEqualTo(1_000); // same id across a YELLOW -> RED change
        assertThat(later.level()).isEqualTo(Level.RED);
        assertThat(eval(r, 3_000, ok)).isEmpty();
        assertThat(eval(r, 4_000, hot).get(0).sinceEpochMs()).isEqualTo(4_000);
    }

    @Test
    void overridesChangeThresholds() {
        Thresholds t = new Thresholds(50.0, 60.0, null, null, 10L, null, null, 3.0, 1).validated();
        assertThat(t.effective().gcYellowPct()).isEqualTo(10.0);
        List<Alert> alerts = new HealthRules().evaluate(List.of(
                node("a", b -> { b.heapUsedBytes = 55L; b.heapMaxBytes = 100L; b.pendingCompactions = 11L;
                    b.hintsInProgress = 1L; b.loadBytes = 100L; }),
                node("b", b -> b.loadBytes = 250L)), true, t, 1);
        assertThat(alerts).extracting(Alert::id).containsExactlyInAnyOrder("heap.high:a", "compaction.backlog:a",
                "hints.backlog:a");
        assertThat(alerts).filteredOn(a -> a.rule().equals("heap.high")).singleElement()
                .satisfies(a -> assertThat(a.threshold()).isEqualTo(50.0));
    }

    @Test
    void thresholdValidation() {
        assertThatThrownBy(() -> new Thresholds(90.0, 80.0, null, null, null, null, null, null, null).validated())
                .isInstanceOf(ApiException.class).hasMessageContaining("heap");
        assertThatThrownBy(() -> new Thresholds(null, null, null, 120.0, null, null, null, null, null).validated())
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new Thresholds(null, null, null, null, -1L, null, null, null, null).validated())
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new Thresholds(null, null, null, null, null, null, null, 1.0, null).validated())
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new Thresholds(null, null, null, null, null, null, null, null, 0).validated())
                .isInstanceOf(ApiException.class);
        assertThat(Thresholds.DEFAULTS.validated()).isEqualTo(Thresholds.DEFAULTS);
    }
}
