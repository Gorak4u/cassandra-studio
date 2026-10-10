package com.cassandrastudio.engine.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.cassandrastudio.engine.store.Database;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Two instances on one store (a SQLite file here; PostgreSQL in ServerIntegrationTest). */
class LeaderElectionTest {
    private static final long LEASE = 4_000;
    @TempDir
    Path dir;
    private Database dbA;
    private Database dbB;
    private final AtomicInteger electedA = new AtomicInteger();
    private final AtomicInteger electedB = new AtomicInteger();
    private LeaderElection a;
    private LeaderElection b;

    @BeforeEach
    void setUp() {
        dbA = Database.open(dir);
        dbB = Database.open(dir);
        a = new LeaderElection(dbA, "a", "host-a", "test", LEASE, electedA::incrementAndGet);
        b = new LeaderElection(dbB, "b", "host-b", "test", LEASE, electedB::incrementAndGet);
    }

    @AfterEach
    void tearDown() {
        a.close();
        b.close();
        dbA.close();
        dbB.close();
    }

    @Test
    void oneLeaderAndTakeoverAfterACrash() throws Exception {
        a.round();
        b.round();
        assertThat(a.isLeader()).isTrue();
        assertThat(b.isLeader()).isFalse();
        assertThat(electedA).hasValue(1);
        a.round(); // renewal: still leader, not "elected" again
        assertThat(a.isLeader()).isTrue();
        assertThat(electedA).hasValue(1);

        a.crash(); // stops renewing without giving the lease up
        b.round();
        assertThat(b.isLeader()).isFalse(); // the lease has not run out yet
        Thread.sleep(LEASE + 200);
        b.round();
        assertThat(b.isLeader()).isTrue();
        assertThat(electedB).hasValue(1);
        assertThat(a.isLeader()).isFalse();

        Map<String, Object> status = b.status();
        assertThat(status.get("leader")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> instances = (List<Map<String, Object>>) status.get("instances");
        assertThat(instances).extracting(i -> i.get("id")).containsExactly("a", "b");
        assertThat(instances).filteredOn(i -> Boolean.TRUE.equals(i.get("leader"))).extracting(i -> i.get("id")).containsExactly("b");
    }

    @Test
    void cleanStopHandsOverAtOnce() {
        a.round();
        assertThat(a.isLeader()).isTrue();
        a.close();
        b.round();
        assertThat(b.isLeader()).isTrue();
    }

    @Test
    void leaderWithoutRenewalStopsBelievingBeforeTheLeaseEnds() throws Exception {
        a.round();
        assertThat(a.isLeader()).isTrue();
        Thread.sleep(LEASE * 3 / 4 + 100);
        assertThat(a.isLeader()).isFalse(); // a paused leader steps back before anyone may take over
    }

    @Test
    void runningLoopRenews() throws Exception {
        a.start();
        b.start();
        Thread.sleep(LEASE * 2);
        assertThat(a.isLeader() ^ b.isLeader()).isTrue();
        assertThat(electedA.get() + electedB.get()).isEqualTo(1);
    }
}
