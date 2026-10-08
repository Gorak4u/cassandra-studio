package com.cassandrastudio.engine.cql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits a CQL script into statements (CQL-2). Understands quoted strings
 * ('it''s'), quoted identifiers, $$ function bodies, line and block comments,
 * and BEGIN BATCH ... APPLY BATCH, whose inner semicolons do not end it.
 */
public final class CqlScript {

    /** One statement and where it starts in the script (for error markers). */
    public record Statement(String text, int offset, int line) {}

    private CqlScript() {}

    public static List<Statement> split(String script) {
        List<Statement> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int start = -1;
        int startLine = 1;
        int line = 1;
        int n = script.length();
        int i = 0;
        while (i < n) {
            char c = script.charAt(i);
            char next = i + 1 < n ? script.charAt(i + 1) : '\0';
            if (c == '-' && next == '-' || c == '/' && next == '/') {
                while (i < n && script.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && next == '*') {
                int end = script.indexOf("*/", i + 2);
                int stop = end < 0 ? n : end + 2;
                line += count(script, i, stop);
                if (cur.length() > 0) cur.append(' ');
                i = stop;
                continue;
            }
            if (start < 0 && !Character.isWhitespace(c) && c != ';') {
                start = i;
                startLine = line;
            }
            if (c == '\'' || c == '"') {
                int j = i + 1;
                while (j < n) {
                    if (script.charAt(j) == c) {
                        if (j + 1 < n && script.charAt(j + 1) == c) { j += 2; continue; }
                        break;
                    }
                    j++;
                }
                int stop = Math.min(j + 1, n);
                line += count(script, i, stop);
                cur.append(script, i, stop);
                i = stop;
                continue;
            }
            if (c == '$' && next == '$') {
                int end = script.indexOf("$$", i + 2);
                int stop = end < 0 ? n : end + 2;
                line += count(script, i, stop);
                cur.append(script, i, stop);
                i = stop;
                continue;
            }
            if (c == ';' && !insideBatch(cur)) {
                add(out, cur, start, startLine);
                cur.setLength(0);
                start = -1;
                i++;
                continue;
            }
            if (c == '\n') line++;
            cur.append(c);
            i++;
        }
        add(out, cur, start, startLine);
        return out;
    }

    private static boolean insideBatch(StringBuilder cur) {
        String s = cur.toString().stripLeading().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (!s.startsWith("BEGIN ")) return false;
        return !s.matches("(?s).*\\bAPPLY BATCH\\s*$");
    }

    private static void add(List<Statement> out, StringBuilder cur, int start, int line) {
        String t = cur.toString().strip();
        if (!t.isEmpty()) out.add(new Statement(t, Math.max(start, 0), line));
    }

    private static int count(String s, int from, int to) {
        int c = 0;
        for (int k = from; k < to; k++) if (s.charAt(k) == '\n') c++;
        return c;
    }
}
