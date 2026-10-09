package com.cassandrastudio.engine.ops;

/**
 * Percentiles of Cassandra's EstimatedHistogram as exposed over JMX (EstimatedPartitionSizeHistogram,
 * EstimatedColumnCountHistogram: a long[] of bucket counts, the last one the overflow bucket).
 * Same bucket offsets and rules as org.apache.cassandra.utils.EstimatedHistogram, so the numbers
 * match nodetool tablehistograms.
 */
final class Histograms {
    private Histograms() {}

    /** Bucket offsets for a histogram with {@code bucketCount} buckets (plus overflow): 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, ... */
    static long[] offsets(int bucketCount) {
        long[] out = new long[Math.max(0, bucketCount)];
        long last = 1;
        for (int i = 0; i < out.length; i++) {
            out[i] = last;
            long next = Math.round(last * 1.2);
            if (next == last) next++;
            last = next;
        }
        return out;
    }

    /** The value at percentile {@code p} (0..1); null for an empty histogram, Long.MAX_VALUE when in overflow. */
    static Long percentile(long[] buckets, double p) {
        if (buckets == null || buckets.length < 2) return null;
        long[] off = offsets(buckets.length - 1);
        int last = buckets.length - 1;
        long count = 0;
        for (long b : buckets) count += b;
        if (count == 0) return null;
        if (buckets[last] > 0) {
            // EstimatedHistogram refuses percentiles with overflowed values; nodetool reports them as such.
            return Long.MAX_VALUE;
        }
        long target = (long) Math.ceil(count * p);
        if (target <= 0) return 0L;
        long seen = 0;
        for (int i = 0; i < last; i++) {
            seen += buckets[i];
            if (seen >= target) return off[i];
        }
        return 0L;
    }

    static Long min(long[] buckets) {
        if (buckets == null || buckets.length < 2) return null;
        long[] off = offsets(buckets.length - 1);
        for (int i = 0; i < buckets.length; i++) {
            if (buckets[i] > 0) return i == 0 ? 0L : off[i - 1] + 1;
        }
        return null;
    }

    static Long max(long[] buckets) {
        if (buckets == null || buckets.length < 2) return null;
        long[] off = offsets(buckets.length - 1);
        int last = buckets.length - 1;
        if (buckets[last] > 0) return Long.MAX_VALUE;
        for (int i = last - 1; i >= 0; i--) {
            if (buckets[i] > 0) return off[i];
        }
        return null;
    }
}
