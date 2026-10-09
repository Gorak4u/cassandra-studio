package com.cassandrastudio.engine.diag;

import static org.assertj.core.api.Assertions.assertThat;

import com.cassandrastudio.engine.diag.ThreadDumps.ThreadDump;
import com.cassandrastudio.engine.diag.ThreadDumps.ThreadEntry;
import java.lang.management.ManagementFactory;
import java.util.concurrent.CountDownLatch;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** Dumps of this JVM over its platform MBean server, i.e. the same CompositeData a node returns. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ThreadDumpsTest {
    private final Object lockA = new Object();
    private final Object lockB = new Object();
    private Thread t1;
    private Thread t2;

    @BeforeAll
    void deadlock() throws Exception {
        CountDownLatch both = new CountDownLatch(2);
        t1 = Thread.ofPlatform().daemon().name("dl-one").start(() -> {
            synchronized (lockA) {
                both.countDown();
                await(both);
                synchronized (lockB) {
                    both.countDown();
                }
            }
        });
        t2 = Thread.ofPlatform().daemon().name("dl-two").start(() -> {
            synchronized (lockB) {
                both.countDown();
                await(both);
                synchronized (lockA) {
                    both.countDown();
                }
            }
        });
        long deadline = System.currentTimeMillis() + 5000;
        while ((t1.getState() != Thread.State.BLOCKED || t2.getState() != Thread.State.BLOCKED)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    private static void await(CountDownLatch l) {
        try {
            l.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadDump dump(String id, long at) throws Exception {
        MBeanServer s = ManagementFactory.getPlatformMBeanServer();
        ObjectName th = new ObjectName(Jmx.THREADING);
        CompositeData[] raw = (CompositeData[]) Jmx.call(s, Jmx.THREADING, "dumpAllThreads", new Object[] {true, true},
                new String[] {boolean.class.getName(), boolean.class.getName()});
        long[] dead = (long[]) s.invoke(th, "findDeadlockedThreads", new Object[0], new String[0]);
        return ThreadDumps.build(id, "127.0.0.1", at, "test JVM", null, ThreadDumps.entries(raw), dead);
    }

    @Test
    void findsTheDeadlockWithOwnerChain() throws Exception {
        ThreadDump d = dump("d1", 1000);
        assertThat(d.deadlocks()).hasSize(1);
        assertThat(d.deadlocks().get(0).threadIds()).containsExactlyInAnyOrder(t1.threadId(), t2.threadId());
        assertThat(d.deadlocks().get(0).lines()).anyMatch(l -> l.contains("\"dl-one\"") && l.contains("held by \"dl-two\""));
        assertThat(d.blockedCount()).isGreaterThanOrEqualTo(2);
        assertThat(d.byState().get("BLOCKED")).isEqualTo(d.blockedCount());

        // deadlocked threads lead the list, each with the chain of lock owners ending in the cycle
        ThreadEntry first = d.threads().get(0);
        assertThat(first.deadlocked()).isTrue();
        ThreadEntry one = d.threads().stream().filter(t -> t.name().equals("dl-one")).findFirst().orElseThrow();
        assertThat(one.lock()).contains("java.lang.Object");
        assertThat(one.ownerChain()).hasSize(2);
        assertThat(one.ownerChain().get(0)).startsWith("\"dl-two\"");
        assertThat(one.ownerChain().get(1)).contains("(cycle)");
        assertThat(one.stack().stream().flatMap(f -> f.locked().stream())).isNotEmpty();
    }

    @Test
    void groupsAndJstackText() throws Exception {
        ThreadDump d = dump("d1", 1000);
        assertThat(d.groups().stream().mapToInt(ThreadDumps.StackGroup::count).sum()).isEqualTo(d.threadCount());
        assertThat(d.groups()).allMatch(g -> g.threadIds().size() == g.count());
        String txt = ThreadDumps.jstack(d);
        assertThat(txt).contains("\"dl-one\" #" + t1.threadId() + " daemon")
                .contains("java.lang.Thread.State: BLOCKED")
                .contains("- waiting to lock <0x")
                .contains("- locked <0x")
                .contains("Found one Java-level deadlock:");
    }

    @Test
    void comparesTwoDumps() throws Exception {
        ThreadDump a = dump("a", 1000);
        CountDownLatch stop = new CountDownLatch(1);
        Thread extra = Thread.ofPlatform().daemon().name("new-in-b").start(() -> await(stop));
        try {
            Thread.sleep(50);
            ThreadDump b = dump("b", 3000);
            ThreadDumps.Comparison c = ThreadDumps.compare(b, a); // given the wrong way round
            assertThat(c.a().id()).isEqualTo("a");
            assertThat(c.intervalMs()).isEqualTo(2000);
            assertThat(c.added()).anyMatch(r -> r.name().equals("new-in-b"));
            // the deadlocked threads did not move: stuck in both, blocked ones first
            assertThat(c.stuck()).anyMatch(s -> s.name().equals("dl-one") && s.state().equals("BLOCKED"));
            assertThat(c.stuck().get(0).state()).isEqualTo("BLOCKED");
            assertThat(c.stateCounts().get("BLOCKED")[0]).isEqualTo(a.byState().get("BLOCKED"));
        } finally {
            stop.countDown();
            extra.join();
        }
    }
}
