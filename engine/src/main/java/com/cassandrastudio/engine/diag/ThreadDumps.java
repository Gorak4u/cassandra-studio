package com.cassandrastudio.engine.diag;

import java.lang.management.LockInfo;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.management.openmbean.CompositeData;

/**
 * Thread dumps (JVM-1): the model the API returns, built from {@code Threading.dumpAllThreads}
 * composite data, with grouping by state and by identical stack, deadlock cycles and lock-owner
 * chains for blocked threads, comparison of two dumps and a jstack-like text export.
 */
public final class ThreadDumps {
    private ThreadDumps() {}

    /** Longest lock-owner chain followed for a blocked thread. */
    static final int MAX_CHAIN = 16;
    /** Frames shown per stuck thread in a comparison. */
    static final int STUCK_FRAMES = 8;

    public static final List<String> STATES =
            List.of("RUNNABLE", "BLOCKED", "WAITING", "TIMED_WAITING", "NEW", "TERMINATED");

    /** One stack frame; {@code locked} = monitors this frame holds, as "<0xhash> (a Class)". */
    public record Frame(String className, String method, String file, Integer line, boolean nativeMethod,
                        List<String> locked) {
        public String text() {
            String where = nativeMethod ? "Native Method"
                    : file == null ? "Unknown Source" : line != null && line >= 0 ? file + ":" + line : file;
            return className + "." + method + "(" + where + ")";
        }
    }

    /**
     * One thread. {@code lock} is what it waits for ("<0xhash> (a Class)"), {@code ownerChain} the
     * threads holding the lock it waits for, transitively ("name #id" each), for blocked threads.
     */
    public record ThreadEntry(long id, String name, String state, boolean daemon, Integer priority, String lock,
                              Long lockOwnerId, String lockOwnerName, boolean inNative, boolean suspended,
                              long blockedCount, long waitedCount, List<Frame> stack, List<String> lockedSynchronizers,
                              String stackKey, boolean deadlocked, List<String> ownerChain) {}

    /** Threads sharing the same stack (thread pools idling in the same place collapse to one group). */
    public record StackGroup(String key, int count, Map<String, Integer> states, List<Long> threadIds,
                             List<String> threadNames, List<Frame> stack) {}

    /** One deadlock cycle; {@code lines} reads "A" waits for X held by "B". */
    public record Deadlock(List<Long> threadIds, List<String> lines) {}

    public record ThreadDump(String id, String node, long takenAtMs, String jvm, String seriesId, int threadCount,
                             Map<String, Integer> byState, int blockedCount, List<Deadlock> deadlocks,
                             List<ThreadEntry> threads, List<StackGroup> groups) {
        public Summary summary() {
            int dl = deadlocks.stream().mapToInt(d -> d.threadIds().size()).sum();
            return new Summary(id, node, takenAtMs, jvm, seriesId, threadCount, byState, blockedCount, dl);
        }
    }

    public record Summary(String id, String node, long takenAtMs, String jvm, String seriesId, int threadCount,
                          Map<String, Integer> byState, int blockedCount, int deadlockedCount) {}

    // ---- building ----------------------------------------------------------------------

    /** Converts {@code dumpAllThreads} results (any JDK 8..21 node) into entries. */
    static List<ThreadEntry> entries(CompositeData[] raw) {
        List<ThreadInfo> infos = new ArrayList<>();
        if (raw != null) {
            for (CompositeData cd : raw) {
                if (cd != null) infos.add(ThreadInfo.from(cd));
            }
        }
        return entriesOf(infos);
    }

    static List<ThreadEntry> entriesOf(List<ThreadInfo> infos) {
        List<ThreadEntry> out = new ArrayList<>();
        for (ThreadInfo ti : infos) {
            StackTraceElement[] st = ti.getStackTrace();
            Map<Integer, List<String>> lockedAt = new HashMap<>();
            for (MonitorInfo mi : ti.getLockedMonitors()) {
                lockedAt.computeIfAbsent(mi.getLockedStackDepth(), k -> new ArrayList<>()).add(lockText(mi));
            }
            List<Frame> frames = new ArrayList<>(st.length);
            for (int i = 0; i < st.length; i++) {
                StackTraceElement e = st[i];
                frames.add(new Frame(e.getClassName(), e.getMethodName(), e.getFileName(),
                        e.getLineNumber() >= 0 ? e.getLineNumber() : null, e.isNativeMethod(),
                        lockedAt.getOrDefault(i, List.of())));
            }
            List<String> syncs = new ArrayList<>();
            for (LockInfo li : ti.getLockedSynchronizers()) syncs.add(lockText(li));
            long owner = ti.getLockOwnerId();
            out.add(new ThreadEntry(ti.getThreadId(), ti.getThreadName(), ti.getThreadState().name(), ti.isDaemon(),
                    ti.getPriority() > 0 ? ti.getPriority() : null, ti.getLockInfo() == null ? null : lockText(ti.getLockInfo()),
                    owner >= 0 ? owner : null, ti.getLockOwnerName(), ti.isInNative(), ti.isSuspended(),
                    ti.getBlockedCount(), ti.getWaitedCount(), frames, syncs, stackKey(frames), false, List.of()));
        }
        return out;
    }

    static String lockText(LockInfo li) {
        return "<0x" + String.format("%016x", li.getIdentityHashCode()) + "> (a " + li.getClassName() + ")";
    }

    static String stackKey(List<Frame> frames) {
        StringBuilder sb = new StringBuilder();
        for (Frame f : frames) sb.append(f.text()).append('\n');
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(sb.toString().hashCode());
        }
    }

    /** Builds the analysed dump: deadlock cycles, owner chains, state counts and stack groups. */
    static ThreadDump build(String id, String node, long takenAtMs, String jvm, String seriesId,
                            List<ThreadEntry> raw, long[] deadlockedIds) {
        Map<Long, ThreadEntry> byId = new LinkedHashMap<>();
        for (ThreadEntry t : raw) byId.put(t.id(), t);
        Set<Long> dead = new HashSet<>();
        if (deadlockedIds != null) for (long l : deadlockedIds) dead.add(l);

        List<ThreadEntry> threads = new ArrayList<>(raw.size());
        for (ThreadEntry t : raw) {
            List<String> chain = "BLOCKED".equals(t.state()) || dead.contains(t.id()) || t.lockOwnerId() != null
                    ? ownerChain(t, byId) : List.of();
            threads.add(new ThreadEntry(t.id(), t.name(), t.state(), t.daemon(), t.priority(), t.lock(), t.lockOwnerId(),
                    t.lockOwnerName(), t.inNative(), t.suspended(), t.blockedCount(), t.waitedCount(), t.stack(),
                    t.lockedSynchronizers(), t.stackKey(), dead.contains(t.id()), chain));
        }
        // Blocked and deadlocked first, then by name, so the interesting threads lead the list.
        threads.sort(Comparator.comparingInt((ThreadEntry t) -> t.deadlocked() ? 0 : "BLOCKED".equals(t.state()) ? 1 : 2)
                .thenComparing(ThreadEntry::name, String.CASE_INSENSITIVE_ORDER));

        Map<String, Integer> byState = new LinkedHashMap<>();
        for (String s : STATES) byState.put(s, 0);
        for (ThreadEntry t : threads) byState.merge(t.state(), 1, Integer::sum);
        int blocked = byState.getOrDefault("BLOCKED", 0);

        return new ThreadDump(id, node, takenAtMs, jvm, seriesId, threads.size(), byState, blocked,
                deadlocks(dead, byId), threads, groups(threads));
    }

    /** "owner #id" for each thread up the chain of lock owners, stopping at a repeat (deadlock). */
    static List<String> ownerChain(ThreadEntry t, Map<Long, ThreadEntry> byId) {
        List<String> chain = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        seen.add(t.id());
        ThreadEntry cur = t;
        while (cur != null && cur.lockOwnerId() != null && chain.size() < MAX_CHAIN) {
            long o = cur.lockOwnerId();
            ThreadEntry owner = byId.get(o);
            String name = owner != null ? owner.name() : cur.lockOwnerName();
            if (!seen.add(o)) {
                chain.add("\"" + name + "\" #" + o + " (cycle)");
                break;
            }
            chain.add("\"" + name + "\" #" + o + (owner != null ? " " + owner.state() : ""));
            cur = owner;
        }
        return List.copyOf(chain);
    }

    static List<Deadlock> deadlocks(Set<Long> dead, Map<Long, ThreadEntry> byId) {
        List<Deadlock> out = new ArrayList<>();
        Set<Long> done = new HashSet<>();
        for (Long start : new java.util.TreeSet<>(dead)) {
            if (done.contains(start)) continue;
            // walk owners from this thread until a thread repeats: the repeated part is the cycle
            List<Long> path = new ArrayList<>();
            Long cur = start;
            while (cur != null && !path.contains(cur) && byId.containsKey(cur) && path.size() <= byId.size()) {
                path.add(cur);
                cur = byId.get(cur).lockOwnerId();
            }
            List<Long> cycle = cur != null && path.contains(cur) ? path.subList(path.indexOf(cur), path.size()) : path;
            List<String> lines = new ArrayList<>();
            for (Long tid : cycle) {
                ThreadEntry t = byId.get(tid);
                ThreadEntry o = t.lockOwnerId() == null ? null : byId.get(t.lockOwnerId());
                lines.add("\"" + t.name() + "\" #" + t.id() + " waits for " + (t.lock() == null ? "a lock" : t.lock())
                        + " held by \"" + (o != null ? o.name() : t.lockOwnerName()) + "\" #" + t.lockOwnerId());
            }
            done.addAll(path);
            if (!cycle.isEmpty()) out.add(new Deadlock(List.copyOf(cycle), lines));
        }
        return out;
    }

    static List<StackGroup> groups(List<ThreadEntry> threads) {
        Map<String, List<ThreadEntry>> by = new LinkedHashMap<>();
        for (ThreadEntry t : threads) by.computeIfAbsent(t.stackKey(), k -> new ArrayList<>()).add(t);
        List<StackGroup> out = new ArrayList<>();
        for (var e : by.entrySet()) {
            List<ThreadEntry> ts = e.getValue();
            Map<String, Integer> states = new TreeMap<>();
            for (ThreadEntry t : ts) states.merge(t.state(), 1, Integer::sum);
            out.add(new StackGroup(e.getKey(), ts.size(), states, ts.stream().map(ThreadEntry::id).toList(),
                    ts.stream().map(ThreadEntry::name).limit(100).toList(), ts.get(0).stack()));
        }
        out.sort(Comparator.comparingInt(StackGroup::count).reversed().thenComparing(g -> g.threadNames().get(0)));
        return out;
    }

    // ---- comparison --------------------------------------------------------------------

    public record ThreadRef(long id, String name, String state) {}

    public record StateChange(long id, String name, String before, String after) {}

    /** A thread with the same stack in both dumps; {@code likelyIdle} when parked in native code (epoll, accept). */
    public record Stuck(long id, String name, String state, boolean likelyIdle, List<Frame> top) {}

    public record Comparison(Summary a, Summary b, long intervalMs, List<ThreadRef> added, List<ThreadRef> removed,
                             List<StateChange> changed, List<Stuck> stuck, Map<String, int[]> stateCounts) {}

    /** Compares an earlier dump {@code a} with a later {@code b} (swapped when given the other way round). */
    public static Comparison compare(ThreadDump a, ThreadDump b) {
        if (a.takenAtMs() > b.takenAtMs()) {
            ThreadDump t = a;
            a = b;
            b = t;
        }
        Map<String, ThreadEntry> before = new LinkedHashMap<>();
        for (ThreadEntry t : a.threads()) before.put(t.id() + "|" + t.name(), t);
        Map<String, ThreadEntry> after = new LinkedHashMap<>();
        for (ThreadEntry t : b.threads()) after.put(t.id() + "|" + t.name(), t);

        List<ThreadRef> added = new ArrayList<>();
        List<StateChange> changed = new ArrayList<>();
        List<Stuck> stuck = new ArrayList<>();
        for (var e : after.entrySet()) {
            ThreadEntry n = e.getValue();
            ThreadEntry o = before.get(e.getKey());
            if (o == null) {
                added.add(new ThreadRef(n.id(), n.name(), n.state()));
                continue;
            }
            if (!o.state().equals(n.state())) changed.add(new StateChange(n.id(), n.name(), o.state(), n.state()));
            boolean active = "RUNNABLE".equals(n.state()) || "BLOCKED".equals(n.state());
            if (active && o.state().equals(n.state()) && !n.stack().isEmpty() && o.stackKey().equals(n.stackKey())) {
                boolean idle = "RUNNABLE".equals(n.state()) && (n.inNative() || n.stack().get(0).nativeMethod());
                stuck.add(new Stuck(n.id(), n.name(), n.state(), idle,
                        n.stack().subList(0, Math.min(STUCK_FRAMES, n.stack().size()))));
            }
        }
        List<ThreadRef> removed = new ArrayList<>();
        for (var e : before.entrySet()) {
            if (!after.containsKey(e.getKey())) {
                ThreadEntry o = e.getValue();
                removed.add(new ThreadRef(o.id(), o.name(), o.state()));
            }
        }
        // the interesting ones first: blocked before runnable, real work before idle network threads
        stuck.sort(Comparator.comparing(Stuck::likelyIdle)
                .thenComparing(s -> !"BLOCKED".equals(s.state())).thenComparing(Stuck::name));
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (String s : STATES) {
            counts.put(s, new int[] {a.byState().getOrDefault(s, 0), b.byState().getOrDefault(s, 0)});
        }
        return new Comparison(a.summary(), b.summary(), b.takenAtMs() - a.takenAtMs(), added, removed, changed, stuck,
                counts);
    }

    // ---- jstack-like text --------------------------------------------------------------

    /** The dump as text in the format of jstack, so existing tools (fastThread, TDA) can read it. */
    public static String jstack(ThreadDump d) {
        StringBuilder sb = new StringBuilder();
        sb.append(Instant.ofEpochMilli(d.takenAtMs())).append('\n');
        sb.append("Full thread dump ").append(d.jvm() == null ? "" : d.jvm()).append(" (node ").append(d.node())
                .append(", via JMX)\n\n");
        List<ThreadEntry> ordered = new ArrayList<>(d.threads());
        ordered.sort(Comparator.comparingLong(ThreadEntry::id));
        for (ThreadEntry t : ordered) {
            sb.append('"').append(t.name()).append("\" #").append(t.id());
            if (t.daemon()) sb.append(" daemon");
            if (t.priority() != null) sb.append(" prio=").append(t.priority());
            sb.append('\n');
            sb.append("   java.lang.Thread.State: ").append(t.state()).append('\n');
            for (int i = 0; i < t.stack().size(); i++) {
                Frame f = t.stack().get(i);
                sb.append("\tat ").append(f.text()).append('\n');
                if (i == 0 && t.lock() != null) {
                    String verb = "BLOCKED".equals(t.state()) ? "waiting to lock"
                            : f.className().endsWith("Unsafe") && "park".equals(f.method()) ? "parking to wait for"
                            : "waiting on";
                    sb.append("\t- ").append(verb).append(' ').append(t.lock());
                    if (t.lockOwnerId() != null) {
                        sb.append(" owned by \"").append(t.lockOwnerName()).append("\" #").append(t.lockOwnerId());
                    }
                    sb.append('\n');
                }
                for (String l : f.locked()) sb.append("\t- locked ").append(l).append('\n');
            }
            if (!t.lockedSynchronizers().isEmpty()) {
                sb.append("\n   Locked ownable synchronizers:\n");
                for (String s : t.lockedSynchronizers()) sb.append("\t- ").append(s).append('\n');
            }
            sb.append('\n');
        }
        for (Deadlock dl : d.deadlocks()) {
            sb.append("\nFound one Java-level deadlock:\n=============================\n");
            for (String l : dl.lines()) sb.append(l).append('\n');
        }
        if (!d.deadlocks().isEmpty()) sb.append("\nFound ").append(d.deadlocks().size()).append(" deadlock(s).\n");
        return sb.toString();
    }
}
