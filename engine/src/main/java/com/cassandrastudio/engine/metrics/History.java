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

    /** One series: raw points (last hour) and closed minute buckets (up to 24 h). */
    static final class Track {
        private final Ring raw = new Ring();
        private final Ring minutes = new Ring();
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
            minutes.dropBefore(at - RETENTION_MS);
        }

        private void closeBucket() {
            if (bucketN > 0) minutes.add(bucketStart, bucketSum / bucketN);
            bucketSum = 0;
            bucketN = 0;
        }

        synchronized List<double[]> query(long from, long to) {
            List<double[]> out = new ArrayList<>();
            long rawStart = raw.size() == 0 ? Long.MAX_VALUE : raw.firstTime();
            // minute points only where raw points no longer exist
            for (int i = 0; i < minutes.size(); i++) {
                long t = minutes.time(i);
                if (t + MINUTE_MS > rawStart) break;
                if (t >= from && t <= to) out.add(new double[] {t, minutes.value(i)});
            }
            for (int i = 0; i < raw.size(); i++) {
                long t = raw.time(i);
                if (t >= from && t <= to) out.add(new double[] {t, raw.value(i)});
            }
            return out;
        }

        synchronized int size() {
            return raw.size() + minutes.size();
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

        private void grow() {
            long[] nt = new long[t.length * 2];
            double[] nv = new double[v.length * 2];
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
