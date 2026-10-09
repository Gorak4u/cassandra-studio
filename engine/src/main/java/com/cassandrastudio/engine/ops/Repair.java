package com.cassandrastudio.engine.ops;

import static com.cassandrastudio.engine.ops.Beans.MAP;
import static com.cassandrastudio.engine.ops.Beans.STORAGE_SERVICE;
import static com.cassandrastudio.engine.ops.Beans.STR;

import com.cassandrastudio.engine.jobs.JobContext;
import com.cassandrastudio.engine.ops.Beans.Call;
import com.cassandrastudio.engine.util.ApiException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import javax.management.InstanceNotFoundException;
import javax.management.ListenerNotFoundException;
import javax.management.Notification;
import javax.management.NotificationListener;

/**
 * OPS-3: repair through StorageService.repairAsync (3.11 to 5.0) with live progress from the
 * JMX "progress" notifications (ProgressEvent: type, progressCount, total, message), the same
 * stream nodetool repair prints. On 4.0+ getParentRepairStatus is polled as well, so a lost
 * notification cannot leave the job waiting forever. Cancel = forceTerminateAllRepairSessions.
 */
public final class Repair {
    private Repair() {}

    public enum Parallelism {
        PARALLEL("parallel", null), SEQUENTIAL("sequential", "-seq"), DC_PARALLEL("dc_parallel", "-dcpar");

        final String option;
        final String flag;

        Parallelism(String option, String flag) {
            this.option = option;
            this.flag = flag;
        }

        public static Parallelism of(String s) {
            if (s == null || s.isBlank()) return PARALLEL;
            for (Parallelism p : values()) {
                if (p.option.equalsIgnoreCase(s) || p.name().equalsIgnoreCase(s)) return p;
            }
            throw ApiException.badRequest("parallelism must be parallel, sequential or dc_parallel");
        }
    }

    /** A token range (start, end] for sub-range repair. */
    public record Range(String start, String end) {}

    public record Request(List<String> nodes, String keyspace, List<String> tables, boolean full, boolean primaryRange,
                          List<String> dataCenters, Parallelism parallelism, List<Range> ranges, int jobThreads,
                          boolean continueOnError) {
        public Request {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            tables = tables == null ? List.of() : List.copyOf(tables);
            dataCenters = dataCenters == null ? List.of() : List.copyOf(dataCenters);
            ranges = ranges == null ? List.of() : List.copyOf(ranges);
            parallelism = parallelism == null ? Parallelism.PARALLEL : parallelism;
            jobThreads = jobThreads <= 0 ? 1 : jobThreads;
        }
    }

    static void validate(Request r) {
        if (r.keyspace() == null) throw ApiException.badRequest("keyspace is required");
        Maintenance.name(r.keyspace(), "keyspace");
        r.tables().forEach(t -> Maintenance.name(t, "table"));
        for (String dc : r.dataCenters()) {
            if (!dc.matches("[A-Za-z0-9_.\\-]{1,128}")) throw ApiException.badRequest("Invalid datacenter name '" + dc + "'");
        }
        if (r.jobThreads() > 4) throw ApiException.badRequest("jobThreads must be between 1 and 4");
        if (r.primaryRange() && !r.ranges().isEmpty()) {
            throw ApiException.badRequest("Primary range (-pr) and sub-range repair cannot be combined");
        }
        for (Range g : r.ranges()) {
            if (!token(g.start()) || !token(g.end())) {
                throw ApiException.badRequest("Range tokens must be integers (Murmur3/Random partitioner): "
                        + g.start() + " .. " + g.end());
            }
        }
    }

    private static boolean token(String t) {
        return t != null && t.matches("-?\\d{1,40}");
    }

    /** One nodetool command per sub-range (nodetool takes one -st/-et pair), or one for the whole node. */
    static List<String> preview(Request r, String node) {
        StringBuilder sb = new StringBuilder("nodetool -h ").append(node).append(" repair");
        if (r.full()) sb.append(" -full");
        if (r.primaryRange()) sb.append(" -pr");
        if (r.parallelism().flag != null) sb.append(' ').append(r.parallelism().flag);
        r.dataCenters().forEach(dc -> sb.append(" -dc ").append(dc));
        if (r.jobThreads() > 1) sb.append(" -j ").append(r.jobThreads());
        String tail = " -- " + r.keyspace() + (r.tables().isEmpty() ? "" : " " + String.join(" ", r.tables()));
        if (r.ranges().isEmpty()) return List.of(sb + tail);
        List<String> out = new ArrayList<>();
        for (Range g : r.ranges()) out.add(sb + " -st " + g.start() + " -et " + g.end() + tail);
        return out;
    }

    static List<String> warnings(Request r, int nodeCount, boolean anyV3) {
        List<String> w = new ArrayList<>();
        if (!r.full() && anyV3) {
            w.add("Incremental repair on Cassandra 3.x can over-stream and leave SSTables anticompacted; full repair is "
                    + "the usual choice there.");
        }
        if (!r.full()) w.add("Incremental repair marks repaired SSTables; switching back to full repair later needs care.");
        if (r.full() && !r.primaryRange() && r.ranges().isEmpty() && nodeCount > 1) {
            w.add("Full repair without -pr on several nodes repairs each range once per replica; use -pr when you repair "
                    + "every node.");
        }
        w.add("Repair streams data between replicas and builds Merkle trees (CPU, disk and network load).");
        if (nodeCount > 1) w.add("Runs on " + nodeCount + " nodes, one after another.");
        return w;
    }

    /** The repairAsync options (RepairOption keys, same on 3.11 to 5.0) for one call. */
    static Map<String, String> options(Request r, Range range) {
        Map<String, String> o = new LinkedHashMap<>();
        o.put("parallelism", r.parallelism().option);
        o.put("primaryRange", String.valueOf(r.primaryRange()));
        o.put("incremental", String.valueOf(!r.full()));
        o.put("jobThreads", String.valueOf(r.jobThreads()));
        o.put("trace", "false");
        if (!r.tables().isEmpty()) o.put("columnFamilies", String.join(",", r.tables()));
        if (!r.dataCenters().isEmpty()) o.put("dataCenters", String.join(",", r.dataCenters()));
        if (range != null) o.put("ranges", range.start() + ":" + range.end());
        return o;
    }

    /** ProgressEventType ordinals (org.apache.cassandra.utils.progress), unchanged from 3.0 to 5.0. */
    static final int START = 0, PROGRESS = 1, ERROR = 2, ABORT = 3, SUCCESS = 4, COMPLETE = 5, NOTIFICATION = 6;

    /** Tracks one repair command's notifications: progress fraction, errors, completion. */
    static final class Tracker {
        final String source;
        Double fraction;
        boolean complete;
        boolean failed;
        String lastError;

        Tracker(int command) {
            this.source = "repair:" + command;
        }

        /** Applies one notification; returns the log line for it, or null when it is not for this command. */
        String apply(Notification n) {
            if (!"progress".equals(n.getType()) || !source.equals(String.valueOf(n.getSource()))) return null;
            int type = -1;
            if (n.getUserData() instanceof Map<?, ?> m) {
                type = m.get("type") instanceof Number t ? t.intValue() : -1;
                if (m.get("progressCount") instanceof Number c && m.get("total") instanceof Number t && t.intValue() > 0) {
                    fraction = Math.min(1.0, c.doubleValue() / t.doubleValue());
                }
            }
            String msg = n.getMessage() == null ? "" : n.getMessage();
            if (type == ERROR || type == ABORT) {
                failed = true;
                lastError = msg;
            }
            if (type == COMPLETE) complete = true;
            return msg;
        }
    }

    /**
     * Runs one repair command on one node and waits for it. {@code ctx} gets the node's
     * progress through {@code report}; returns a short result, throws OpsException on failure.
     */
    static String run(Beans b, Request r, Range range, JobContext ctx, java.util.function.DoubleConsumer report,
                      java.util.function.Consumer<String> log, LongSupplier clock, long statusPollMs) throws InterruptedException {
        BlockingQueue<Notification> queue = new LinkedBlockingQueue<>();
        NotificationListener listener = (n, hb) -> queue.add(n);
        var ss = Beans.name(STORAGE_SERVICE);
        try {
            b.connection().addNotificationListener(ss, listener, null, null);
        } catch (InstanceNotFoundException e) {
            throw new OpsException("StorageService MBean not found");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            Object res = b.invoke(STORAGE_SERVICE, "repairAsync",
                    Call.of(STR, r.keyspace(), MAP, new HashMap<>(options(r, range))));
            int cmd = res instanceof Integer i ? i : -1;
            if (cmd <= 0) {
                log.accept("Nothing to repair for keyspace " + r.keyspace() + " (replication factor 1 or no ranges)");
                return "nothing to repair";
            }
            log.accept("Started repair command #" + cmd);
            Tracker t = new Tracker(cmd);
            boolean statusOp = b.has(STORAGE_SERVICE, "getParentRepairStatus", "int");
            long nextStatus = clock.getAsLong() + statusPollMs;
            while (!t.complete) {
                Notification n = queue.poll(500, TimeUnit.MILLISECONDS);
                if (n != null) {
                    String line = t.apply(n);
                    if (line != null && !line.isBlank()) log.accept(line);
                    if (t.fraction != null) report.accept(t.fraction);
                    continue;
                }
                ctx.checkCancelled();
                if (statusOp && clock.getAsLong() >= nextStatus) {
                    nextStatus = clock.getAsLong() + statusPollMs;
                    Object st = b.invoke(STORAGE_SERVICE, "getParentRepairStatus", Call.of("int", cmd));
                    if (st instanceof List<?> l && !l.isEmpty()) {
                        String state = String.valueOf(l.get(0)).toUpperCase(Locale.ROOT);
                        if (state.equals("COMPLETED") || state.equals("FAILED")) {
                            // Drain what arrived meanwhile, then trust the node's own status.
                            Notification late;
                            while ((late = queue.poll(200, TimeUnit.MILLISECONDS)) != null) {
                                String line = t.apply(late);
                                if (line != null && !line.isBlank()) log.accept(line);
                            }
                            if (state.equals("FAILED")) {
                                t.failed = true;
                                if (l.size() > 1) t.lastError = String.valueOf(l.get(1));
                            }
                            t.complete = true;
                        }
                    }
                }
            }
            if (t.failed) throw new OpsException("Repair #" + cmd + " failed" + (t.lastError == null ? "" : ": " + t.lastError));
            return "repair #" + cmd + " completed";
        } finally {
            try {
                b.connection().removeNotificationListener(ss, listener);
            } catch (ListenerNotFoundException | InstanceNotFoundException | IOException | RuntimeException e) {
                // connection gone or already removed
            }
        }
    }

    static void terminate(Beans b) {
        b.invoke(STORAGE_SERVICE, "forceTerminateAllRepairSessions", Call.of());
    }
}
