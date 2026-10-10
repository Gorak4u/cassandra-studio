package com.cassandrastudio.engine.jobs;

import com.cassandrastudio.engine.audit.Actor;
import com.cassandrastudio.engine.audit.AuditLog;
import com.cassandrastudio.engine.conn.ConnectionRepository;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.util.ApiException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs long tasks in the background (one virtual thread each) with progress, a bounded log and
 * cancel. Jobs live in memory; the newest {@link #MAX_FINISHED} finished ones are kept. Start and
 * end are written to the audit log when the job was started with an audit category.
 *
 * <p>Callers check permissions (ActionGuard) <em>before</em> {@link #submit}: the job runner
 * only runs what it is given.
 */
public final class JobService implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(JobService.class);
    public static final int MAX_LOG_LINES = 500;
    static final int MAX_FINISHED = 200;

    private final ConnectionRepository connections;
    private final AuditLog audit;
    private final LongSupplier clock;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Running> jobs = new LinkedHashMap<>();

    public JobService(ConnectionRepository connections, AuditLog audit) {
        this(connections, audit, System::currentTimeMillis);
    }

    JobService(ConnectionRepository connections, AuditLog audit, LongSupplier clock) {
        this.connections = connections;
        this.audit = audit;
        this.clock = clock;
    }

    /** What to start. {@code auditCategory} null = do not audit (read-only tasks). */
    public record Spec(String connectionId, String kind, String title, String node, String auditCategory,
                       boolean cancellable) {}

    public Job submit(Spec spec, JobTask task) {
        ConnectionConfig conn = spec.connectionId() == null ? null : connections.get(spec.connectionId());
        Running r = new Running(UUID.randomUUID().toString(), spec, conn, clock.getAsLong());
        synchronized (this) {
            jobs.put(r.id, r);
            prune();
        }
        String actor = Actor.current(); // the job is audited as the user who started it
        r.future = executor.submit(() -> Actor.as(actor, () -> run(r, task)));
        return r.snapshot();
    }

    public Job get(String jobId) {
        Running r;
        synchronized (this) {
            r = jobs.get(jobId);
        }
        if (r == null) throw ApiException.notFound("job " + jobId);
        return r.snapshot();
    }

    /** Newest first; {@code connectionId} null = all. */
    public synchronized List<Job> list(String connectionId) {
        List<Job> out = new ArrayList<>();
        for (Running r : jobs.values()) {
            if (connectionId == null || connectionId.equals(r.spec.connectionId())) out.add(r.snapshot());
        }
        out.sort(Comparator.comparingLong(Job::createdAtMs).reversed());
        return out;
    }

    public Job cancel(String jobId) {
        Running r;
        synchronized (this) {
            r = jobs.get(jobId);
        }
        if (r == null) throw ApiException.notFound("job " + jobId);
        if (!r.spec.cancellable()) throw new ApiException(409, "not_cancellable", "This job cannot be cancelled");
        r.requestCancel();
        return r.snapshot();
    }

    /** Waits for a job to finish (tests and callers that need the result); returns its final state. */
    public Job await(String jobId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Job j = get(jobId);
            if (j.done()) return j;
            Thread.sleep(20);
        }
        return get(jobId);
    }

    private void run(Running r, JobTask task) {
        r.start(clock.getAsLong());
        Job.State state;
        Object result = null;
        String error = null;
        try {
            r.checkCancelled();
            result = task.run(r);
            state = r.cancelled() ? Job.State.CANCELLED : Job.State.SUCCEEDED;
        } catch (CancellationException | InterruptedException e) {
            state = Job.State.CANCELLED;
        } catch (Throwable e) {
            error = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
            LOG.info("job {} ({}) failed: {}", r.id, r.spec.title(), error);
            state = Job.State.FAILED;
        }
        long now = clock.getAsLong();
        // Audit before the job reads as finished, so a caller that sees it done also sees the audit entry.
        if (r.spec.auditCategory() != null && r.conn != null) {
            long ms = now - (r.startedAt == null ? r.createdAt : r.startedAt);
            AuditLog.Outcome outcome = state == Job.State.SUCCEEDED ? AuditLog.Outcome.SUCCESS : AuditLog.Outcome.FAILED;
            try {
                audit.record(r.conn, r.spec.node(), r.spec.auditCategory(), r.spec.title(),
                        "job " + r.id + " " + state + " after " + ms + " ms", outcome, error);
            } catch (RuntimeException e) {
                LOG.warn("could not audit job {}: {}", r.id, e.getMessage());
            }
        }
        r.finish(state, state == Job.State.SUCCEEDED ? result : null, error, now);
    }

    private void prune() {
        long finished = jobs.values().stream().filter(x -> x.done).count();
        var it = jobs.values().iterator();
        while (finished > MAX_FINISHED && it.hasNext()) {
            if (it.next().done) {
                it.remove();
                finished--;
            }
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            jobs.values().forEach(Running::requestCancel);
        }
        executor.shutdownNow();
    }

    /** Mutable job state; all access under its own monitor. */
    private static final class Running implements JobContext {
        final String id;
        final Spec spec;
        final ConnectionConfig conn;
        final long createdAt;
        final Deque<String> log = new ArrayDeque<>();
        final List<Runnable> cancelActions = new ArrayList<>();
        volatile Future<?> future;
        volatile boolean cancel;
        volatile boolean done;
        Job.State state = Job.State.QUEUED;
        Double progress;
        String message;
        Object result;
        String error;
        Long startedAt;
        Long finishedAt;

        Running(String id, Spec spec, ConnectionConfig conn, long createdAt) {
            this.id = id;
            this.spec = spec;
            this.conn = conn;
            this.createdAt = createdAt;
        }

        synchronized void start(long now) {
            if (state == Job.State.QUEUED) {
                state = Job.State.RUNNING;
                startedAt = now;
            }
        }

        synchronized void finish(Job.State s, Object res, String err, long now) {
            state = s;
            result = res;
            error = err;
            finishedAt = now;
            if (s == Job.State.SUCCEEDED) progress = 1.0;
            done = true;
        }

        void requestCancel() {
            List<Runnable> actions;
            synchronized (this) {
                if (done || cancel) return;
                cancel = true;
                message = "Cancelling…";
                actions = List.copyOf(cancelActions);
            }
            for (Runnable a : actions) {
                try {
                    a.run();
                } catch (RuntimeException e) {
                    LOG.info("cancel action of job {} failed: {}", id, e.getMessage());
                }
            }
            Future<?> f = future;
            if (f != null) f.cancel(true);
        }

        @Override
        public synchronized void progress(Double fraction, String msg) {
            if (fraction != null) progress = Math.max(0, Math.min(1, fraction));
            if (msg != null) message = msg;
        }

        @Override
        public synchronized void log(String line) {
            log.addLast(line);
            while (log.size() > MAX_LOG_LINES) log.removeFirst();
        }

        @Override
        public boolean cancelled() {
            return cancel;
        }

        @Override
        public synchronized void onCancel(Runnable action) {
            cancelActions.add(action);
        }

        synchronized Job snapshot() {
            return new Job(id, spec.connectionId(), spec.kind(), spec.title(), spec.node(), state, progress, message,
                    List.copyOf(log), result, error, createdAt, startedAt, finishedAt, spec.cancellable());
        }
    }
}
