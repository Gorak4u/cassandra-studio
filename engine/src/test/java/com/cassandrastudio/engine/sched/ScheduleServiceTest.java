package com.cassandrastudio.engine.sched;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cassandrastudio.engine.alerts.AlertHub;
import com.cassandrastudio.engine.alerts.StudioAlert;
import com.cassandrastudio.engine.audit.AuditLog;
import com.cassandrastudio.engine.conn.ConnectionRepository;
import com.cassandrastudio.engine.jobs.Job;
import com.cassandrastudio.engine.jobs.JobService;
import com.cassandrastudio.engine.jobs.JobTask;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.Environment;
import com.cassandrastudio.engine.secrets.SecretStores;
import com.cassandrastudio.engine.store.Database;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScheduleServiceTest {
    private static final ZoneId UTC = ZoneOffset.UTC;
    private Database db;
    private JobService jobs;
    private ScheduleService schedules;
    private String connId;
    private final AtomicInteger runs = new AtomicInteger();
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile boolean block;

    @BeforeEach
    void setUp() {
        db = Database.inMemory();
        ConnectionRepository repo = new ConnectionRepository(db, SecretStores.inMemory());
        connId = repo.save(new ConnectionConfig(null, null, "c1", Environment.DEV, null, false, List.of("127.0.0.1"),
                "dc1", null, null, null, null, null, null, null, null, List.of(), null, null), Map.of()).id();
        jobs = new JobService(repo, new AuditLog(db, "tester"));
        schedules = new ScheduleService(db, jobs, UTC);
        schedules.register("count", new ScheduledTask() {
            @Override
            public void validate(Schedule s) {
                if (s.params().containsKey("bad")) throw new IllegalArgumentException("bad param");
            }

            @Override
            public JobService.Spec spec(Schedule s) {
                return new JobService.Spec(s.connectionId(), "count", "Count " + s.name(), null, "test", true);
            }

            @Override
            public JobTask task(Schedule s) {
                return ctx -> {
                    runs.incrementAndGet();
                    if (block) release.await();
                    return null;
                };
            }
        });
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        schedules.close();
        jobs.close();
        db.close();
    }

    private Schedule schedule(int every, String at, String from, String to) {
        return new Schedule(null, connId, "count", "nightly", every, at, from, to, true, Map.of("keyspace", "shop"),
                null, null, null, null);
    }

    private static long utc(String iso) {
        return LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    @Test
    void savesAndReadsBack() {
        Schedule s = schedules.save(schedule(1440, "01:00", null, null));
        assertThat(s.id()).isNotBlank();
        assertThat(s.nextRunMs()).isNotNull();
        Schedule back = schedules.get(s.id()).orElseThrow();
        assertThat(back.params()).containsEntry("keyspace", "shop");
        assertThat(back.atTime()).isEqualTo("01:00");
        assertThat(schedules.list(connId)).hasSize(1);
        assertThat(schedules.types()).containsExactly("count");
    }

    @Test
    void rejectsBadSchedules() {
        assertThatThrownBy(() -> schedules.save(new Schedule(null, connId, "nope", "x", 60, null, null, null, true, Map.of(),
                null, null, null, null))).hasMessageContaining("Unknown schedule type");
        assertThatThrownBy(() -> schedules.save(schedule(1, null, null, null))).hasMessageContaining("5 minutes");
        assertThatThrownBy(() -> schedules.save(schedule(60, "25:00", null, null))).hasMessageContaining("HH:mm");
        assertThatThrownBy(() -> schedules.save(schedule(60, null, "01:00", null))).hasMessageContaining("both");
        assertThatThrownBy(() -> schedules.save(new Schedule(null, connId, "count", "x", 60, null, null, null, true,
                Map.of("bad", 1), null, null, null, null))).hasMessageContaining("bad param");
    }

    @Test
    void nextRunAlignsToTimeOfDay() {
        Schedule daily = schedule(1440, "01:00", null, null);
        assertThat(schedules.nextRun(daily, utc("2026-10-10T00:30:00"))).isEqualTo(utc("2026-10-10T01:00:00"));
        assertThat(schedules.nextRun(daily, utc("2026-10-10T01:00:00"))).isEqualTo(utc("2026-10-11T01:00:00"));
        Schedule weekly = schedule(7 * 1440, "02:30", null, null);
        assertThat(schedules.nextRun(weekly, utc("2026-10-10T03:00:00"))).isEqualTo(utc("2026-10-17T02:30:00"));
        Schedule hourly = schedule(60, null, null, null);
        assertThat(schedules.nextRun(hourly, utc("2026-10-10T03:10:00"))).isEqualTo(utc("2026-10-10T04:10:00"));
    }

    @Test
    void windowsMayWrapMidnight() {
        Schedule night = schedule(60, null, "22:00", "04:00");
        assertThat(schedules.inWindow(night, utc("2026-10-10T23:00:00"))).isTrue();
        assertThat(schedules.inWindow(night, utc("2026-10-10T03:59:00"))).isTrue();
        assertThat(schedules.inWindow(night, utc("2026-10-10T12:00:00"))).isFalse();
        Schedule day = schedule(60, null, "09:00", "17:00");
        assertThat(schedules.inWindow(day, utc("2026-10-10T17:00:00"))).isFalse();
        assertThat(schedules.inWindow(day, utc("2026-10-10T09:00:00"))).isTrue();
    }

    @Test
    void tickFiresDueSchedulesAndRecordsTheOutcome() throws Exception {
        Schedule s = schedules.save(schedule(60, null, null, null));
        schedules.tick(s.nextRunMs() - 1);
        assertThat(runs.get()).isZero();
        schedules.tick(s.nextRunMs());
        Schedule fired = schedules.get(s.id()).orElseThrow();
        assertThat(fired.lastJobId()).isNotNull();
        Job job = jobs.await(fired.lastJobId(), 5000);
        assertThat(job.state()).isEqualTo(Job.State.SUCCEEDED);
        assertThat(runs.get()).isEqualTo(1);
        assertThat(fired.nextRunMs()).isGreaterThan(s.nextRunMs());
        schedules.tick(s.nextRunMs() + 1000); // not due again yet; picks up the finished state
        assertThat(schedules.get(s.id()).orElseThrow().lastOutcome()).isEqualTo("SUCCEEDED");
        assertThat(schedules.runs(s.id(), 10)).extracting(ScheduleService.Run::outcome).containsExactly("SUCCEEDED");
    }

    @Test
    void skipsWhilePreviousRunIsStillGoing() throws Exception {
        block = true;
        Schedule s = schedules.save(schedule(60, null, null, null));
        Job first = schedules.runNow(s.id());
        for (int i = 0; i < 50 && jobs.get(first.id()).state() != Job.State.RUNNING; i++) Thread.sleep(20);
        long due = schedules.get(s.id()).orElseThrow().nextRunMs();
        schedules.tick(due);
        assertThat(schedules.get(s.id()).orElseThrow().lastOutcome()).isEqualTo("SKIPPED");
        assertThat(schedules.runs(s.id(), 10)).extracting(ScheduleService.Run::outcome).contains("SKIPPED", "STARTED");
        release.countDown();
        assertThat(jobs.await(first.id(), 5000).state()).isEqualTo(Job.State.SUCCEEDED);
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void waitsOutsideItsWindowAndOnlyTheLeaderFires() {
        Schedule s = schedules.save(schedule(60, null, "01:00", "02:00"));
        long noon = utc("2026-10-10T12:00:00");
        db.update("UPDATE schedules SET next_run_ms = ? WHERE id = ?", noon, s.id());
        schedules.tick(noon);
        assertThat(runs.get()).isZero();
        assertThat(schedules.get(s.id()).orElseThrow().nextRunMs()).isGreaterThan(noon);

        Schedule any = schedules.save(schedule(60, null, null, null));
        schedules.setLeader(() -> false);
        schedules.tick(any.nextRunMs());
        assertThat(schedules.get(any.id()).orElseThrow().lastJobId()).isNull();
    }

    @Test
    void deleteRemovesTheScheduleAndItsRuns() throws Exception {
        Schedule s = schedules.save(schedule(60, null, null, null));
        jobs.await(schedules.runNow(s.id()).id(), 5000);
        assertThat(schedules.delete(s.id())).isTrue();
        assertThat(schedules.get(s.id())).isEmpty();
        assertThat(schedules.runs(s.id(), 10)).isEmpty();
    }

    @Test
    void alertHubPassesOnlyChanges() {
        AlertHub hub = new AlertHub();
        List<StudioAlert> seen = new ArrayList<>();
        hub.subscribe(seen::add);
        hub.publish(alert(StudioAlert.Severity.GREEN));
        hub.publish(alert(StudioAlert.Severity.RED));
        hub.publish(alert(StudioAlert.Severity.RED));
        hub.publish(alert(StudioAlert.Severity.GREEN));
        assertThat(seen).extracting(StudioAlert::severity)
                .containsExactly(StudioAlert.Severity.RED, StudioAlert.Severity.GREEN);
        hub.forget("c1");
        hub.publish(alert(StudioAlert.Severity.RED));
        assertThat(seen).hasSize(3);
    }

    private static StudioAlert alert(StudioAlert.Severity sev) {
        return new StudioAlert("c1", "acme", "PROD", "health", "node-down:10.0.0.1", sev, "Node down", null, 0);
    }
}
