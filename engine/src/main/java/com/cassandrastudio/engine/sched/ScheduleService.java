package com.cassandrastudio.engine.sched;

import com.cassandrastudio.engine.audit.Actor;
import com.cassandrastudio.engine.jobs.Job;
import com.cassandrastudio.engine.jobs.JobService;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.Json;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Stored schedules and the loop that fires them (SRV-5). In the desktop app schedules run while
 * Studio is open; Studio Server runs the same loop unattended. A run is skipped (not queued) while the
 * previous run of the same schedule is still going, and outside its window it waits for the next one.
 * {@link #setLeader} lets a server cluster make only one instance fire (SRV-7).
 */
public final class ScheduleService implements AutoCloseable {
    public static final long TICK_MS = 30_000;

    public record Run(long id, String scheduleId, long startedMs, String jobId, String outcome, String detail) {}

    private final Database db;
    private final JobService jobs;
    private final ZoneId zone;
    private final Map<String, ScheduledTask> types = new ConcurrentHashMap<>();
    private final ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "schedules");
        t.setDaemon(true);
        return t;
    });
    private volatile java.util.function.BooleanSupplier leader = () -> true;
    private volatile boolean started;

    public ScheduleService(Database db, JobService jobs, ZoneId zone) {
        this.db = db;
        this.jobs = jobs;
        this.zone = zone;
    }

    public void register(String type, ScheduledTask task) {
        if (types.putIfAbsent(type, task) != null) throw new IllegalStateException("Schedule type already registered: " + type);
    }

    public List<String> types() {
        return types.keySet().stream().sorted().toList();
    }

    /** Only this instance fires schedules while {@code isLeader} is true (Studio Server HA). */
    public void setLeader(java.util.function.BooleanSupplier isLeader) {
        this.leader = isLeader;
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        loop.scheduleWithFixedDelay(this::tickSafely, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    public List<Schedule> list(String connectionId) {
        String sql = "SELECT * FROM schedules" + (connectionId == null ? "" : " WHERE connection_id = ?") + " ORDER BY name";
        List<Schedule> out = new ArrayList<>();
        for (Map<String, Object> r : connectionId == null ? db.query(sql) : db.query(sql, connectionId)) out.add(fromRow(r));
        return out;
    }

    public Optional<Schedule> get(String id) {
        return db.query("SELECT * FROM schedules WHERE id = ?", id).stream().map(ScheduleService::fromRow).findFirst();
    }

    /** Creates (blank id) or replaces a schedule; recomputes its next run. */
    public Schedule save(Schedule s) {
        ScheduledTask task = types.get(s.type());
        if (task == null) throw new IllegalArgumentException("Unknown schedule type " + s.type() + "; known: " + types());
        if (s.everyMinutes() < 5) throw new IllegalArgumentException("A schedule must run at most every 5 minutes");
        parseTime(s.atTime(), "atTime");
        parseTime(s.windowStart(), "windowStart");
        parseTime(s.windowEnd(), "windowEnd");
        if ((s.windowStart() == null) != (s.windowEnd() == null)) {
            throw new IllegalArgumentException("Give both windowStart and windowEnd, or neither");
        }
        task.validate(s);
        String id = s.id() == null || s.id().isBlank() ? UUID.randomUUID().toString() : s.id();
        Optional<Schedule> old = get(id);
        Long last = old.map(Schedule::lastRunMs).orElse(null);
        Schedule saved = new Schedule(id, s.connectionId(), s.type(), s.name(), s.everyMinutes(), s.atTime(), s.windowStart(),
                s.windowEnd(), s.enabled(), s.params(), null, last, old.map(Schedule::lastOutcome).orElse(null),
                old.map(Schedule::lastJobId).orElse(null));
        saved = saved.withRun(nextRun(saved, System.currentTimeMillis()), last, saved.lastOutcome(), saved.lastJobId());
        db.update("INSERT OR REPLACE INTO schedules (id, connection_id, type, name, every_minutes, at_time, window_start, window_end,"
                        + " enabled, params_json, next_run_ms, last_run_ms, last_outcome, last_job_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                saved.id(), saved.connectionId(), saved.type(), saved.name(), saved.everyMinutes(), saved.atTime(),
                saved.windowStart(), saved.windowEnd(), saved.enabled() ? 1 : 0, Json.write(saved.params()), saved.nextRunMs(),
                saved.lastRunMs(), saved.lastOutcome(), saved.lastJobId());
        return saved;
    }

    public boolean delete(String id) {
        db.update("DELETE FROM schedule_runs WHERE schedule_id = ?", id);
        return db.update("DELETE FROM schedules WHERE id = ?", id) > 0;
    }

    /** Starts a run now, whatever the window, as if it were due. */
    public Job runNow(String id) {
        Schedule s = get(id).orElseThrow(() -> new IllegalArgumentException("No schedule " + id));
        return fire(s, System.currentTimeMillis());
    }

    public List<Run> runs(String scheduleId, int limit) {
        List<Run> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT * FROM schedule_runs WHERE schedule_id = ? ORDER BY id DESC LIMIT ?",
                scheduleId, limit)) {
            out.add(new Run(((Number) r.get("id")).longValue(), (String) r.get("schedule_id"),
                    ((Number) r.get("started_ms")).longValue(), (String) r.get("job_id"), (String) r.get("outcome"),
                    (String) r.get("detail")));
        }
        return out;
    }

    /** One pass of the loop; public for tests. Fires every enabled schedule whose next run is due. */
    public void tick(long nowMs) {
        if (!leader.getAsBoolean()) return;
        refreshFinished();
        for (Schedule s : list(null)) {
            if (!s.enabled() || s.nextRunMs() == null || s.nextRunMs() > nowMs) continue;
            if (s.lastJobId() != null && isRunning(s.lastJobId())) {
                record(s, nowMs, null, "SKIPPED", "previous run still running");
                updateRun(s.withRun(nextRun(s, nowMs), s.lastRunMs(), "SKIPPED", s.lastJobId()));
                continue;
            }
            if (!inWindow(s, nowMs)) {
                updateRun(s.withRun(nextRun(s, nowMs), s.lastRunMs(), s.lastOutcome(), s.lastJobId()));
                continue;
            }
            fire(s, nowMs);
        }
    }

    private Job fire(Schedule s, long nowMs) {
        ScheduledTask task = types.get(s.type());
        if (task == null) {
            record(s, nowMs, null, "FAILED", "schedule type " + s.type() + " is not available in this Studio");
            updateRun(s.withRun(nextRun(s, nowMs), nowMs, "FAILED", s.lastJobId()));
            throw new IllegalStateException("Schedule type " + s.type() + " is not available");
        }
        try {
            Job job = Actor.as("schedule: " + s.name(), () -> jobs.submit(task.spec(s), task.task(s)));
            record(s, nowMs, job.id(), "STARTED", null);
            updateRun(s.withRun(nextRun(s, nowMs), nowMs, "STARTED", job.id()));
            return job;
        } catch (RuntimeException e) {
            record(s, nowMs, null, "FAILED", e.getMessage());
            updateRun(s.withRun(nextRun(s, nowMs), nowMs, "FAILED", s.lastJobId()));
            throw e;
        }
    }

    /** Copies the final state of finished jobs onto their schedule and run rows. */
    private void refreshFinished() {
        for (Map<String, Object> r : db.query("SELECT id, schedule_id, job_id FROM schedule_runs WHERE outcome = 'STARTED'")) {
            String jobId = (String) r.get("job_id");
            Job job;
            try {
                job = jobs.get(jobId);
            } catch (RuntimeException gone) {
                job = null;
            }
            String state = job == null ? "UNKNOWN" : String.valueOf(job.state());
            if (job != null && isActive(state)) continue;
            String detail = job == null ? "job no longer known (Studio restarted)" : job.error();
            db.update("UPDATE schedule_runs SET outcome = ?, detail = ? WHERE id = ?", state, detail, r.get("id"));
            db.update("UPDATE schedules SET last_outcome = ? WHERE id = ? AND last_job_id = ?", state, r.get("schedule_id"), jobId);
        }
    }

    private boolean isRunning(String jobId) {
        try {
            return isActive(String.valueOf(jobs.get(jobId).state()));
        } catch (RuntimeException gone) {
            return false;
        }
    }

    private static boolean isActive(String state) {
        return state.equals("QUEUED") || state.equals("RUNNING");
    }

    private void record(Schedule s, long nowMs, String jobId, String outcome, String detail) {
        db.update("INSERT INTO schedule_runs (schedule_id, started_ms, job_id, outcome, detail) VALUES (?,?,?,?,?)",
                s.id(), nowMs, jobId, outcome, detail);
        db.update("DELETE FROM schedule_runs WHERE schedule_id = ? AND id NOT IN"
                + " (SELECT id FROM schedule_runs WHERE schedule_id = ? ORDER BY id DESC LIMIT 500)", s.id(), s.id());
    }

    private void updateRun(Schedule s) {
        db.update("UPDATE schedules SET next_run_ms = ?, last_run_ms = ?, last_outcome = ?, last_job_id = ? WHERE id = ?",
                s.nextRunMs(), s.lastRunMs(), s.lastOutcome(), s.lastJobId(), s.id());
    }

    /** The first run time after {@code afterMs}: aligned to {@code atTime} when set, else every N minutes from the last run. */
    long nextRun(Schedule s, long afterMs) {
        long every = s.everyMinutes() * 60_000L;
        LocalTime at = parseTime(s.atTime(), "atTime");
        if (at == null) {
            long base = s.lastRunMs() == null ? afterMs : s.lastRunMs();
            long next = base + every;
            while (next <= afterMs) next += every;
            return s.lastRunMs() == null ? afterMs + every : next;
        }
        ZonedDateTime anchor = LocalDateTime.of(Instant.ofEpochMilli(afterMs).atZone(zone).toLocalDate(), at).atZone(zone);
        long t = anchor.toInstant().toEpochMilli();
        while (t <= afterMs) t += every;
        return t;
    }

    boolean inWindow(Schedule s, long nowMs) {
        LocalTime from = parseTime(s.windowStart(), "windowStart");
        LocalTime to = parseTime(s.windowEnd(), "windowEnd");
        if (from == null || to == null) return true;
        LocalTime now = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalTime();
        return from.isBefore(to) ? !now.isBefore(from) && now.isBefore(to) : !now.isBefore(from) || now.isBefore(to);
    }

    private static LocalTime parseTime(String hhmm, String field) {
        if (hhmm == null || hhmm.isBlank()) return null;
        try {
            return LocalTime.parse(hhmm);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException(field + " must be HH:mm, got " + hhmm);
        }
    }

    @SuppressWarnings("unchecked")
    private static Schedule fromRow(Map<String, Object> r) {
        return new Schedule((String) r.get("id"), (String) r.get("connection_id"), (String) r.get("type"), (String) r.get("name"),
                ((Number) r.get("every_minutes")).intValue(), (String) r.get("at_time"), (String) r.get("window_start"),
                (String) r.get("window_end"), ((Number) r.get("enabled")).intValue() != 0,
                Json.read((String) r.get("params_json"), Map.class), toLong(r.get("next_run_ms")), toLong(r.get("last_run_ms")),
                (String) r.get("last_outcome"), (String) r.get("last_job_id"));
    }

    private static Long toLong(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    private void tickSafely() {
        try {
            tick(System.currentTimeMillis());
        } catch (RuntimeException e) {
            System.err.println("Schedules: " + e);
        }
    }

    @Override
    public void close() {
        loop.shutdownNow();
    }
}
