package com.cassandrastudio.engine.jobs;

/** What a running task can do: report progress, log, and check for cancellation. */
public interface JobContext {
    /** @param fraction 0..1, or null when unknown; {@code message} is a short status line, or null */
    void progress(Double fraction, String message);

    void log(String line);

    /** True once cancel was requested; the task should stop soon and return. */
    boolean cancelled();

    /** Throws {@link java.util.concurrent.CancellationException} when cancelled; call between steps. */
    default void checkCancelled() {
        if (cancelled()) throw new java.util.concurrent.CancellationException("cancelled");
    }

    /** Registers what to run on cancel, e.g. a JMX forceTerminateAllRepairSessions; runs at most once. */
    void onCancel(Runnable action);
}
