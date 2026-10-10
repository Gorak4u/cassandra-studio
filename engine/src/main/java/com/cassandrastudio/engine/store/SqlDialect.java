package com.cassandrastudio.engine.store;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapts Studio's SQL (written for SQLite) to PostgreSQL, so the migrations and every feature's
 * statements run unchanged on both stores (SRV-7). On SQLite everything passes through as is.
 *
 * <ul>
 *   <li>DDL: {@code INTEGER PRIMARY KEY AUTOINCREMENT} becomes {@code BIGSERIAL PRIMARY KEY}; INTEGER
 *       becomes BIGINT (SQLite integers are 64-bit, epoch milliseconds need it); REAL becomes DOUBLE
 *       PRECISION; BLOB becomes BYTEA.</li>
 *   <li>{@code INSERT OR REPLACE INTO t (cols) VALUES (...)} becomes {@code INSERT ... ON CONFLICT (pk) DO
 *       UPDATE SET} the other columns; {@code INSERT OR IGNORE} becomes {@code ON CONFLICT DO NOTHING}.</li>
 *   <li>LIKE becomes ILIKE: SQLite's LIKE ignores ASCII case, PostgreSQL's does not.</li>
 * </ul>
 * New code should prefer {@code INSERT ... ON CONFLICT(...) DO UPDATE}, which both engines run natively.
 */
final class SqlDialect {
    private static final Pattern AUTOINCREMENT = Pattern.compile("(?i)\\bINTEGER\\s+PRIMARY\\s+KEY\\s+AUTOINCREMENT\\b");
    private static final Pattern INTEGER = Pattern.compile("(?i)\\bINTEGER\\b");
    private static final Pattern REAL = Pattern.compile("(?i)\\bREAL\\b");
    private static final Pattern BLOB = Pattern.compile("(?i)\\bBLOB\\b");
    private static final Pattern OR_REPLACE = Pattern.compile(
            "(?is)^\\s*INSERT\\s+OR\\s+REPLACE\\s+INTO\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(VALUES\\s*\\(.*\\)|SELECT\\s.*)\\s*;?\\s*$");
    private static final Pattern OR_IGNORE = Pattern.compile("(?is)^\\s*INSERT\\s+OR\\s+IGNORE\\s+INTO\\s+(.*?)\\s*;?\\s*$");
    private static final Pattern LIKE = Pattern.compile("(?i)(?<![\\w.])LIKE(?!\\w)");

    private final Database.Dialect dialect;
    private final Function<String, List<String>> primaryKey;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    SqlDialect(Database.Dialect dialect, Function<String, List<String>> primaryKey) {
        this.dialect = dialect;
        this.primaryKey = primaryKey;
    }

    /** A migration statement for this store. */
    String ddl(String sql) {
        if (dialect == Database.Dialect.SQLITE) return sql;
        String s = AUTOINCREMENT.matcher(sql).replaceAll("BIGSERIAL PRIMARY KEY");
        s = INTEGER.matcher(s).replaceAll("BIGINT");
        s = REAL.matcher(s).replaceAll("DOUBLE PRECISION");
        s = BLOB.matcher(s).replaceAll("BYTEA");
        return dml(s);
    }

    /** A query or data statement for this store (cached per statement text). */
    String dml(String sql) {
        if (dialect == Database.Dialect.SQLITE) return sql;
        return cache.computeIfAbsent(sql, this::translate);
    }

    private String translate(String sql) {
        String s = sql;
        Matcher m = OR_REPLACE.matcher(s);
        if (m.matches()) {
            String table = m.group(1);
            List<String> cols = List.of(m.group(2).split("\\s*,\\s*")).stream().map(String::strip).toList();
            List<String> pk = primaryKey.apply(table);
            if (pk.isEmpty()) throw new IllegalStateException("INSERT OR REPLACE into " + table + " needs a primary key on PostgreSQL");
            List<String> others = cols.stream().filter(c -> pk.stream().noneMatch(k -> k.equalsIgnoreCase(c))).toList();
            String action = others.isEmpty() ? "DO NOTHING"
                    : "DO UPDATE SET " + String.join(", ", others.stream().map(c -> c + " = EXCLUDED." + c).toList());
            s = "INSERT INTO " + table + " (" + String.join(", ", cols) + ") " + m.group(3).strip()
                    + " ON CONFLICT (" + String.join(", ", pk) + ") " + action;
        } else if (s.matches("(?is)^\\s*INSERT\\s+OR\\s+REPLACE\\b.*")) {
            throw new IllegalStateException("Unsupported INSERT OR REPLACE form on PostgreSQL (name the columns): " + sql);
        } else {
            Matcher ig = OR_IGNORE.matcher(s);
            if (ig.matches()) s = "INSERT INTO " + ig.group(1) + " ON CONFLICT DO NOTHING";
        }
        return LIKE.matcher(s).replaceAll("ILIKE");
    }
}
