package com.cassandrastudio.engine.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MON-3 in-memory history for one cluster: per (metric, node) one point per poll for the last
 * hour, then one point per minute (the mean) up to 24 h. Memory is bounded: points older than
 * the retention are dropped and buffers never hold more than an hour of raw points at the
 * minimum poll interval plus 1440 minute points.
 */
public final class History {
    public static final long RAW_MS = 60 * 60 * 1000L;
    public static final long RETENTION_MS = 24 * RAW_MS;
    static final long MINUTE_MS = 60_000L;

    private final Map<String, Track> tracks = new ConcurrentHashMap<>();

    public void add(String metric, String node, long atMs, Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) return;
        tracks.computeIfAbsent(metric + "\u0000" + node, k -> new Track()).add(atMs, value);
    }

    /** Points in [fromMs, toMs] by node; node null = every node with points. */
    public Map<String, List<double[]>> query(String metric, String node, long fromMs, long toMs) {
        Map<String, List<double[]>> out = new TreeMap<>();
        String prefix = metric + "\u0000";
        tracks.forEach((key, track) -> {
            if (!key.startsWith(prefix)) return;
            String n = key.substring(prefix.length());
            if (node != null && !node.equals(n)) return;
            List<double[]> pts = track.query(fromMs, toMs);
            if (!pts.isEmpty() || node != null) out.put(n, pts);
        });
        if (node != null) out.putIfAbsent(node, List.of());
        return out;
    }

    /** Drop nodes that left the cluster. */
    public void retainNodes(Set<String> nodes) {
        tracks.keySet().removeIf(k -> !nodes.contains(k.substring(k.indexOf('\u0000') + 1)));
    }

    public void clear() {
        tracks.clear();
    }

    int pointCount() {
        return tracks.values().stream().mapToInt(Track::size).sum();
    }

    /** Approximate heap bytes held by all series (NFR-SCALE measurements). */
    public long approxBytes() {
        return tracks.values().stream().mapToLong(Track::bytes).sum();
    }

    /**
     * One series: raw points (last hour) and closed minute buckets (up to 24 h). Minute means are
     * kept as floats in a fixed slot per minute of the day (4 bytes a point, no timestamps), which
     * keeps 24 h for a 500-node cluster at about a third of the memory of (time, value) pairs.
     */
    static final class Track {
        static final int SLOTS = (int) (RETENTION_MS / MINUTE_MS) + 1;
        private final Ring raw = new Ring();
        /** Mean per minute at slot floorMod(minute, SLOTS); NaN = no point. Allocated on the first closed minute. */
        private float[] minuteMeans;
        /** Minute index (time / 60 s) of the newest closed bucket; -1 = none. */
        private long newestMinute = -1;
        private long bucketStart = -1;
        private double bucketSum;
        private int bucketN;

        synchronized void add(long at, double v) {
            if (raw.size() > 0 && at < raw.lastTime()) return; // out of order: ignore
            long minute = at - Math.floorMod(at, MINUTE_MS);
            if (bucketStart >= 0 && minute != bucketStart) closeBucket();
            bucketStart = minute;
            bucketSum += v;
            bucketN++;
            raw.add(at, v);
            raw.dropBefore(at - RAW_MS);
        }

        private void closeBucket() {
            if (bucketN > 0) {
                if (minuteMeans == null) {
                    minuteMeans = new float[SLOTS];
                    java.util.Arrays.fill(minuteMeans, Float.NaN);
                }
                long m = bucketStart / MINUTE_MS;
                // minutes without a point since the newest bucket (polling paused, node unreadable)
                if (newestMinute >= 0) {
                    for (long g = newestMinute + 1; g < m && g <= newestMinute + SLOTS; g++) {
                        minuteMeans[Math.floorMod(g, SLOTS)] = Float.NaN;
                    }
                }
                minuteMeans[Math.floorMod(m, SLOTS)] = (float) (bucketSum / bucketN);
                newestMinute = m;
            }
            bucketSum = 0;
            bucketN = 0;
        }

        /** First minute index still inside the retention, relative to the newest raw point. */
        private long oldestMinute() {
            long cutoff = raw.size() == 0 ? Long.MIN_VALUE : raw.lastTime() - RETENTION_MS;
            long oldest = newestMinute - SLOTS + 1;
            long byTime = cutoff == Long.MIN_VALUE ? oldest : Math.floorDiv(cutoff + MINUTE_MS - 1, MINUTE_MS);
            return Math.max(oldest, byTime);
        }

        synchronized List<double[]> query(long from, long to) {
            List<double[]> out = new ArrayList<>();
            long rawStart = raw.size() == 0 ? Long.MAX_VALUE : raw.firstTime();
            // minute points only where raw points no longer exist
            if (minuteMeans != null) {
                for (long m = oldestMinute(); m <= newestMinute; m++) {
                    long t = m * MINUTE_MS;
                    if (t + MINUTE_MS > rawStart) break;
                    float v = minuteMeans[Math.floorMod(m, SLOTS)];
                    if (!Float.isNaN(v) && t >= from && t <= to) out.add(new double[] {t, v});
                }
            }
            for (int i = 0; i < raw.size(); i++) {
                long t = raw.time(i);
                if (t >= from && t <= to) out.add(new double[] {t, raw.value(i)});
            }
            return out;
        }

        synchronized int size() {
            int n = raw.size();
            if (minuteMeans != null) {
                for (long m = oldestMinute(); m <= newestMinute; m++) {
                    if (!Float.isNaN(minuteMeans[Math.floorMod(m, SLOTS)])) n++;
                }
            }
            return n;
        }

        /** Approximate bytes held, for the scale tests. */
        synchronized long bytes() {
            return raw.capacity() * 16L + (minuteMeans == null ? 0 : minuteMeans.length * 4L) + 64;
        }
    }

    /** Growable circular buffer of (time, value) primitives, oldest first. */
    static final class Ring {
        private long[] t = new long[64];
        private double[] v = new double[64];
        private int head, size;

        void add(long time, double value) {
            if (size == t.length) grow();
            int i = (head + size) % t.length;
            t[i] = time;
            v[i] = value;
            size++;
        }

        void dropBefore(long cutoff) {
            while (size > 0 && t[head] < cutoff) {
                head = (head + 1) % t.length;
                size--;
            }
        }

        /** Grows by half: an hour of 10 s points (361) fits in 486 slots instead of 512. */
        private void grow() {
            int cap = t.length + t.length / 2;
            long[] nt = new long[cap];
            double[] nv = new double[cap];
            for (int i = 0; i < size; i++) {
                nt[i] = t[(head + i) % t.length];
                nv[i] = v[(head + i) % v.length];
            }
            t = nt;
            v = nv;
            head = 0;
        }

        int size() {
            return size;
        }

        int capacity() {
            return t.length;
        }

        long time(int i) {
            return t[(head + i) % t.length];
        }

        double value(int i) {
            return v[(head + i) % v.length];
        }

        long firstTime() {
            return time(0);
        }

        long lastTime() {
            return time(size - 1);
        }
    }
}
