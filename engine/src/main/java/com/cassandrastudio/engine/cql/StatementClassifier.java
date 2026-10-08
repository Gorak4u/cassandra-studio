package com.cassandrastudio.engine.cql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides what a statement does, for the safety guard (NFR-SAFE) and for the
 * risky-statement warnings (CQL-14).
 */
public final class StatementClassifier {

    public enum Kind {
        /** Reads only: SELECT, LIST ..., DESCRIBE. */
        READ,
        /** Changes data: INSERT, UPDATE, DELETE, BATCH, TRUNCATE. */
        WRITE,
        /** Changes schema: CREATE / ALTER / DROP of keyspaces, tables, types, ... */
        DDL,
        /** Changes access: roles, users, GRANT, REVOKE. */
        DCL,
        /** Handled by Studio itself: USE, CONSISTENCY, ... */
        CLIENT,
        UNKNOWN
    }

    public record Classification(Kind kind, String verb, List<String> warnings, boolean destructive) {
        public boolean changesSomething() {
            return kind == Kind.WRITE || kind == Kind.DDL || kind == Kind.DCL || kind == Kind.UNKNOWN;
        }
    }

    private static final Pattern WHERE = Pattern.compile("\\bWHERE\\b");
    private static final Pattern LIMIT = Pattern.compile("\\bLIMIT\\b");
    private static final Pattern ALLOW_FILTERING = Pattern.compile("\\bALLOW\\s+FILTERING\\b");
    private static final Pattern UNLOGGED_BATCH = Pattern.compile("^BEGIN\\s+UNLOGGED\\s+BATCH\\b");
    private static final Pattern ACCESS_OBJECT = Pattern.compile("^(CREATE|ALTER|DROP)\\s+(ROLE|USER)\\b");

    private StatementClassifier() {}

    public static Classification classify(String statement) {
        String s = normalise(statement);
        String[] words = s.split(" ", 3);
        String first = words.length > 0 ? words[0] : "";
        String second = words.length > 1 ? words[1] : "";
        List<String> warnings = new ArrayList<>();
        Kind kind;
        boolean destructive = false;
        switch (first) {
            case "SELECT" -> {
                kind = Kind.READ;
                if (ALLOW_FILTERING.matcher(s).find()) {
                    warnings.add("ALLOW FILTERING can scan every partition on every node; it is slow and loads the cluster.");
                }
                if (!WHERE.matcher(s).find() && !s.contains("SYSTEM")) {
                    warnings.add(LIMIT.matcher(s).find()
                            ? "No WHERE clause: this reads across all partitions (full table scan), limited by LIMIT."
                            : "No WHERE clause and no LIMIT: this is a full table scan.");
                }
            }
            case "LIST", "DESCRIBE", "DESC" -> kind = Kind.READ;
            case "INSERT", "UPDATE", "DELETE" -> kind = Kind.WRITE;
            case "BEGIN" -> {
                kind = Kind.WRITE;
                if (UNLOGGED_BATCH.matcher(s).find()) {
                    warnings.add("UNLOGGED batches across several partitions are not atomic and add coordinator load.");
                }
            }
            case "TRUNCATE" -> {
                kind = Kind.WRITE;
                destructive = true;
                warnings.add("TRUNCATE deletes ALL data in the table on every node.");
            }
            case "GRANT", "REVOKE" -> kind = Kind.DCL;
            case "CREATE", "ALTER", "DROP" -> {
                kind = ACCESS_OBJECT.matcher(s).find() ? Kind.DCL : Kind.DDL;
                if (first.equals("DROP")) {
                    destructive = true;
                    warnings.add("DROP " + second + " removes it and, for keyspaces and tables, all of its data.");
                }
            }
            case "USE", "CONSISTENCY", "SERIAL", "TRACING", "PAGING", "EXPAND" -> kind = Kind.CLIENT;
            default -> kind = Kind.UNKNOWN;
        }
        return new Classification(kind, first, List.copyOf(warnings), destructive);
    }

    /** Upper case, comments removed, string contents blanked, whitespace collapsed. */
    static String normalise(String statement) {
        StringBuilder b = new StringBuilder();
        int n = statement.length();
        for (int i = 0; i < n; i++) {
            char c = statement.charAt(i);
            char next = i + 1 < n ? statement.charAt(i + 1) : '\0';
            if (c == '-' && next == '-' || c == '/' && next == '/') {
                while (i < n && statement.charAt(i) != '\n') i++;
                b.append(' ');
            } else if (c == '/' && next == '*') {
                int end = statement.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 1;
                b.append(' ');
            } else if (c == '\'' || c == '$' && next == '$') {
                String close = c == '\'' ? "'" : "$$";
                int j = i + close.length();
                while (j < n) {
                    if (statement.startsWith(close, j)) {
                        if (c == '\'' && j + 1 < n && statement.charAt(j + 1) == '\'') { j += 2; continue; }
                        break;
                    }
                    j++;
                }
                b.append("''");
                i = Math.min(j + close.length() - 1, n);
            } else {
                b.append(c);
            }
        }
        return b.toString().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
}
