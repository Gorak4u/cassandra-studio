package com.cassandrastudio.engine.sched;

import com.cassandrastudio.engine.jobs.JobService;
import com.cassandrastudio.engine.jobs.JobTask;

/**
 * One kind of scheduled work (repair, backup, verify, health report, ...), registered with
 * {@link ScheduleService#register}. Each due run becomes an ordinary job, so it gets progress, cancel,
 * the job log and the audit entry like a run started by hand.
 */
public interface ScheduledTask {
    /** Checks the schedule's params before it is saved; throws IllegalArgumentException with a readable message. */
    default void validate(Schedule schedule) {}

    JobService.Spec spec(Schedule schedule);

    JobTask task(Schedule schedule);
}
