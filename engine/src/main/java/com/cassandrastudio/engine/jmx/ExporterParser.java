package com.cassandrastudio.engine.jmx;

import com.cassandrastudio.engine.jmx.JmxAccess.ExporterSample;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prometheus text exposition format (what jmx_exporter serves), and the OpenMetrics subset of
 * it: comments and HELP/TYPE lines are skipped, label values unescaped (\\, \", \n), values may
 * be NaN, +Inf or -Inf, timestamps and exemplars are ignored. A malformed line is skipped, not
 * fatal: one odd metric must not hide the others.
 */
final class ExporterParser {
    private ExporterParser() {}

    static List<ExporterSample> parse(String text) {
        List<ExporterSample> out = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            ExporterSample s = parseLine(line);
            if (s != null) out.add(s);
        }
        return out;
    }

    /** One sample line, or null for comments, blank and malformed lines. */
    static ExporterSample parseLine(String line) {
        String l = line.strip();
        if (l.isEmpty() || l.charAt(0) == '#') return null;
        int i = 0;
        int n = l.length();
        while (i < n && isNameChar(l.charAt(i), i == 0)) i++;
        if (i == 0) return null;
        String name = l.substring(0, i);
        Map<String, String> labels = new LinkedHashMap<>();
        if (i < n && l.charAt(i) == '{') {
            i = parseLabels(l, i + 1, labels);
            if (i < 0) return null;
        }
        if (i >= n || !Character.isWhitespace(l.charAt(i))) return null;
        String rest = l.substring(i).strip();
        int end = 0;
        while (end < rest.length() && !Character.isWhitespace(rest.charAt(end))) end++;
        Double value = parseValue(rest.substring(0, end));
        if (value == null) return null;
        return new ExporterSample(name, Collections.unmodifiableMap(labels), value);
    }

    /** Parses {@code a="x",b="y"}} from {@code i}; returns the index after '}' or -1. */
    private static int parseLabels(String l, int i, Map<String, String> labels) {
        int n = l.length();
        while (true) {
            while (i < n && (l.charAt(i) == ' ' || l.charAt(i) == ',')) i++;
            if (i >= n) return -1;
            if (l.charAt(i) == '}') return i + 1;
            int start = i;
            while (i < n && isNameChar(l.charAt(i), i == start)) i++;
            if (i == start) return -1;
            String key = l.substring(start, i);
            while (i < n && l.charAt(i) == ' ') i++;
            if (i >= n || l.charAt(i) != '=') return -1;
            i++;
            while (i < n && l.charAt(i) == ' ') i++;
            if (i >= n || l.charAt(i) != '"') return -1;
            i++;
            StringBuilder v = new StringBuilder();
            boolean closed = false;
            while (i < n) {
                char c = l.charAt(i++);
                if (c == '"') {
                    closed = true;
                    break;
                }
                if (c == '\\' && i < n) {
                    char e = l.charAt(i++);
                    v.append(switch (e) {
                        case 'n' -> '\n';
                        case '\\' -> '\\';
                        case '"' -> '"';
                        default -> e;
                    });
                } else {
                    v.append(c);
                }
            }
            if (!closed) return -1;
            labels.put(key, v.toString());
        }
    }

    static Double parseValue(String s) {
        switch (s) {
            case "NaN", "nan" -> {
                return Double.NaN;
            }
            case "+Inf", "Inf", "+inf", "inf" -> {
                return Double.POSITIVE_INFINITY;
            }
            case "-Inf", "-inf" -> {
                return Double.NEGATIVE_INFINITY;
            }
            default -> {
                try {
                    return Double.parseDouble(s);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
    }

    private static boolean isNameChar(char c, boolean first) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '_' || c == ':' || !first && c >= '0' && c <= '9';
    }
}
