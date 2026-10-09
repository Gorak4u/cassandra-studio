package com.cassandrastudio.engine.config;

import com.cassandrastudio.engine.config.ConfigModel.DriftReport;
import com.cassandrastudio.engine.config.ConfigModel.HieraOptions;
import com.cassandrastudio.engine.config.ConfigModel.HieraSettings;
import com.cassandrastudio.engine.config.ConfigModel.HieraStatus;
import com.cassandrastudio.engine.config.ConfigModel.NodeConfig;
import com.cassandrastudio.engine.config.ConfigModel.Snapshot;
import com.cassandrastudio.engine.conn.ConnectionRepository;
import com.cassandrastudio.engine.cql.ClusterService.NodeInfo;
import com.cassandrastudio.engine.jobs.Job;
import com.cassandrastudio.engine.jobs.JobService;
import com.cassandrastudio.engine.metrics.Topology;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.ApiException;
import com.cassandrastudio.engine.util.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Effective config per node and drift (CFG-1/2). Collecting reads every node in parallel as a
 * job (read-only, not audited); the last snapshot per connection is kept in memory and the drift
 * report is computed from it on request, with the Hiera comparison settings stored per
 * connection in the settings table ({@code config.hiera/<connectionId>}).
 */
public final class ConfigService implements AutoCloseable {
    public static final String DEFAULT_REPO = "/home/user/cassandra-control-repo";
    static final String HIERA_KEY = "config.hiera/";
    static final long NODE_TIMEOUT_SEC = 90;

    /** Reads one node; a seam so tests can collect without a cluster. */
    public interface Collector {
        NodeConfig collect(ConnectionConfig cfg, Map<String, String> secrets, NodeInfo node);
    }

    private final Database db;
    private final ConnectionRepository connections;
    private final Topology topology;
    private final JobService jobs;
    private final Function<String, Map<String, String>> secrets;
    private final Function<String, Collector> collectors;
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "config-collect");
        t.setDaemon(true);
        return t;
    });

    public ConfigService(Database db, ConnectionRepository connections, Topology topology, JobService jobs,
                         Function<String, Map<String, String>> secrets, Function<String, Collector> collectors) {
        this.db = db;
        this.connections = connections;
        this.topology = topology;
        this.jobs = jobs;
        this.secrets = secrets;
        this.collectors = collectors;
    }

    /** Starts collecting every node's config; the job's result is a short summary. */
    public Job collect(String connectionId) {
        ConnectionConfig cfg = connections.get(connectionId);
        List<NodeInfo> nodes = topology.info(connectionId).nodes();
        if (nodes.isEmpty()) throw ApiException.badRequest("The driver knows no nodes for this cluster");
        return jobs.submit(new JobService.Spec(connectionId, "config-collect", "Collect effective config ("
                + nodes.size() + " node" + (nodes.size() == 1 ? "" : "s") + ")", null, null, true), ctx -> {
            Map<String, String> sec = secrets.apply(connectionId);
            Collector c = collectors.apply(connectionId);
            ctx.progress(0.0, "reading " + nodes.size() + " nodes");
            Map<NodeInfo, Future<NodeConfig>> futures = new LinkedHashMap<>();
            for (NodeInfo n : nodes) futures.put(n, pool.submit(() -> c.collect(cfg, sec, n)));
            ctx.onCancel(() -> futures.values().forEach(f -> f.cancel(true)));
            List<NodeConfig> out = new ArrayList<>();
            int done = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(NODE_TIMEOUT_SEC);
            for (Map.Entry<NodeInfo, Future<NodeConfig>> e : futures.entrySet()) {
                NodeInfo n = e.getKey();
                NodeConfig nc;
                try {
                    nc = e.getValue().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (TimeoutException te) {
                    e.getValue().cancel(true);
                    nc = failed(n, "timed out after " + NODE_TIMEOUT_SEC + " s");
                } catch (java.util.concurrent.CancellationException ce) {
                    throw ce;
                } catch (Exception ex) {
                    nc = failed(n, NodeCollector.message(ex));
                }
                out.add(nc);
                done++;
                ctx.log(n.address() + ": " + nc.settings().size() + " settings"
                        + (nc.notices().isEmpty() ? "" : "; " + String.join(" ", nc.notices())));
                ctx.progress((double) done / nodes.size(), done + " of " + nodes.size() + " nodes read");
                ctx.checkCancelled();
            }
            Snapshot snap = new Snapshot(System.currentTimeMillis(), out);
            snapshots.put(connectionId, snap);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("nodes", out.size());
            result.put("settings", out.stream().mapToInt(x -> x.settings().size()).sum());
            result.put("notices", out.stream().mapToInt(x -> x.notices().size()).sum());
            return result;
        });
    }

    private static NodeConfig failed(NodeInfo n, String why) {
        return new NodeConfig(n.address(), n.hostId(), n.datacenter(), n.rack(), n.version(), Map.of(), List.of(),
                List.of("Could not read this node: " + why));
    }

    public Snapshot snapshot(String connectionId) {
        Snapshot s = snapshots.get(connectionId);
        if (s == null) throw new ApiException(404, "not_collected", "No configuration collected yet: run Collect first");
        return s;
    }

    /** Stores a snapshot directly (tests). */
    void put(String connectionId, Snapshot s) {
        snapshots.put(connectionId, s);
    }

    public void forget(String connectionId) {
        snapshots.remove(connectionId);
    }

    public DriftReport drift(String connectionId, String scope, boolean onlyDifferences, boolean withHiera) {
        String sc = scope == null || scope.isBlank() ? Drift.SCOPE_CLUSTER : scope;
        if (!sc.equals(Drift.SCOPE_CLUSTER) && !sc.equals(Drift.SCOPE_DC)) {
            throw ApiException.badRequest("scope must be cluster or dc");
        }
        Snapshot snap = snapshot(connectionId);
        HieraSettings hs = hieraSettings(connectionId);
        Map<String, Map<String, Hiera.Expected>> expected = null;
        HieraStatus status = new HieraStatus(false, null, Map.of(), 0);
        if (withHiera && hs.enabled()) {
            try {
                Hiera h = Hiera.load(Path.of(hs.repoPath()));
                expected = new HashMap<>();
                Map<String, List<String>> layers = new LinkedHashMap<>();
                for (NodeConfig n : snap.nodes()) {
                    Map<String, String> vars = Hiera.variablesFor(factsFor(hs, n));
                    expected.put(n.address(), h.expected(vars));
                    layers.put(n.address(), h.layers(vars));
                }
                status = new HieraStatus(true, null, layers, h.mappings().size());
            } catch (IllegalArgumentException e) {
                status = new HieraStatus(true, e.getMessage(), Map.of(), 0);
            }
        }
        return Drift.report(snap, sc, onlyDifferences, expected, status);
    }

    /** The facts for one node: the stored ones, its DC when none is set, its certname if mapped. */
    static Map<String, String> factsFor(HieraSettings hs, NodeConfig n) {
        Map<String, String> f = new LinkedHashMap<>(hs.facts() == null ? Map.of() : hs.facts());
        if (f.get("datacenter") == null || f.get("datacenter").isBlank()) f.put("datacenter", n.datacenter());
        String cert = hs.certnames() == null ? null : hs.certnames().get(n.address());
        if (cert != null && !cert.isBlank()) f.put("certname", cert);
        return f;
    }

    // ---- Hiera settings -------------------------------------------------------------------

    public HieraSettings hieraSettings(String connectionId) {
        connections.get(connectionId);
        List<Map<String, Object>> rows = db.query("SELECT value FROM settings WHERE key=?", HIERA_KEY + connectionId);
        if (!rows.isEmpty()) {
            try {
                HieraSettings s = Json.MAPPER.readValue(String.valueOf(rows.get(0).get("value")), HieraSettings.class);
                return new HieraSettings(s.enabled(), s.repoPath() == null ? DEFAULT_REPO : s.repoPath(),
                        s.facts() == null ? Map.of() : s.facts(), s.certnames() == null ? Map.of() : s.certnames());
            } catch (Exception e) {
                // unreadable: fall back to defaults
            }
        }
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("product", "cassandra");
        return new HieraSettings(Files.isDirectory(Path.of(DEFAULT_REPO)), DEFAULT_REPO, facts, Map.of());
    }

    public HieraSettings setHieraSettings(String connectionId, HieraSettings s) {
        connections.get(connectionId);
        if (s.repoPath() == null || s.repoPath().isBlank()) throw ApiException.badRequest("repoPath is required");
        Path p = Path.of(s.repoPath().strip());
        if (!p.isAbsolute()) throw ApiException.badRequest("repoPath must be an absolute path");
        Map<String, String> facts = clean(s.facts(), "facts");
        Map<String, String> certs = clean(s.certnames(), "certnames");
        HieraSettings stored = new HieraSettings(s.enabled(), p.toString(), facts, certs);
        try {
            db.update("INSERT OR REPLACE INTO settings(key, value) VALUES (?, ?)", HIERA_KEY + connectionId,
                    Json.MAPPER.writeValueAsString(stored));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return stored;
    }

    private static Map<String, String> clean(Map<String, String> in, String what) {
        Map<String, String> out = new LinkedHashMap<>();
        if (in == null) return out;
        if (in.size() > 500) throw ApiException.badRequest(what + ": too many entries");
        in.forEach((k, v) -> {
            if (k == null || k.isBlank() || v == null || v.isBlank()) return;
            String key = k.strip();
            String val = v.strip();
            if (key.length() > 200 || val.length() > 200) throw ApiException.badRequest(what + ": entries are limited to 200 characters");
            if (val.contains("/") || val.contains("..") || val.contains("%{")) {
                throw ApiException.badRequest(what + "." + key + " must not contain '/', '..' or '%{'");
            }
            out.put(key, val);
        });
        return out;
    }

    public HieraOptions hieraOptions(String connectionId, String repoPath) {
        String path = repoPath == null || repoPath.isBlank() ? hieraSettings(connectionId).repoPath() : repoPath.strip();
        if (!Path.of(path).isAbsolute()) throw ApiException.badRequest("repoPath must be an absolute path");
        try {
            Hiera h = Hiera.load(Path.of(path));
            return new HieraOptions(path, true, null, h.factValues(), List.copyOf(h.variables()));
        } catch (IllegalArgumentException e) {
            return new HieraOptions(path, false, e.getMessage(), Map.of(), List.of());
        }
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
