package com.cassandrastudio.engine.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.cassandrastudio.engine.audit.AuditLog;
import com.cassandrastudio.engine.conn.ConnectionRepository;
import com.cassandrastudio.engine.jobs.JobService;
import com.cassandrastudio.engine.jobs.JobTask;
import com.cassandrastudio.engine.sched.Schedule;
import com.cassandrastudio.engine.sched.ScheduleService;
import com.cassandrastudio.engine.sched.ScheduledTask;
import com.cassandrastudio.engine.secrets.SecretStores;
import com.cassandrastudio.engine.store.Database;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SRV-7: two instances that both believe they lead (a lease handover race) still fire a due schedule
 * exactly once, because each run is claimed with a compare-and-set on the shared store.
 */
class ScheduleClaimTest {
    @TempDir
    Path dir;

    @Test
    void dueRunFiresOnceAcrossInstances() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        List<Database> dbs = new ArrayList<>();
        List<ScheduleService> services = new ArrayList<>();
        List<JobService> jobs = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Database db = Database.open(dir); // two connections to one file, like two servers on one store
            dbs.add(db);
            JobService js = new JobService(new ConnectionRepository(db, SecretStores.inMemory()), new AuditLog(db, "test"));
            jobs.add(js);
            ScheduleService s = new ScheduleService(db, js, ZoneOffset.UTC);
            s.register("count", new ScheduledTask() {
                @Override
                public JobService.Spec spec(Schedule sc) {
                    return new JobService.Spec(null, "count", "Count", null, null, false);
                }

                @Override
                public JobTask task(Schedule sc) {
                    return ctx -> runs.incrementAndGet();
                }
            });
            services.add(s);
        }
        try {
            for (int round = 0; round < 5; round++) {
                Schedule saved = services.get(0).save(new Schedule(null, null, "count", "s" + round, 5, null, null, null, true,
                        Map.of(), null, null, null, null));
                long now = System.currentTimeMillis();
                dbs.get(0).update("UPDATE schedules SET next_run_ms = ? WHERE id = ?", now - 1_000, saved.id());
                CyclicBarrier start = new CyclicBarrier(2);
                List<Thread> threads = new ArrayList<>();
                for (ScheduleService s : services) {
                    Thread t = new Thread(() -> {
                        try {
                            start.await();
                            s.tick(now);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    });
                    t.start();
                    threads.add(t);
                }
                for (Thread t : threads) t.join();
                assertThat(services.get(1).runs(saved.id(), 10)).as("round " + round).hasSize(1);
                assertThat(services.get(0).get(saved.id()).orElseThrow().nextRunMs()).isGreaterThan(now);
            }
            Thread.sleep(300);
            assertThat(runs).hasValue(5);
        } finally {
            services.forEach(ScheduleService::close);
            jobs.forEach(JobService::close);
            dbs.forEach(Database::close);
        }
    }
}
