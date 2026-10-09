package com.cassandrastudio.engine.bulk;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * RFC 4180 CSV: fields with the delimiter, quotes or line breaks are quoted, quotes doubled.
 * Whether a field was quoted is kept, so a quoted empty field is an empty string while an
 * unquoted one equal to the null string is a null (unload writes them that way).
 */
public final class Csv {
    private Csv() {}

    /** One record: its fields, which of them were quoted, and the line it starts on (1-based). */
    public record Record(List<String> fields, BitSet quoted, long line) {
        public boolean wasQuoted(int i) {
            return quoted.get(i);
        }
    }

    /** A malformed record; the reader has skipped to the next line and can go on. */
    public static final class FormatException extends IOException {
        public final long line;

        FormatException(long line, String message) {
            super("line " + line + ": " + message);
            this.line = line;
        }
    }

    /** Appends one field. {@code isNull} writes the null string unquoted; a value equal to it is quoted. */
    public static void appendField(StringBuilder sb, String value, boolean isNull, char delimiter, String nullString) {
        if (isNull) {
            sb.append(nullString);
            return;
        }
        if (needsQuotes(value, delimiter, nullString)) {
            sb.append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '"') sb.append('"');
                sb.append(c);
            }
            sb.append('"');
        } else {
            sb.append(value);
        }
    }

    static boolean needsQuotes(String v, char delimiter, String nullString) {
        if (v.equals(nullString) || v.isEmpty()) return true;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == delimiter || c == '"' || c == '\n' || c == '\r') return true;
        }
        return false;
    }

    /** A record as one CSV line (for the rejected-rows file), quoting as the original did where needed. */
    public static String line(Record r, char delimiter, String nullString) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < r.fields().size(); i++) {
            if (i > 0) sb.append(delimiter);
            String f = r.fields().get(i);
            boolean asNull = !r.wasQuoted(i) && f.equals(nullString);
            appendField(sb, f, asNull, delimiter, nullString);
        }
        return sb.toString();
    }

    /** Streaming reader; strips a UTF-8 BOM, skips blank lines, handles CRLF and LF. */
    public static final class Reader implements Closeable {
        private final java.io.Reader in;
        private final char delimiter;
        private final char[] buf = new char[1 << 16];
        private int pos;
        private int len;
        private long line = 1;
        private boolean first = true;

        public Reader(java.io.Reader in, char delimiter) {
            this.in = in;
            this.delimiter = delimiter;
        }

        private int peek() throws IOException {
            if (pos >= len) {
                len = in.read(buf, 0, buf.length);
                pos = 0;
                if (len <= 0) {
                    len = 0;
                    return -1;
                }
            }
            return buf[pos];
        }

        private int read() throws IOException {
            int c = peek();
            if (c != -1) pos++;
            return c;
        }

        /** The next record, or null at the end. Throws {@link FormatException} for a malformed one. */
        public Record next() throws IOException {
            if (first) {
                first = false;
                if (peek() == '﻿') read();
            }
            int c;
            while (true) {
                c = peek();
                if (c == -1) return null;
                if (c == '\n') {
                    read();
                    line++;
                } else if (c == '\r') {
                    read();
                    if (peek() == '\n') read();
                    line++;
                } else {
                    break;
                }
            }
            long start = line;
            List<String> fields = new ArrayList<>();
            BitSet quoted = new BitSet();
            StringBuilder sb = new StringBuilder();
            while (true) {
                sb.setLength(0);
                c = read();
                if (c == '"') {
                    quoted.set(fields.size());
                    while (true) {
                        c = read();
                        if (c == -1) throw new FormatException(start, "a quoted field is not closed before the end of the file");
                        if (c == '"') {
                            if (peek() == '"') {
                                read();
                                sb.append('"');
                            } else {
                                break;
                            }
                        } else {
                            if (c == '\n' || c == '\r' && peek() != '\n') line++;
                            sb.append((char) c);
                        }
                    }
                    c = read();
                    if (c != delimiter && c != '\n' && c != '\r' && c != -1) {
                        skipLine(c);
                        throw new FormatException(start, "unexpected text after a closing quote");
                    }
                } else {
                    while (c != -1 && c != delimiter && c != '\n' && c != '\r') {
                        sb.append((char) c);
                        c = read();
                    }
                }
                fields.add(sb.toString());
                if (c == delimiter) continue;
                if (c == '\r' && peek() == '\n') read();
                if (c == '\r' || c == '\n') line++;
                return new Record(fields, quoted, start);
            }
        }

        private void skipLine(int c) throws IOException {
            while (c != -1 && c != '\n' && c != '\r') c = read();
            if (c == '\r' && peek() == '\n') read();
            if (c != -1) line++;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
