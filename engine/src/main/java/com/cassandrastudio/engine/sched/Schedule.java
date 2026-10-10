package com.cassandrastudio.engine.sched;

import java.util.Map;

/**
 * A recurring job (SRV-5): a registered {@link ScheduledTask} type run every {@code everyMinutes},
 * optionally anchored to a local time of day ({@code atTime}, "HH:mm") and limited to a window
 * ({@code windowStart}..{@code windowEnd}, "HH:mm", may wrap midnight). {@code params} are the
 * type's own settings (keyspace, provider, retention, ...).
 */
public record Schedule(String id, String connectionId, String type, String name, int everyMinutes, String atTime,
                       String windowStart, String windowEnd, boolean enabled, Map<String, Object> params,
                       Long nextRunMs, Long lastRunMs, String lastOutcome, String lastJobId) {

    public Schedule {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    public Schedule withRun(Long nextRunMs, Long lastRunMs, String lastOutcome, String lastJobId) {
        return new Schedule(id, connectionId, type, name, everyMinutes, atTime, windowStart, windowEnd, enabled, params,
                nextRunMs, lastRunMs, lastOutcome, lastJobId);
    }
}
