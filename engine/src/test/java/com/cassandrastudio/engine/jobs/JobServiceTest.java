package com.cassandrastudio.engine.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cassandrastudio.engine.audit.AuditLog;
import com.cassandrastudio.engine.conn.ConnectionRepository;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.Environment;
import com.cassandrastudio.engine.secrets.SecretStores;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.ApiException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JobServiceTest {
    private Database db;
    private AuditLog audit;
    private JobService jobs;
    private String connId;

    @BeforeEach
    void setUp() {
        db = Database.inMemory();
        ConnectionRepository repo = new ConnectionRepository(db, SecretStores.inMemory());
        connId = repo.save(new ConnectionConfig(null, null, "c1", Environment.DEV, null, false, List.of("127.0.0.1"),
                "dc1", null, null, null, null, null, null, null, null, List.of(), null, null), Map.of()).id();
        audit = new AuditLog(db, "tester");
        jobs = new JobService(repo, audit);
    }

    @AfterEach
    void tearDown() {
        jobs.close();
        db.close();
    }

    private JobService.Spec spec(String audit, boolean cancellable) {
        return new JobService.Spec(connId, "flush", "Flush shop on 10.0.0.1", "10.0.0.1", audit, cancellable);
    }

    @Test
    void runsReportsProgressAndAudits() throws Exception {
        Job j = jobs.submit(spec("ops", true), ctx -> {
            ctx.progress(0.5, "half way");
            ctx.log("flushed shop.orders");
            return Map.of("tables", 1);
        });
        Job done = jobs.await(j.id(), 5_000);
        assertThat(done.state()).isEqualTo(Job.State.SUCCEEDED);
        assertThat(done.progress()).isEqualTo(1.0);
        assertThat(done.message()).isEqualTo("half way");
        assertThat(done.log()).containsExactly("flushed shop.orders");
        assertThat(done.result()).isEqualTo(Map.of("tables", 1));
        assertThat(jobs.list(connId)).extracting(Job::id).containsExactly(j.id());
        assertThat(audit.search(connId, null, null, 10)).singleElement().satisfies(e -> {
            assertThat(e.category()).isEqualTo("ops");
            assertThat(e.outcome()).isEqualTo("SUCCESS");
        });
    }

    @Test
    void failureKeepsTheMessage() throws Exception {
        Job j = jobs.submit(spec("ops", false), ctx -> {
            throw new IllegalStateException("node 10.0.0.1 refused");
        });
        Job done = jobs.await(j.id(), 5_000);
        assertThat(done.state()).isEqualTo(Job.State.FAILED);
        assertThat(done.error()).isEqualTo("node 10.0.0.1 refused");
        assertThat(audit.search(connId, null, null, 10)).singleElement()
                .satisfies(e -> assertThat(e.outcome()).isEqualTo("FAILED"));
    }

    @Test
    void cancelRunsTheCancelActionAndInterrupts() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean terminated = new AtomicBoolean();
        Job j = jobs.submit(spec(null, true), ctx -> {
            ctx.onCancel(() -> terminated.set(true));
            started.countDown();
            Thread.sleep(60_000);
            return null;
        });
        started.await();
        jobs.cancel(j.id());
        Job done = jobs.await(j.id(), 5_000);
        assertThat(done.state()).isEqualTo(Job.State.CANCELLED);
        assertThat(terminated).isTrue();
        assertThat(audit.search(connId, null, null, 10)).isEmpty(); // no audit category
    }

    @Test
    void notCancellableAndUnknownJobs() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Job j = jobs.submit(spec(null, false), ctx -> {
            release.await();
            return null;
        });
        assertThatThrownBy(() -> jobs.cancel(j.id())).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(409));
        release.countDown();
        assertThat(jobs.await(j.id(), 5_000).state()).isEqualTo(Job.State.SUCCEEDED);
        assertThatThrownBy(() -> jobs.get("nope")).isInstanceOf(ApiException.class);
    }

    @Test
    void logIsBounded() throws Exception {
        Job j = jobs.submit(spec(null, false), ctx -> {
            for (int i = 0; i < JobService.MAX_LOG_LINES + 50; i++) ctx.log("line " + i);
            return null;
        });
        Job done = jobs.await(j.id(), 5_000);
        assertThat(done.log()).hasSize(JobService.MAX_LOG_LINES).first().isEqualTo("line 50");
    }
}
