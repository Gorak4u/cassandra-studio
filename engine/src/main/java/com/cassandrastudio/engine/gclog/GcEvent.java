package com.cassandrastudio.engine.gclog;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.ArrayList;
import java.util.List;

/**
 * One collection or collection phase from a GC log, as the API returns it. Memory values are
 * in KiB; null when the log does not say. {@code x} is seconds since the start of the analysed
 * log (wall clock when every line has a date, else JVM uptime), set by {@link GcLogParser#finish}.
 */
public final class GcEvent {
    public static final String PAUSE = "pause";
    public static final String CONCURRENT = "concurrent";

    /** young, mixed, full (stop-the-world collections), phase (other pauses: remark ...), concurrent. */
    public static final String YOUNG = "young", MIXED = "mixed", FULL = "full", PHASE = "phase";

    public double x;
    public Integer gcId;
    public Double uptime;
    public Long ts;
    /** {@link #PAUSE} or {@link #CONCURRENT}. */
    public String kind;
    /** Young, Mixed, Full, Remark, Initial Mark, Cleanup, Concurrent Mark, ZGC Major ... */
    public String type;
    public String category;
    public String cause;
    public double durationMs;
    public Long heapBeforeK, heapAfterK, heapTotalK;
    public Long youngBeforeK, youngAfterK, youngTotalK;
    public Long oldBeforeK, oldAfterK, oldTotalK;
    public Long humongousBeforeK, humongousAfterK;
    public Long metaBeforeK, metaAfterK, metaTotalK;
    /** to-space exhausted, evacuation failure, concurrent mode failure, promotion failed, humongous ... */
    public List<String> flags;

    // G1 region counts, turned into KiB once the region size is known
    @JsonIgnore Integer edenBefore, edenAfter, edenCap, survBefore, survAfter, survCap, oldRegBefore, oldRegAfter,
            humBefore, humAfter;
    @JsonIgnore String dateStamp;

    public boolean isPause() {
        return PAUSE.equals(kind);
    }

    public boolean hasFlag(String f) {
        return flags != null && flags.contains(f);
    }

    void flag(String f) {
        if (flags == null) flags = new ArrayList<>(2);
        if (!flags.contains(f)) flags.add(f);
    }
}
