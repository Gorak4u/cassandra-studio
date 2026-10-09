package com.cassandrastudio.engine.metrics;

import com.cassandrastudio.engine.util.ApiException;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ALR-1 thresholds for one cluster, as a flat JSON object keyed {@code <rule>.<setting>} (the UI's
 * shape). A null field means "use the default"; {@link #effective()} fills them in, which is what
 * GET returns. PUT sends the full map, so null clears an override. Percentages are 0-100.
 */
public record Thresholds(@JsonProperty("heap.high.yellowPct") Double heapYellowPct,
                         @JsonProperty("heap.high.redPct") Double heapRedPct,
                         @JsonProperty("gc.pressure.yellowPct") Double gcYellowPct,
                         @JsonProperty("gc.pressure.redPct") Double gcRedPct,
                         @JsonProperty("compaction.backlog.pending") Long compactionPending,
                         @JsonProperty("disk.usage.yellowPct") Double diskYellowPct,
                         @JsonProperty("disk.usage.redPct") Double diskRedPct,
                         @JsonProperty("load.imbalance.factor") Double loadImbalanceFactor,
                         @JsonProperty("hints.backlog.polls") Integer hintsPolls) {

    public static final Thresholds DEFAULTS = new Thresholds(85.0, 95.0, 10.0, 25.0, 100L, 80.0, 90.0, 1.5, 3);

    public Thresholds effective() {
        Thresholds d = DEFAULTS;
        return new Thresholds(or(heapYellowPct, d.heapYellowPct), or(heapRedPct, d.heapRedPct),
                or(gcYellowPct, d.gcYellowPct), or(gcRedPct, d.gcRedPct),
                or(compactionPending, d.compactionPending), or(diskYellowPct, d.diskYellowPct),
                or(diskRedPct, d.diskRedPct), or(loadImbalanceFactor, d.loadImbalanceFactor),
                or(hintsPolls, d.hintsPolls));
    }

    /** Validates the effective values; throws 400 with the first problem. */
    public Thresholds validated() {
        Thresholds e = effective();
        pair("heap", e.heapYellowPct, e.heapRedPct);
        pair("gc", e.gcYellowPct, e.gcRedPct);
        pair("disk", e.diskYellowPct, e.diskRedPct);
        if (e.compactionPending < 0) throw ApiException.badRequest("compactionPending must be >= 0");
        if (e.loadImbalanceFactor <= 1.0) throw ApiException.badRequest("loadImbalanceFactor must be > 1");
        if (e.hintsPolls < 1 || e.hintsPolls > 1000) throw ApiException.badRequest("hintsPolls must be 1-1000");
        return this;
    }

    private static void pair(String what, double yellow, double red) {
        if (yellow <= 0 || yellow > 100 || red <= 0 || red > 100) {
            throw ApiException.badRequest(what + " thresholds must be between 0 and 100");
        }
        if (yellow > red) throw ApiException.badRequest(what + " yellow threshold must not exceed red");
    }

    private static <T> T or(T v, T dflt) {
        return v == null ? dflt : v;
    }
}
