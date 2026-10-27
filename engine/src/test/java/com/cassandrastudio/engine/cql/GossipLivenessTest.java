package com.cassandrastudio.engine.cql;

import static org.assertj.core.api.Assertions.assertThat;

import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.metadata.NodeState;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class GossipLivenessTest {
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final List<Long> sleeps = new ArrayList<>();
    private final GossipLiveness liveness = new GossipLiveness(now::get, ms -> {
        sleeps.add(ms);
        now.addAndGet(ms);
    });
    private long hbA = 100;
    private long hbB = 50;
    private boolean bMoves = true;

    private List<GossipLiveness.Beat> beats() {
        hbA += 2;
        if (bMoves) hbB += 2;
        return List.of(new GossipLiveness.Beat("AAAA", 1, hbA, "NORMAL,123"),
                new GossipLiveness.Beat("bbbb", 1, hbB, "NORMAL,456"),
                new GossipLiveness.Beat("cccc", 1, 7, "LEFT,789,1700000000000"));
    }

    @Test
    void liveNodesAreUpOnTheFirstLook() {
        Map<String, String> v = liveness.view("c1", this::beats);
        assertThat(v).containsEntry("aaaa", "UP").containsEntry("bbbb", "UP").containsEntry("cccc", "LEFT");
        assertThat(sleeps).containsExactly(GossipLiveness.FIRST_SAMPLE_GAP_MS); // one extra sample, once
        now.addAndGet(10_000);
        liveness.view("c1", this::beats);
        assertThat(sleeps).hasSize(1);
    }

    @Test
    void aStillHeartbeatTurnsDownAfterTheThreshold() {
        liveness.view("c1", this::beats);
        bMoves = false;
        now.addAndGet(10_000);
        assertThat(liveness.view("c1", this::beats)).containsEntry("bbbb", "UP");
        now.addAndGet(GossipLiveness.DOWN_AFTER_MS);
        assertThat(liveness.view("c1", this::beats)).containsEntry("bbbb", "DOWN").containsEntry("aaaa", "UP");
        bMoves = true; // restarted or back
        now.addAndGet(10_000);
        assertThat(liveness.view("c1", this::beats)).containsEntry("bbbb", "UP");
    }

    @Test
    void aNodeThatNeverMovesIsUnknownUntilTheThreshold() {
        bMoves = false;
        assertThat(liveness.view("c1", this::beats)).containsEntry("bbbb", "UNKNOWN");
        now.addAndGet(GossipLiveness.DOWN_AFTER_MS);
        assertThat(liveness.view("c1", this::beats)).containsEntry("bbbb", "DOWN");
    }

    @Test
    void readsCloseTogetherReuseTheView() {
        AtomicLong reads = new AtomicLong();
        GossipLiveness.Source src = () -> {
            reads.incrementAndGet();
            return beats();
        };
        liveness.view("c1", src);
        long after = reads.get();
        now.addAndGet(500);
        liveness.view("c1", src);
        assertThat(reads.get()).isEqualTo(after);
    }

    @Test
    void olderCassandraIsRememberedAsUnsupportedAndFailuresAreNotCached() {
        AtomicLong reads = new AtomicLong();
        assertThat(liveness.view("old", () -> {
            reads.incrementAndGet();
            return null;
        })).isNull();
        now.addAndGet(60_000);
        assertThat(liveness.view("old", this::beats)).isNull();
        assertThat(reads.get()).isEqualTo(1);

        assertThat(liveness.view("flaky", () -> {
            throw new IllegalStateException("timeout");
        })).isNull();
        assertThat(liveness.view("flaky", this::beats)).containsEntry("aaaa", "UP");
        liveness.forget("old");
        assertThat(liveness.view("old", this::beats)).containsEntry("aaaa", "UP");
    }

    @Test
    void clusterViewBeatsTheDriversOwnConnectionState() {
        Set<String> everUp = new HashSet<>();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        // the node Studio is connected to
        assertThat(ClusterService.state(node(a, NodeState.UP), null, everUp)).isEqualTo("UP");
        // a node this computer cannot reach: gossip says up
        assertThat(ClusterService.state(node(b, NodeState.DOWN), Map.of(b.toString(), "UP"), everUp)).isEqualTo("UP");
        // no gossip view (Cassandra < 4.1) and never reachable: unknown, not down
        assertThat(ClusterService.state(node(b, NodeState.DOWN), null, everUp)).isEqualTo("UNKNOWN");
        assertThat(ClusterService.state(node(b, NodeState.UNKNOWN), null, everUp)).isEqualTo("UNKNOWN");
        // gossip says down
        assertThat(ClusterService.state(node(b, NodeState.DOWN), Map.of(b.toString(), "DOWN"), everUp)).isEqualTo("DOWN");
        // a node that was reachable and dropped is down even without gossip
        assertThat(ClusterService.state(node(a, NodeState.DOWN), null, everUp)).isEqualTo("DOWN");
        // a live connection wins over a stale gossip view
        assertThat(ClusterService.state(node(a, NodeState.UP), Map.of(a.toString(), "DOWN"), everUp)).isEqualTo("UP");
    }

    private static Node node(UUID hostId, NodeState state) {
        return (Node) Proxy.newProxyInstance(Node.class.getClassLoader(), new Class<?>[] {Node.class}, (p, m, args) ->
                switch (m.getName()) {
                    case "getHostId" -> hostId;
                    case "getState" -> state;
                    case "toString" -> "node " + hostId;
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == args[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }
}
