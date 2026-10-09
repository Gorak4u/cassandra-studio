package com.cassandrastudio.engine.ops;

import com.cassandrastudio.engine.conn.ConnectionRepository;
import com.cassandrastudio.engine.cql.ClusterService.NodeInfo;
import com.cassandrastudio.engine.guard.ActionGuard;
import com.cassandrastudio.engine.jmx.JmxAccess;
import com.cassandrastudio.engine.jmx.JmxAccess.JmxUnavailableException;
import com.cassandrastudio.engine.jmx.NodeEndpoint;
import com.cassandrastudio.engine.jobs.Job;
import com.cassandrastudio.engine.jobs.JobContext;
import com.cassandrastudio.engine.jobs.JobService;
import com.cassandrastudio.engine.metrics.Topology;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.JmxMethod;
import com.cassandrastudio.engine.ops.OpsModel.NodeError;
import com.cassandrastudio.engine.ops.OpsModel.NodeResult;
import com.cassandrastudio.engine.ops.OpsModel.Snapshot;
import com.cassandrastudio.engine.ops.OpsModel.SnapshotList;
import com.cassandrastudio.engine.ops.OpsModel.View;
import com.cassandrastudio.engine.util.ApiException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Function;

/**
 * Cluster operations (OPS-1..4): nodetool views read with the read JMX (30 s timeout), and
 * maintenance, repair and snapshots run as jobs on the operations JMX (no read timeout, as those
 * calls block), one node after another with progress per node. Every change passes ActionGuard
 * first, with the exact nodetool commands as preview; the job runner audits it.
 */
public final class OpsService implements AutoCloseable {
    static final long VIEW_TIMEOUT_MS = 60_000;
    static final long STATUS_POLL_MS = 10_000;
    static final String CATEGORY = "ops";

    private final ConnectionRepository connections;
    private final Function<String, Map<String, String>> secrets;
    private final JmxAccess readJmx;
    private final JmxAccess opsJmx;
    private final Topology topology;
    private final ActionGuard guard;
    private final JobService jobs;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    long pollIntervalMs = 2_000;

    public OpsService(ConnectionRepository connections, Function<String, Map<String, String>> secrets, JmxAccess readJmx,
                      JmxAccess opsJmx, Topology topology, ActionGuard guard, JobService jobs) {
        this.connections = connections;
        this.secrets = secrets;
        this.readJmx = readJmx;
        this.opsJmx = opsJmx;
        this.topology = topology;
        this.guard = guard;
        this.jobs = jobs;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    // ---- OPS-1 views ------------------------------------------------------------------------

    public List<String> views() {
        return NodeViews.ALL;
    }

    /** One view from one node (null = the first node that is up); keyspace, table and key as the view needs them. */
    public View view(String connectionId, String view, String node, String keyspace, String table, String key) {
        if (keyspace != null) Maintenance.name(keyspace, "keyspace");
        if (table != null) Maintenance.name(table, "table");
        NodeViews.Params params = new NodeViews.Params(keyspace, table, key);
        ConnectionConfig cfg = jmxConnection(connectionId);
        List<NodeInfo> all = topology.info(connectionId).nodes();
        NodeInfo n = node == null ? firstUp(all) : resolve(all, List.of(node)).get(0);
        Map<String, String> s = secrets.apply(connectionId);
        return call(() -> NodeViews.read(new Beans(session(readJmx, cfg, s, n)), view, n.address(), params, all),
                VIEW_TIMEOUT_MS, n.address());
    }

    // ---- OPS-2 maintenance -----------------------------------------------------------------

    public Job maintenance(String connectionId, Maintenance.Request r, ActionGuard.Confirmation confirmation) {
        Maintenance.validate(r);
        ConnectionConfig cfg = jmxConnection(connectionId);
        List<NodeInfo> nodes = resolve(topology.info(connectionId).nodes(), r.nodes());
        String label = label(nodes);
        List<String> preview = nodes.stream().map(n -> Maintenance.preview(r, n.address())).toList();
        String title = Maintenance.summary(r) + " on " + label;
        guard.check(cfg, new ActionGuard.Action(CATEGORY, title, preview, Maintenance.warnings(r, nodes.size()),
                Maintenance.destructive(r), nodeField(nodes)), confirmation);
        Map<String, String> s = secrets.apply(connectionId);
        Consumer<NodeInfo> stop = r.kind().stopType == null ? null
                : cur -> new Beans(session(readJmx, cfg, s, cur)).invoke(Beans.COMPACTION_MANAGER, "stopCompaction",
                        Beans.Call.of(Beans.STR, r.kind().stopType));
        return submit(connectionId, r.kind().id, title, nodes, r.continueOnError(), stop, (n, b, ctx, progress) -> {
            Future<String> f = executor.submit(() -> Maintenance.run(b, r));
            while (true) {
                try {
                    return f.get(pollIntervalMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    pollCompactions(cfg, s, n, r, ctx, progress);
                } catch (ExecutionException e) {
                    throw e.getCause() instanceof Exception ex ? ex : new OpsException(Beans.message(e.getCause()));
                }
            }
        });
    }

    /** Progress of a running compaction-type operation from CompactionManager.Compactions; best effort. */
    private void pollCompactions(ConnectionConfig cfg, Map<String, String> s, NodeInfo n, Maintenance.Request r,
                                 JobContext ctx, DoubleConsumer progress) {
        try {
            Object list = new Beans(session(readJmx, cfg, s, n)).attr(Beans.COMPACTION_MANAGER, "Compactions");
            if (!(list instanceof List<?> l)) return;
            long done = 0, total = 0;
            String what = null;
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> c)) continue;
                if (r.keyspace() != null && !r.keyspace().equals(String.valueOf(c.get("keyspace")))) continue;
                Long d = Beans.asLong(c.get("completed")), t = Beans.asLong(c.get("total"));
                if (d == null || t == null || t <= 0) continue;
                done += d;
                total += t;
                what = c.get("taskType") + " " + c.get("keyspace") + "." + c.get("columnfamily");
            }
            if (total > 0) {
                double f = Math.min(1.0, done / (double) total);
                ctx.progress(null, n.address() + ": " + what + " " + Fmt.pct(f));
                progress.accept(f);
            }
        } catch (RuntimeException e) {
            // progress is best effort
        }
    }

    // ---- OPS-3 repair ------------------------------------------------------------------------

    public Job repair(String connectionId, Repair.Request r, ActionGuard.Confirmation confirmation) {
        Repair.validate(r);
        ConnectionConfig cfg = jmxConnection(connectionId);
        List<NodeInfo> nodes = resolve(topology.info(connectionId).nodes(), r.nodes());
        List<String> preview = new ArrayList<>();
        nodes.forEach(n -> preview.addAll(Repair.preview(r, n.address())));
        boolean anyV3 = nodes.stream().anyMatch(n -> n.version() != null && n.version().startsWith("3."));
        String kind = r.full() ? "Full repair" : "Incremental repair";
        String title = kind + (r.primaryRange() ? " (-pr)" : "") + (r.ranges().isEmpty() ? "" : " (sub-range)") + " of "
                + r.keyspace() + (r.tables().isEmpty() ? "" : " (" + String.join(", ", r.tables()) + ")") + " on " + label(nodes);
        guard.check(cfg, new ActionGuard.Action(CATEGORY, title, preview, Repair.warnings(r, nodes.size(), anyV3), false,
                nodeField(nodes)), confirmation);
        Map<String, String> s = secrets.apply(connectionId);
        return submit(connectionId, "repair", title, nodes, r.continueOnError(),
                cur -> Repair.terminate(new Beans(session(opsJmx, cfg, s, cur))), (n, b, ctx, progress) -> {
            List<Repair.Range> ranges = r.ranges().isEmpty() ? java.util.Collections.singletonList(null) : r.ranges();
            List<String> out = new ArrayList<>();
            for (int i = 0; i < ranges.size(); i++) {
                ctx.checkCancelled();
                final int ri = i;
                out.add(Repair.run(b, r, ranges.get(i), ctx, f -> progress.accept((ri + f) / ranges.size()),
                        line -> ctx.log("[" + n.address() + "] " + line), System::currentTimeMillis, STATUS_POLL_MS));
            }
            return String.join("; ", out);
        });
    }

    // ---- OPS-4 snapshots ---------------------------------------------------------------------

    public SnapshotList snapshots(String connectionId, String node) {
        ConnectionConfig cfg = jmxConnection(connectionId);
        List<NodeInfo> all = topology.info(connectionId).nodes();
        List<NodeInfo> nodes = node == null ? all : resolve(all, List.of(node));
        Map<String, String> s = secrets.apply(connectionId);
        Map<NodeInfo, Future<List<Snapshot>>> futures = new LinkedHashMap<>();
        for (NodeInfo n : nodes) futures.put(n, executor.submit(() -> Snapshots.list(new Beans(session(readJmx, cfg, s, n)), n.address())));
        List<Snapshot> out = new ArrayList<>();
        List<NodeError> errors = new ArrayList<>();
        futures.forEach((n, f) -> {
            try {
                out.addAll(f.get(VIEW_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                f.cancel(true);
                errors.add(new NodeError(n.address(), message(e)));
            }
        });
        return new SnapshotList(out, errors);
    }

    public Job takeSnapshot(String connectionId, Snapshots.Create c, ActionGuard.Confirmation confirmation) {
        Snapshots.validate(c);
        ConnectionConfig cfg = jmxConnection(connectionId);
        List<NodeInfo> nodes = resolve(topology.info(connectionId).nodes(), c.nodes());
        String what = !c.tables().isEmpty() ? String.join(", ", c.tables())
                : c.keyspaces().isEmpty() ? "all keyspaces" : String.join(", ", c.keyspaces());
        String title = "Snapshot '" + c.tag() + "' of " + what + " on " + label(nodes);
        List<String> warnings = new ArrayList<>();
        warnings.add("Snapshots are hard links: they cost no space now, but keep deleted and compacted data on disk until cleared.");
        if (c.skipFlush()) warnings.add("Skip flush: data still in memtables is not in the snapshot.");
        guard.check(cfg, new ActionGuard.Action(CATEGORY, title, nodes.stream().map(n -> Snapshots.preview(c, n.address())).toList(),
                warnings, false, nodeField(nodes)), confirmation);
        return submit(connectionId, "snapshot", title, nodes, false, null, (n, b, ctx, progress) -> {
            Snapshots.take(b, c);
            return "snapshot '" + c.tag() + "' taken";
        });
    }

    public Job clearSnapshot(String connectionId, Snapshots.Clear c, ActionGuard.Confirmation confirmation) {
        Snapshots.validate(c);
        ConnectionConfig cfg = jmxConnection(connectionId);
        List<NodeInfo> nodes = resolve(topology.info(connectionId).nodes(), c.nodes());
        String what = (c.all() ? "ALL snapshots" : "snapshot '" + c.tag() + "'")
                + (c.keyspaces().isEmpty() ? "" : " of " + String.join(", ", c.keyspaces()));
        String title = "Clear " + what + " on " + label(nodes);
        List<String> warnings = List.of(c.all() ? "Deletes every snapshot on the selected nodes, including backups' snapshots."
                : "Deletes the snapshot's files; it cannot be restored from afterwards.");
        guard.check(cfg, new ActionGuard.Action(CATEGORY, title, nodes.stream().map(n -> Snapshots.preview(c, n.address())).toList(),
                warnings, true, nodeField(nodes)), confirmation);
        return submit(connectionId, "clearsnapshot", title, nodes, false, null, (n, b, ctx, progress) -> {
            Snapshots.clear(b, c);
            return "cleared";
        });
    }

    // ---- job runner over nodes ------------------------------------------------------------------

    @FunctionalInterface
    interface NodeTask {
        String run(NodeInfo node, Beans beans, JobContext ctx, DoubleConsumer progress) throws Exception;
    }

    /**
     * One job, the nodes one after another; progress = finished nodes + the current node's fraction.
     * {@code stop} (optional) stops the operation on the node running it when the job is cancelled.
     */
    private Job submit(String connectionId, String kind, String title, List<NodeInfo> nodes, boolean continueOnError,
                       Consumer<NodeInfo> stop, NodeTask task) {
        ConnectionConfig cfg = connections.get(connectionId);
        Map<String, String> s = secrets.apply(connectionId);
        return jobs.submit(new JobService.Spec(connectionId, kind, title, nodeField(nodes), CATEGORY, true), ctx -> {
            AtomicReference<NodeInfo> current = new AtomicReference<>();
            if (stop != null) {
                ctx.onCancel(() -> {
                    NodeInfo cur = current.get();
                    if (cur != null) {
                        ctx.log("[" + cur.address() + "] stopping");
                        stop.accept(cur);
                    }
                });
            }
            List<NodeResult> results = new ArrayList<>();
            String firstError = null;
            int failed = 0;
            for (int i = 0; i < nodes.size(); i++) {
                ctx.checkCancelled();
                NodeInfo n = nodes.get(i);
                current.set(n);
                final int idx = i;
                final int count = nodes.size();
                ctx.progress(idx / (double) count, "Node " + (idx + 1) + " of " + count + ": " + n.address());
                ctx.log("[" + n.address() + "] starting");
                long t0 = System.currentTimeMillis();
                try {
                    Beans b = new Beans(session(opsJmx, cfg, s, n));
                    String res = task.run(n, b, ctx, f -> ctx.progress((idx + f) / count, null));
                    long ms = System.currentTimeMillis() - t0;
                    results.add(new NodeResult(n.address(), "OK", ms, res));
                    ctx.log("[" + n.address() + "] " + res + " (" + seconds(ms) + ")");
                } catch (CancellationException | InterruptedException e) {
                    ctx.log("[" + n.address() + "] cancelled");
                    throw e;
                } catch (Exception e) {
                    if (ctx.cancelled()) throw new CancellationException("cancelled");
                    long ms = System.currentTimeMillis() - t0;
                    String m = message(e);
                    results.add(new NodeResult(n.address(), "FAILED", ms, m));
                    ctx.log("[" + n.address() + "] FAILED: " + m);
                    failed++;
                    if (firstError == null) firstError = n.address() + ": " + m;
                    if (!continueOnError) break;
                }
            }
            current.set(null);
            if (failed > 0) {
                int skipped = nodes.size() - results.size();
                throw new OpsException(failed + " of " + nodes.size() + " node(s) failed" + (skipped > 0 ? ", " + skipped
                        + " not run" : "") + " - " + firstError);
            }
            ctx.progress(1.0, "Done on " + nodes.size() + " node(s)");
            return results;
        });
    }

    private static String seconds(long ms) {
        return String.format(java.util.Locale.ROOT, "%.1f s", ms / 1000.0);
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** The connection, with 409 when its JMX method cannot run operations. */
    private ConnectionConfig jmxConnection(String connectionId) {
        ConnectionConfig cfg = connections.get(connectionId);
        JmxMethod m = cfg.jmx().method();
        if (m != JmxMethod.SSH_TUNNEL && m != JmxMethod.DIRECT) {
            throw new ApiException(409, "jmx_required", "Operations need JMX; this connection reaches nodes via " + m
                    + ". Set JMX to SSH tunnel or direct (Edit connection → JMX).");
        }
        return cfg;
    }

    static List<NodeInfo> resolve(List<NodeInfo> all, List<String> wanted) {
        if (wanted == null || wanted.isEmpty()) throw ApiException.badRequest("nodes is required (one or more node addresses)");
        List<NodeInfo> out = new ArrayList<>();
        for (String w : wanted) {
            NodeInfo n = all.stream().filter(x -> x.address().equals(w) || w.equals(x.hostId())).findFirst()
                    .orElseThrow(() -> ApiException.badRequest("Unknown node '" + w + "'; not part of this cluster"));
            if (!out.contains(n)) out.add(n);
        }
        return out;
    }

    private static NodeInfo firstUp(List<NodeInfo> all) {
        return all.stream().sorted(Comparator.comparing((NodeInfo n) -> !"UP".equals(n.state()))).findFirst()
                .orElseThrow(() -> new ApiException(503, "no_nodes", "The cluster has no known nodes"));
    }

    static String label(List<NodeInfo> nodes) {
        return nodes.size() == 1 ? nodes.get(0).address() : nodes.size() + " nodes";
    }

    static String nodeField(List<NodeInfo> nodes) {
        return String.join(",", nodes.stream().map(NodeInfo::address).toList());
    }

    private static javax.management.MBeanServerConnection session(JmxAccess jmx, ConnectionConfig cfg, Map<String, String> s,
                                                                 NodeInfo n) {
        try {
            return jmx.session(cfg, s, new NodeEndpoint(n.hostId(), n.address(), n.datacenter(), n.rack(), n.version())).mbeans();
        } catch (UnsupportedOperationException e) {
            throw new OpsException("JMX is not available for " + n.address() + ": " + e.getMessage());
        }
    }

    private <T> T call(Callable<T> task, long timeoutMs, String node) {
        Future<T> f = executor.submit(task);
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new ApiException(504, "timeout", "Reading " + node + " took longer than " + timeoutMs / 1000 + " s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "interrupted", "Interrupted");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof ApiException ae) throw ae;
            throw new ApiException(502, "jmx_failed", node + ": " + message(c));
        }
    }

    /** A user-readable message for a node failure (no stack traces). */
    static String message(Throwable e) {
        Throwable t = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        if (t instanceof OpsException || t instanceof JmxUnavailableException || t instanceof ApiException) return t.getMessage();
        if (t instanceof UncheckedIOException u) return "JMX connection lost: " + Beans.message(u.getCause());
        if (t instanceof TimeoutException) return "timed out";
        return Beans.message(t);
    }
}
