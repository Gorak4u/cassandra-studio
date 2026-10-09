package com.cassandrastudio.engine.bulk;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.cql.SessionManager;
import com.cassandrastudio.engine.guard.ActionGuard;
import com.cassandrastudio.engine.jobs.Job;
import com.cassandrastudio.engine.jobs.JobService;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.util.ApiException;
import com.datastax.oss.driver.api.core.CqlSession;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bulk unload and load (BLK-1, BLK-2) as jobs. Unload is read-only (audited under "bulk" on
 * PROD); load goes through the ActionGuard ("bulk") with the INSERT and the source as preview,
 * except a dry run, which writes nothing.
 */
public final class BulkService {
    private static final int MAX_STATS = 200;

    private final Engine engine;
    /** Live counters per job id, newest last; bounded. */
    private final Map<String, BulkStats> stats = new LinkedHashMap<>();
    /** Running bulk jobs: job id -> connection id, cancelled on disconnect. */
    private final Map<String, String> running = new ConcurrentHashMap<>();

    public BulkService(Engine engine) {
        this.engine = engine;
    }

    public Map<String, Object> defaults() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("downloadsDir", BulkFiles.downloadsDir().toString());
        m.put("separator", File.separator);
        return m;
    }

    public Job unload(String connectionId, JsonNode body) {
        ConnectionConfig cfg = engine.connections.get(connectionId);
        BulkOptions.Unload o = BulkOptions.unload(body);
        BulkFiles.checkTarget(o.path(), o.overwrite());
        CqlSession session;
        String perRequestKs = null;
        if (o.tableMode() || o.keyspace() == null) {
            session = engine.sessions.session(connectionId);
        } else {
            SessionManager.SessionAndKeyspace sk = engine.sessions.sessionFor(connectionId, o.keyspace());
            session = sk.session();
            perRequestKs = sk.perRequestKeyspace();
        }
        String what = o.tableMode() ? o.keyspace() + "." + o.table() : "query";
        BulkStats st = new BulkStats("unload", o.path().toString(), what);
        Unloader u = new Unloader(session, perRequestKs, o, st);
        String title = "Unload " + what + " to " + o.path().getFileName();
        // Reading data is not guarded, but taking PROD data off the cluster is audited.
        JobService.Spec spec = new JobService.Spec(connectionId, "bulk-unload", title, null, cfg.isProd() ? "bulk" : null, true);
        return submit(spec, st, ctx -> u.run(ctx));
    }

    public Map<String, Object> preview(String connectionId, JsonNode body) {
        BulkOptions.Load o = BulkOptions.load(body, false);
        return Loader.inspect(engine.sessions.session(connectionId), o);
    }

    public Job load(String connectionId, JsonNode body, ActionGuard.Confirmation confirmation) {
        ConnectionConfig cfg = engine.connections.get(connectionId);
        BulkOptions.Load o = BulkOptions.load(body);
        CqlSession session = engine.sessions.session(connectionId);
        String target = o.keyspace() + "." + o.table();
        BulkStats st = new BulkStats("load", o.path().toString(), target);
        Loader loader = new Loader(session, o, st);
        String summary = (o.dryRun() ? "Validate " : "Load ") + o.path().getFileName() + " into " + target;
        if (!o.dryRun()) {
            List<String> warnings = new ArrayList<>(loader.warnings());
            if (loader.estimatedRows() != null) warnings.add(0, String.format("About %,d rows will be written.", loader.estimatedRows()));
            engine.guard.check(cfg, new ActionGuard.Action("bulk", summary, loader.preview(), warnings, false, null), confirmation);
        }
        JobService.Spec spec = new JobService.Spec(connectionId, "bulk-load", summary, null, o.dryRun() ? null : "bulk", true);
        return submit(spec, st, loader::run);
    }

    private Job submit(JobService.Spec spec, BulkStats st, com.cassandrastudio.engine.jobs.JobTask task) {
        String[] id = new String[1];
        Job job = engine.jobs.submit(spec, ctx -> {
            try {
                return task.run(ctx);
            } finally {
                st.finish();
                if (id[0] != null) running.remove(id[0]);
            }
        });
        id[0] = job.id();
        if (!job.done()) running.put(job.id(), spec.connectionId());
        synchronized (stats) {
            stats.put(job.id(), st);
            while (stats.size() > MAX_STATS) stats.remove(stats.keySet().iterator().next());
        }
        return job;
    }

    /** Recent bulk jobs of a connection, newest first, each with its live counters. */
    public List<Map<String, Object>> jobs(String connectionId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Job j : engine.jobs.list(connectionId)) {
            if (!j.kind().startsWith("bulk-")) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("job", j);
            BulkStats s;
            synchronized (stats) {
                s = stats.get(j.id());
            }
            m.put("stats", s == null ? null : s.snapshot());
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> stats(String jobId) {
        BulkStats s;
        synchronized (stats) {
            s = stats.get(jobId);
        }
        if (s == null) throw ApiException.notFound("bulk job " + jobId);
        return s.snapshot();
    }

    /** Stops the connection's running bulk jobs (its session is about to close). */
    public void disconnect(String connectionId) {
        running.forEach((jobId, conn) -> {
            if (conn.equals(connectionId)) {
                try {
                    engine.jobs.cancel(jobId);
                } catch (RuntimeException e) {
                    // already finished
                }
            }
        });
    }
}
