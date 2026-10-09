package com.cassandrastudio.engine.gclog;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.cql.ClusterService.NodeInfo;
import com.cassandrastudio.engine.jmx.JmxAccess;
import com.cassandrastudio.engine.jmx.NodeEndpoint;
import com.cassandrastudio.engine.jobs.Job;
import com.cassandrastudio.engine.jobs.JobService;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.JmxMethod;
import com.cassandrastudio.engine.ssh.SshAccessException;
import com.cassandrastudio.engine.util.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.management.ObjectName;

/**
 * GC log analysis (GCL-1..4): discovers and fetches logs from nodes over SSH (as jobs), parses
 * uploads, and keeps the last few parsed logs per connection in memory so the UI can re-analyse
 * any time window without fetching again. Read-only towards the cluster.
 */
public final class GcLogService implements AutoCloseable {
    /** Default cap on what one fetch reads from a node. */
    public static final long DEFAULT_MAX_BYTES = 200L * 1024 * 1024;
    public static final long MAX_MAX_BYTES = 1024L * 1024 * 1024;
    /** Upload cap, uncompressed. */
    public static final long MAX_UPLOAD_BYTES = 1024L * 1024 * 1024;
    static final int KEEP_PER_CONNECTION = 6;

    private final Engine engine;
    private final ExecutorService jmxReads = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "gclog-jmx");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, LinkedHashMap<String, Analysis>> analyses = new ConcurrentHashMap<>();

    record Analysis(String id, String name, GcReport.Source source, long createdAtMs, GcLog log) {}

    /** One stored analysis, as the list returns it. */
    public record AnalysisInfo(String id, String name, String sourceKind, String node, long createdAtMs,
                               String collector, String format, int events, long bytes, List<String> warnings) {}

    public GcLogService(Engine engine) {
        this.engine = engine;
    }

    // ---- discovery ------------------------------------------------------------------------

    public GcLogFiles.Discovery discover(String connectionId, String node) {
        ConnectionConfig cfg = engine.connections.get(connectionId);
        if (node == null || node.isBlank()) throw ApiException.badRequest("node is required");
        NodeInfo n = node(connectionId, node);
        Map<String, String> secrets = engine.secretsFor(connectionId);
        List<String> args = List.of();
        String java = null, note = null;
        JmxMethod method = cfg.jmx() == null ? JmxMethod.NONE : cfg.jmx().method();
        if (method == JmxMethod.SSH_TUNNEL || method == JmxMethod.DIRECT) {
            Future<String[]> f = jmxReads.submit(() -> {
                var mb = engine.jmx.session(cfg, secrets, endpoint(n)).mbeans();
                ObjectName rt = new ObjectName("java.lang:type=Runtime");
                String[] a = (String[]) mb.getAttribute(rt, "InputArguments");
                String v = String.valueOf(mb.getAttribute(rt, "SpecVersion"));
                String[] out = Arrays.copyOf(a, a.length + 1);
                out[a.length] = v;
                return out;
            });
            try {
                String[] r = f.get(20, TimeUnit.SECONDS);
                args = List.of(Arrays.copyOf(r, r.length - 1));
                java = r[r.length - 1];
            } catch (Exception e) {
                f.cancel(true);
                note = "JVM options could not be read over JMX (" + message(e) + "); searched the usual paths.";
            }
        } else {
            note = "JMX is not available for this connection; searched the usual paths.";
        }
        try {
            return GcLogFiles.discover(shell(cfg, secrets, n.address()),
                    n.address(), args, java, note);
        } catch (SshAccessException e) {
            throw new ApiException(502, "ssh_failed", "SSH to " + n.address() + " failed: " + e.getMessage()
                    + ". Check the connection's SSH settings, or upload the log file instead.");
        }
    }

    // ---- fetch (job) and upload -------------------------------------------------------------

    /** @param javaVersion the node's Java version from {@link #discover}, for logs that do not print it, or null */
    public Job fetch(String connectionId, String node, List<String> paths, Long maxBytes, String javaVersion) {
        ConnectionConfig cfg = engine.connections.get(connectionId);
        if (paths == null || paths.isEmpty()) throw ApiException.badRequest("Select at least one file");
        if (paths.size() > 100) throw ApiException.badRequest("At most 100 files at a time");
        for (String p : paths) {
            String err = GcLogFiles.checkPath(p);
            if (err != null) throw ApiException.badRequest("Cannot read " + p + ": " + err);
        }
        long cap = maxBytes == null ? DEFAULT_MAX_BYTES : maxBytes;
        if (cap < 1024 * 1024 || cap > MAX_MAX_BYTES) throw ApiException.badRequest("maxMB must be between 1 and 1024");
        NodeInfo n = node(connectionId, node);
        Map<String, String> secrets = engine.secretsFor(connectionId);
        List<String> files = List.copyOf(new java.util.LinkedHashSet<>(paths));
        String title = "Load GC logs from " + n.address();
        return engine.jobs.submit(new JobService.Spec(connectionId, "gclog-fetch", title, n.address(), null, true), ctx -> {
            GcLogParser parser = new GcLogParser();
            GcLogFiles.Fetched got;
            try {
                got = GcLogFiles.fetch(shell(cfg, secrets, n.address()),
                        n.address(), files, cap, parser, ctx);
            } catch (SshAccessException e) {
                throw new IllegalStateException("SSH to " + n.address() + " failed: " + e.getMessage(), e);
            }
            ctx.progress(0.95, "Analysing");
            GcLog log = parser.finish();
            if (log.jvmVersion == null && javaVersion != null && javaVersion.matches("[0-9][0-9.]*")) log.jvmVersion = javaVersion;
            requireEvents(log, files.size() == 1 ? files.get(0) : files.size() + " files");
            String name = n.address() + " " + shortName(got.files().get(got.files().size() - 1).path())
                    + (got.files().size() > 1 ? " +" + (got.files().size() - 1) : "");
            Analysis a = store(connectionId, name, new GcReport.Source("ssh", n.address(), got.files()), log);
            ctx.log(log.events.size() + " events, collector " + (log.collector == null ? "unknown" : log.collector));
            return Map.of("analysisId", a.id(), "events", log.events.size());
        });
    }

    public AnalysisInfo upload(String connectionId, String name, InputStream body) {
        engine.connections.get(connectionId); // 404 for an unknown connection
        if (name == null || name.isBlank()) throw ApiException.badRequest("name is required");
        String clean = name.replaceAll("[\\r\\n\\t]", " ").strip();
        if (clean.length() > 200) clean = clean.substring(0, 200);
        GcLogParser parser = new GcLogParser();
        List<GcReport.SourceFile> files;
        try {
            files = GcLogUpload.read(body, clean, MAX_UPLOAD_BYTES, parser);
        } catch (GcLogUpload.TooLargeException e) {
            throw new ApiException(413, "too_large", e.getMessage());
        } catch (IOException e) {
            throw ApiException.badRequest("Cannot read " + clean + ": " + message(e));
        }
        GcLog log = parser.finish();
        if (log.lines == 0) throw ApiException.badRequest("The file " + clean + " is empty");
        requireEvents(log, clean);
        return info(store(connectionId, clean, new GcReport.Source("upload", null, files), log));
    }

    private static void requireEvents(GcLog log, String what) {
        if (log.events.isEmpty() && log.safepoints == 0) {
            throw new ApiException(422, "not_a_gc_log", "No GC events found in " + what + ": is it a GC log "
                    + "(Java 8 -XX:+PrintGCDetails or Java 9+ -Xlog:gc*)?");
        }
    }

    // ---- analyses ---------------------------------------------------------------------------

    public List<AnalysisInfo> list(String connectionId) {
        engine.connections.get(connectionId);
        List<AnalysisInfo> out = new ArrayList<>();
        LinkedHashMap<String, Analysis> m = analyses.get(connectionId);
        if (m != null) {
            synchronized (m) {
                m.values().forEach(a -> out.add(info(a)));
            }
        }
        return out.reversed();
    }

    public GcReport report(String connectionId, String analysisId, Double fromX, Double toX) {
        Analysis a = get(connectionId, analysisId);
        if (fromX != null && toX != null && toX < fromX) throw ApiException.badRequest("toX must not be before fromX");
        return GcAnalyzer.analyze(a.log(), a.id(), a.name(), a.source(), a.createdAtMs(), fromX, toX);
    }

    public void delete(String connectionId, String analysisId) {
        get(connectionId, analysisId);
        LinkedHashMap<String, Analysis> m = analyses.get(connectionId);
        synchronized (m) {
            m.remove(analysisId);
        }
    }

    /** Drops a connection's analyses (disconnect, edit, delete). */
    public void drop(String connectionId) {
        analyses.remove(connectionId);
    }

    private Analysis get(String connectionId, String analysisId) {
        LinkedHashMap<String, Analysis> m = analyses.get(connectionId);
        Analysis a = null;
        if (m != null) {
            synchronized (m) {
                a = m.get(analysisId);
            }
        }
        if (a == null) throw ApiException.notFound("GC log analysis " + analysisId);
        return a;
    }

    private Analysis store(String connectionId, String name, GcReport.Source source, GcLog log) {
        Analysis a = new Analysis(UUID.randomUUID().toString(), name, source, System.currentTimeMillis(), log);
        LinkedHashMap<String, Analysis> m = analyses.computeIfAbsent(connectionId, k -> new LinkedHashMap<>());
        synchronized (m) {
            m.put(a.id(), a);
            while (m.size() > KEEP_PER_CONNECTION) m.remove(m.keySet().iterator().next());
        }
        return a;
    }

    private static AnalysisInfo info(Analysis a) {
        return new AnalysisInfo(a.id(), a.name(), a.source().kind(), a.source().node(), a.createdAtMs(), a.log().collector,
                a.log().format, a.log().events.size(), a.log().bytes, a.log().warnings);
    }

    // ---- helpers ----------------------------------------------------------------------------

    private NodeInfo node(String connectionId, String address) {
        if (address == null || address.isBlank()) throw ApiException.badRequest("node is required");
        List<NodeInfo> nodes;
        try {
            nodes = engine.topology.info(connectionId).nodes();
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ApiException(503, "not_connected", "Cannot read the cluster's nodes: " + message(e));
        }
        return nodes.stream().filter(n -> address.equals(n.address())).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Unknown node " + address + " for this connection"));
    }

    /**
     * Commands over the node's cached SSH session. When the server refuses a new channel (OpenSSH
     * MaxSessions reached on a long-lived session), the session is reopened and the command retried once.
     */
    private GcLogFiles.Shell shell(ConnectionConfig cfg, Map<String, String> secrets, String host) {
        return (cmd, timeout, max) -> {
            try {
                return engine.shell.exec(cfg, secrets, host, cmd, timeout, max);
            } catch (SshAccessException e) {
                if (e.getMessage() == null || !e.getMessage().contains("open failed")) throw e;
                engine.shell.closeConnection(cfg.id());
                return engine.shell.exec(cfg, secrets, host, cmd, timeout, max);
            }
        };
    }

    private static NodeEndpoint endpoint(NodeInfo n) {
        return new NodeEndpoint(n.hostId(), n.address(), n.datacenter(), n.rack(), n.version());
    }

    private static String shortName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    static String message(Throwable t) {
        Throwable c = t;
        while ((c instanceof java.util.concurrent.ExecutionException || c.getMessage() == null) && c.getCause() != null) {
            c = c.getCause();
        }
        if (c instanceof java.util.concurrent.TimeoutException) return "timed out";
        if (c instanceof JmxAccess.JmxUnavailableException) return c.getMessage();
        String m = c.getMessage();
        return m == null || m.isBlank() ? c.getClass().getSimpleName() : m;
    }

    @Override
    public void close() {
        jmxReads.shutdownNow();
        analyses.clear();
    }
}
