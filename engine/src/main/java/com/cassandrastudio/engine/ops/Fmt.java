package com.cassandrastudio.engine.ops;

import java.util.Locale;

/** Number formatting the way nodetool prints it. */
final class Fmt {
    private Fmt() {}

    private static final String[] UNITS = {"bytes", "KiB", "MiB", "GiB", "TiB", "PiB"};

    /** 1536 → "1.5 KiB" (nodetool's FileUtils.stringifyFileSize). Null stays null. */
    static String bytes(Long v) {
        if (v == null) return null;
        double d = v;
        int u = 0;
        while (Math.abs(d) >= 1024 && u < UNITS.length - 1) {
            d /= 1024;
            u++;
        }
        return u == 0 ? v + " bytes" : String.format(Locale.ROOT, "%.2f %s", d, UNITS[u]);
    }

    static String num(Number v) {
        return v == null ? null : String.valueOf(v.longValue());
    }

    static String dec(Double v, int places) {
        return v == null ? null : String.format(Locale.ROOT, "%." + places + "f", v);
    }

    /** 0..1 fraction → "33.3%". */
    static String pct(Double fraction) {
        return fraction == null ? null : String.format(Locale.ROOT, "%.1f%%", fraction * 100);
    }

    static String bool(Object v) {
        return v instanceof Boolean b ? b.toString() : null;
    }

    /** "Number of bytes or a size string" from nodetool-style maps, back to bytes (e.g. "245.27 KiB"). */
    static Long parseSize(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException ignored) {
            // with unit
        }
        String[] p = t.split("\\s+");
        if (p.length != 2) return null;
        try {
            double v = Double.parseDouble(p[0]);
            String unit = p[1].toUpperCase(Locale.ROOT);
            int pow = switch (unit) {
                case "BYTES", "B" -> 0;
                case "KIB", "KB" -> 1;
                case "MIB", "MB" -> 2;
                case "GIB", "GB" -> 3;
                case "TIB", "TB" -> 4;
                case "PIB", "PB" -> 5;
                default -> -1;
            };
            return pow < 0 ? null : Math.round(v * Math.pow(1024, pow));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
