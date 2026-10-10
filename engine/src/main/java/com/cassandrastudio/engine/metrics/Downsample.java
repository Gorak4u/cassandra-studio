package com.cassandrastudio.engine.metrics;

import java.util.ArrayList;
import java.util.List;

/** Largest-triangle-three-buckets downsampling of (time, value) points (keeps the visual shape and peaks). */
public final class Downsample {
    private Downsample() {}

    /** At most {@code threshold} points (at least 3); the first and last point are always kept. */
    public static List<double[]> lttb(List<double[]> data, int threshold) {
        int n = data.size();
        if (threshold >= n || threshold < 3) return data;
        List<double[]> out = new ArrayList<>(threshold);
        double every = (double) (n - 2) / (threshold - 2);
        int a = 0;
        out.add(data.get(0));
        for (int i = 0; i < threshold - 2; i++) {
            // average of the next bucket: the third point of the triangle
            int nextStart = (int) Math.floor((i + 1) * every) + 1;
            int nextEnd = Math.min((int) Math.floor((i + 2) * every) + 1, n);
            double avgX = 0;
            double avgY = 0;
            for (int j = nextStart; j < nextEnd; j++) {
                avgX += data.get(j)[0];
                avgY += data.get(j)[1];
            }
            int len = Math.max(1, nextEnd - nextStart);
            avgX /= len;
            avgY /= len;
            int start = (int) Math.floor(i * every) + 1;
            int end = (int) Math.floor((i + 1) * every) + 1;
            double ax = data.get(a)[0];
            double ay = data.get(a)[1];
            double maxArea = -1;
            int pick = start;
            for (int j = start; j < end; j++) {
                double area = Math.abs((ax - avgX) * (data.get(j)[1] - ay) - (ax - data.get(j)[0]) * (avgY - ay));
                if (area > maxArea) {
                    maxArea = area;
                    pick = j;
                }
            }
            out.add(data.get(pick));
            a = pick;
        }
        out.add(data.get(n - 1));
        return out;
    }
}
