package com.cassandrastudio.engine.gclog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything {@link GcLogParser} read from one or more GC log files, before analysis. */
public final class GcLog {
    /** java8, unified, mixed or unknown. */
    public String format = "unknown";
    /** G1, CMS, Parallel, Serial, ZGC, ZGC (generational), Shenandoah, or null when the log does not tell. */
    public String collector;
    public String jvmVersion;
    /** Java 8 "CommandLine flags:" line, or null. */
    public String jvmFlags;
    public Long heapMaxK;
    public Long regionSizeK;
    /** wall (every event has a date stamp) or uptime. */
    public String timeAxis = "uptime";
    /** Epoch ms at x = 0 when {@link #timeAxis} is wall. */
    public Long startTs;
    /** x of the first and the last line with a time stamp, for the log duration. */
    public double startX, endX;
    public long lines;
    public long bytes;
    public final List<GcEvent> events = new ArrayList<>();

    // counters for things that are not events
    public long safepoints;
    public double safepointTotalMs, safepointMaxMs, ttspTotalMs, ttspMaxMs;
    public final Map<String, double[]> safepointReasons = new LinkedHashMap<>();
    public long allocationStalls;
    public double allocationStallTotalMs, allocationStallMaxMs;
    public long concurrentModeFailures, promotionFailures, toSpaceExhausted, evacuationFailures, shenandoahCancels;
    /** Safepoint and stall times as (x, ms), kept for range filtering. */
    public final Points safepointPoints = new Points();
    public final Points stallPoints = new Points();
    public final List<String> warnings = new ArrayList<>();

    /** Java major version when known (from the header, or 8 for the Java 8 format). */
    public Integer javaMajor() {
        if (jvmVersion != null) {
            String v = jvmVersion.startsWith("1.") ? jvmVersion.substring(2) : jvmVersion;
            int i = 0;
            while (i < v.length() && Character.isDigit(v.charAt(i))) i++;
            if (i > 0) {
                try {
                    return Integer.parseInt(v.substring(0, i));
                } catch (NumberFormatException e) {
                    // fall through
                }
            }
        }
        if ("java8".equals(format)) return 8;
        return null;
    }

    /**
     * A growable list of time-stamped values without boxing (a large log has a million
     * safepoints). Points carry the raw stamps; {@link GcLogParser#finish} sets x.
     */
    public static final class Points {
        double[] ts = new double[64];
        double[] up = new double[64];
        double[] xs = new double[64];
        double[] vs = new double[64];
        int size;

        void add(Long tsMs, Double uptime, double v) {
            if (size == vs.length) {
                ts = java.util.Arrays.copyOf(ts, size * 2);
                up = java.util.Arrays.copyOf(up, size * 2);
                xs = java.util.Arrays.copyOf(xs, size * 2);
                vs = java.util.Arrays.copyOf(vs, size * 2);
            }
            ts[size] = tsMs == null ? Double.NaN : tsMs;
            up[size] = uptime == null ? Double.NaN : uptime;
            vs[size++] = v;
        }

        public int size() {
            return size;
        }

        public double x(int i) {
            return xs[i];
        }

        public double v(int i) {
            return vs[i];
        }
    }
}
