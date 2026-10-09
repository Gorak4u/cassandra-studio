package com.cassandrastudio.engine.jobs;

import java.util.List;

/**
 * Phase 3 contract: a long-running task (repair, compaction, backup, bulk load ...) as the API
 * returns it (docs/api/jobs.md). Mirrored by ui/src/lib/jobsTypes.ts.
 *
 * @param progress 0..1, or null when the task cannot tell
 * @param log      the last {@link JobService#MAX_LOG_LINES} lines, oldest first
 * @param result   task-specific JSON-able value set on success (e.g. rows unloaded), or null
 */
public record Job(String id, String connectionId, String kind, String title, String node, State state,
                  Double progress, String message, List<String> log, Object result, String error,
                  long createdAtMs, Long startedAtMs, Long finishedAtMs, boolean cancellable) {

    public enum State { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    public boolean done() {
        return state == State.SUCCEEDED || state == State.FAILED || state == State.CANCELLED;
    }
}
