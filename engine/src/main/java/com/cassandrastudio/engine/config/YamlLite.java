package com.cassandrastudio.engine.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small YAML reader for configuration files: cassandra.yaml, Hiera data and hiera.yaml. The
 * engine has no YAML library on its classpath, and these files use a narrow subset: block
 * mappings and sequences, plain and quoted scalars, flow collections, block scalars, anchors and
 * aliases, comments. Every scalar comes back as a String ({@code null} for {@code ~}, {@code null}
 * and empty values); mappings as {@link LinkedHashMap}, sequences as {@link List}. Tags such as
 * {@code !!str} are ignored. Input it cannot follow raises {@link YamlException} with a line number.
 */
public final class YamlLite {
    private static final Pattern KEY = Pattern.compile(
            "^(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^']|'')*'|[^\\s#'\"{\\[\\]\\-?:][^#]*?|-[^\\s#][^#]*?)\\s*:(?:\\s+|$)(.*)$");

    public static final class YamlException extends RuntimeException {
        public YamlException(String message) {
            super(message);
        }
    }

    private final List<String> raw;
    private final List<Line> lines = new ArrayList<>();
    private final Map<String, Object> anchors = new HashMap<>();
    private int pos;

    private static final class Line {
        int indent;
        String text; // comment stripped, trimmed right, without the indent
        final int number;
        final int rawIndex;

        Line(int indent, String text, int number, int rawIndex) {
            this.indent = indent;
            this.text = text;
            this.number = number;
            this.rawIndex = rawIndex;
        }
    }

    private YamlLite(String text) {
        raw = List.of(text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
        for (int i = 0; i < raw.size(); i++) {
            String l = raw.get(i).replace("\t", "    ");
            String body = stripComment(l);
            if (body.isBlank()) continue;
            String t = body.strip();
            if (t.equals("---") || t.equals("...") || t.startsWith("%")) continue;
            int indent = 0;
            while (indent < body.length() && body.charAt(indent) == ' ') indent++;
            lines.add(new Line(indent, body.substring(indent).stripTrailing(), i + 1, i));
        }
    }

    /** Parses one document; an empty document gives null. */
    public static Object parse(String text) {
        YamlLite y = new YamlLite(text == null ? "" : text);
        if (y.lines.isEmpty()) return null;
        Object v = y.node(0);
        if (y.pos < y.lines.size()) {
            Line l = y.lines.get(y.pos);
            throw new YamlException("Unexpected content at line " + l.number + ": " + l.text);
        }
        return v;
    }

    /** Parses a document that must be a mapping (or empty); anything else is an error. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseMap(String text) {
        Object v = parse(text);
        if (v == null) return new LinkedHashMap<>();
        if (!(v instanceof Map)) throw new YamlException("Top level is not a mapping");
        return (Map<String, Object>) v;
    }

    static String stripComment(String l) {
        boolean sq = false;
        boolean dq = false;
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if (c == '\'' && !dq) sq = !sq;
            else if (c == '"' && !sq) dq = !dq;
            else if (c == '\\' && dq) i++;
            else if (c == '#' && !sq && !dq && (i == 0 || Character.isWhitespace(l.charAt(i - 1)))) return l.substring(0, i);
        }
        return l;
    }

    private Line peek() {
        return pos < lines.size() ? lines.get(pos) : null;
    }

    private static boolean isSeqItem(String t) {
        return t.equals("-") || t.startsWith("- ");
    }

    private Object node(int minIndent) {
        Line l = peek();
        if (l == null || l.indent < minIndent) return null;
        if (isSeqItem(l.text)) return sequence(l.indent);
        if (KEY.matcher(l.text).matches() && !l.text.startsWith("{") && !l.text.startsWith("[")) return mapping(l.indent);
        // a scalar (possibly a multi-line flow collection or plain scalar)
        pos++;
        StringBuilder sb = new StringBuilder(l.text);
        if (l.text.startsWith("[") || l.text.startsWith("{")) {
            while (!balanced(sb) && peek() != null) sb.append(' ').append(lines.get(pos++).text);
        } else {
            while (peek() != null && peek().indent > l.indent - 1 && peek().indent >= minIndent
                    && !isSeqItem(peek().text) && !KEY.matcher(peek().text).matches()) {
                sb.append(' ').append(lines.get(pos++).text);
            }
        }
        return value(sb.toString(), l.number);
    }

    private Map<String, Object> mapping(int indent) {
        Map<String, Object> m = new LinkedHashMap<>();
        while (true) {
            Line l = peek();
            if (l == null || l.indent < indent) break;
            if (l.indent > indent) throw new YamlException("Bad indentation at line " + l.number);
            Matcher km = KEY.matcher(l.text);
            if (isSeqItem(l.text) || !km.matches()) break;
            String key = unquote(km.group(1).strip());
            String rest = km.group(2).strip();
            pos++;
            String anchor = null;
            if (rest.startsWith("&")) {
                int sp = rest.indexOf(' ');
                anchor = sp < 0 ? rest.substring(1) : rest.substring(1, sp);
                rest = sp < 0 ? "" : rest.substring(sp + 1).strip();
            }
            rest = stripTag(rest);
            Object v;
            if (rest.isEmpty()) {
                Line n = peek();
                if (n != null && n.indent == indent && isSeqItem(n.text)) v = sequence(indent);
                else v = node(indent + 1);
            } else if (rest.startsWith("|") || rest.startsWith(">")) {
                v = blockScalar(rest, indent);
            } else if ((rest.startsWith("[") || rest.startsWith("{")) && !balanced(new StringBuilder(rest))) {
                StringBuilder sb = new StringBuilder(rest);
                while (!balanced(sb) && peek() != null) sb.append(' ').append(lines.get(pos++).text);
                v = value(sb.toString(), l.number);
            } else {
                v = value(rest, l.number);
            }
            if (anchor != null) anchors.put(anchor, v);
            if (key.equals("<<") && v instanceof Map<?, ?> merge) {
                merge.forEach((k, val) -> m.putIfAbsent(String.valueOf(k), val));
            } else {
                m.put(key, v);
            }
        }
        return m;
    }

    private List<Object> sequence(int indent) {
        List<Object> out = new ArrayList<>();
        while (true) {
            Line l = peek();
            if (l == null || l.indent != indent || !isSeqItem(l.text)) break;
            String content = l.text.length() > 1 ? l.text.substring(1) : "";
            int extra = 1;
            while (extra < l.text.length() && l.text.charAt(extra) == ' ') extra++;
            content = content.strip();
            if (content.isEmpty()) {
                pos++;
                out.add(node(indent + 1));
            } else if (KEY.matcher(content).matches() && !content.startsWith("{") && !content.startsWith("[")) {
                // "- key: value" starts a mapping whose keys sit at the item's content column
                l.indent = indent + extra;
                l.text = content;
                out.add(mapping(l.indent));
            } else {
                l.indent = indent + extra;
                l.text = content;
                out.add(node(l.indent));
            }
        }
        return out;
    }

    private String blockScalar(String header, int parentIndent) {
        boolean literal = header.startsWith("|");
        boolean keep = header.contains("+");
        boolean strip = header.contains("-");
        int startRaw = pos < lines.size() ? lines.get(pos).rawIndex : raw.size();
        // block content: following raw lines that are blank or indented deeper than the parent
        List<String> body = new ArrayList<>();
        int i = lastRawIndex() + 1;
        int blockIndent = -1;
        for (; i < raw.size(); i++) {
            String r = raw.get(i).replace("\t", "    ");
            if (r.isBlank()) {
                body.add("");
                continue;
            }
            int ind = 0;
            while (ind < r.length() && r.charAt(ind) == ' ') ind++;
            if (ind <= parentIndent) break;
            if (blockIndent < 0) blockIndent = ind;
            body.add(r.length() >= blockIndent ? r.substring(Math.min(blockIndent, ind)) : "");
        }
        // skip the parsed lines that belong to the block
        while (pos < lines.size() && lines.get(pos).rawIndex < i) pos++;
        if (startRaw > i) pos = Math.min(pos, lines.size());
        while (!keep && !body.isEmpty() && body.get(body.size() - 1).isEmpty()) body.remove(body.size() - 1);
        String text;
        if (literal) {
            text = String.join("\n", body);
        } else {
            StringBuilder sb = new StringBuilder();
            for (String b : body) {
                if (b.isEmpty()) sb.append('\n');
                else sb.append(sb.isEmpty() || sb.charAt(sb.length() - 1) == '\n' ? "" : " ").append(b);
            }
            text = sb.toString();
        }
        return strip ? text : text + "\n";
    }

    private int lastRawIndex() {
        return pos > 0 ? lines.get(pos - 1).rawIndex : -1;
    }

    private static String stripTag(String s) {
        if (s.startsWith("!")) {
            int sp = s.indexOf(' ');
            return sp < 0 ? "" : s.substring(sp + 1).strip();
        }
        return s;
    }

    private Object value(String s, int lineNo) {
        s = stripTag(s.strip());
        if (s.startsWith("&")) {
            int sp = s.indexOf(' ');
            String name = sp < 0 ? s.substring(1) : s.substring(1, sp);
            Object v = value(sp < 0 ? "" : s.substring(sp + 1), lineNo);
            anchors.put(name, v);
            return v;
        }
        if (s.startsWith("*")) {
            String name = s.substring(1).strip();
            if (!anchors.containsKey(name)) throw new YamlException("Unknown alias *" + name + " at line " + lineNo);
            return anchors.get(name);
        }
        if (s.startsWith("[") || s.startsWith("{")) {
            int[] at = {0};
            Object v = flow(s, at, lineNo);
            return v;
        }
        return scalar(s);
    }

    private static Object scalar(String s) {
        if (s.isEmpty() || s.equals("~") || s.equals("null") || s.equals("Null") || s.equals("NULL")) return null;
        if (s.startsWith("\"") || s.startsWith("'")) return unquote(s);
        return s;
    }

    static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("'") && s.endsWith("'")) {
            return s.substring(1, s.length() - 1).replace("''", "'");
        }
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            String in = s.substring(1, s.length() - 1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < in.length(); i++) {
                char c = in.charAt(i);
                if (c == '\\' && i + 1 < in.length()) {
                    char n = in.charAt(++i);
                    switch (n) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case '0' -> sb.append('\0');
                        default -> sb.append(n);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
        return s;
    }

    private static boolean balanced(CharSequence s) {
        int depth = 0;
        boolean sq = false;
        boolean dq = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && !dq) sq = !sq;
            else if (c == '"' && !sq) dq = !dq;
            else if (!sq && !dq && (c == '[' || c == '{')) depth++;
            else if (!sq && !dq && (c == ']' || c == '}')) depth--;
        }
        return depth <= 0;
    }

    private Object flow(String s, int[] at, int lineNo) {
        skipWs(s, at);
        if (at[0] >= s.length()) return null;
        char c = s.charAt(at[0]);
        if (c == '[') {
            at[0]++;
            List<Object> out = new ArrayList<>();
            while (true) {
                skipWs(s, at);
                if (at[0] >= s.length()) throw new YamlException("Unclosed [ at line " + lineNo);
                if (s.charAt(at[0]) == ']') {
                    at[0]++;
                    return out;
                }
                out.add(flow(s, at, lineNo));
                skipWs(s, at);
                if (at[0] < s.length() && s.charAt(at[0]) == ',') at[0]++;
            }
        }
        if (c == '{') {
            at[0]++;
            Map<String, Object> out = new LinkedHashMap<>();
            while (true) {
                skipWs(s, at);
                if (at[0] >= s.length()) throw new YamlException("Unclosed { at line " + lineNo);
                if (s.charAt(at[0]) == '}') {
                    at[0]++;
                    return out;
                }
                Object k = flowScalar(s, at, true);
                skipWs(s, at);
                Object v = null;
                if (at[0] < s.length() && s.charAt(at[0]) == ':') {
                    at[0]++;
                    v = flow(s, at, lineNo);
                }
                out.put(String.valueOf(k), v);
                skipWs(s, at);
                if (at[0] < s.length() && s.charAt(at[0]) == ',') at[0]++;
            }
        }
        if (c == '*' || c == '&') {
            int start = at[0];
            while (at[0] < s.length() && ",]} ".indexOf(s.charAt(at[0])) < 0) at[0]++;
            return value(s.substring(start, at[0]), lineNo);
        }
        return flowScalar(s, at, false);
    }

    private static Object flowScalar(String s, int[] at, boolean key) {
        char c = s.charAt(at[0]);
        if (c == '"' || c == '\'') {
            int start = at[0]++;
            while (at[0] < s.length()) {
                char d = s.charAt(at[0]);
                if (c == '"' && d == '\\') {
                    at[0] += 2;
                    continue;
                }
                if (d == c) {
                    if (c == '\'' && at[0] + 1 < s.length() && s.charAt(at[0] + 1) == '\'') {
                        at[0] += 2;
                        continue;
                    }
                    at[0]++;
                    break;
                }
                at[0]++;
            }
            return unquote(s.substring(start, at[0]));
        }
        int start = at[0];
        while (at[0] < s.length()) {
            char d = s.charAt(at[0]);
            if (d == ',' || d == ']' || d == '}') break;
            if (key && d == ':' && (at[0] + 1 >= s.length() || " ,}".indexOf(s.charAt(at[0] + 1)) >= 0)) break;
            if (!key && d == ':' && at[0] + 1 < s.length() && s.charAt(at[0] + 1) == ' ') break;
            at[0]++;
        }
        return scalar(s.substring(start, at[0]).strip());
    }

    private static void skipWs(String s, int[] at) {
        while (at[0] < s.length() && Character.isWhitespace(s.charAt(at[0]))) at[0]++;
    }
}
