package com.cassandrastudio.engine.schema;

import com.cassandrastudio.engine.util.ApiException;
import com.datastax.oss.driver.api.core.CqlIdentifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Generates DDL from the schema forms (SCH-4). The UI always shows the result
 * and runs it through the normal, guarded query path; nothing here executes.
 */
public final class DdlBuilder {
    // Type text and option values go into the statement as-is, so reject anything
    // that could end the statement or start a comment.
    private static final Pattern UNSAFE = Pattern.compile(";|--|//|/\\*");
    private static final Pattern OPTION_NAME = Pattern.compile("[a-z_][a-z0-9_]*");

    private DdlBuilder() {}

    public static String id(String name) {
        if (name == null || name.isBlank()) throw ApiException.badRequest("Name required");
        return CqlIdentifier.fromInternal(name).asCql(true);
    }

    public static String qualified(String keyspace, String name) {
        return id(keyspace) + "." + id(name);
    }

    public static String literal(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    private static String raw(String what, String text) {
        if (text == null || text.isBlank()) throw ApiException.badRequest(what + " required");
        if (UNSAFE.matcher(text).find()) throw ApiException.badRequest(what + " contains ';' or a comment: " + text);
        return text.strip();
    }

    // ---- keyspaces --------------------------------------------------------

    public record KeyspaceSpec(String name, String strategy, Integer replicationFactor, Map<String, Integer> datacenters,
                               Boolean durableWrites, Boolean ifNotExists) {}

    public static String replication(KeyspaceSpec k) {
        String strategy = k.strategy() == null ? "NetworkTopologyStrategy" : k.strategy();
        Map<String, String> r = new LinkedHashMap<>();
        if (strategy.endsWith("SimpleStrategy")) {
            r.put("class", "SimpleStrategy");
            int rf = k.replicationFactor() == null ? 1 : k.replicationFactor();
            if (rf < 1) throw ApiException.badRequest("Replication factor must be at least 1");
            r.put("replication_factor", String.valueOf(rf));
        } else if (strategy.endsWith("NetworkTopologyStrategy")) {
            r.put("class", "NetworkTopologyStrategy");
            if (k.datacenters() == null || k.datacenters().isEmpty()) {
                throw ApiException.badRequest("NetworkTopologyStrategy needs at least one datacenter");
            }
            k.datacenters().forEach((dc, rf) -> {
                if (rf == null || rf < 0) throw ApiException.badRequest("Bad replication factor for " + dc);
                r.put(dc, String.valueOf(rf));
            });
        } else {
            throw ApiException.badRequest("Unknown replication strategy " + strategy);
        }
        StringBuilder b = new StringBuilder("{");
        r.forEach((key, v) -> b.append(b.length() > 1 ? ", " : "").append(literal(key)).append(": ")
                .append(literal(v)));
        return b.append('}').toString();
    }

    public static String createKeyspace(KeyspaceSpec k) {
        return "CREATE KEYSPACE " + (Boolean.TRUE.equals(k.ifNotExists()) ? "IF NOT EXISTS " : "") + id(k.name())
                + "\n  WITH replication = " + replication(k)
                + (Boolean.FALSE.equals(k.durableWrites()) ? "\n  AND durable_writes = false" : "") + ";";
    }

    public static String alterKeyspace(KeyspaceSpec k) {
        return "ALTER KEYSPACE " + id(k.name()) + "\n  WITH replication = " + replication(k)
                + (k.durableWrites() == null ? "" : "\n  AND durable_writes = " + k.durableWrites()) + ";";
    }

    public static String dropKeyspace(String name) {
        return "DROP KEYSPACE " + id(name) + ";";
    }

    // ---- tables -----------------------------------------------------------

    public record ColumnSpec(String name, String type, Boolean isStatic) {}

    public record ClusteringSpec(String name, String order) {}

    public record TableSpec(String keyspace, String name, List<ColumnSpec> columns, List<String> partitionKey,
                            List<ClusteringSpec> clustering, Map<String, String> options, Boolean ifNotExists) {}

    public static String createTable(TableSpec t) {
        if (t.columns() == null || t.columns().isEmpty()) throw ApiException.badRequest("A table needs columns");
        if (t.partitionKey() == null || t.partitionKey().isEmpty()) throw ApiException.badRequest("A table needs a partition key");
        List<String> names = t.columns().stream().map(ColumnSpec::name).toList();
        for (String pk : t.partitionKey()) {
            if (!names.contains(pk)) throw ApiException.badRequest("Partition key column " + pk + " is not defined");
        }
        List<ClusteringSpec> clustering = t.clustering() == null ? List.of() : t.clustering();
        for (ClusteringSpec c : clustering) {
            if (!names.contains(c.name())) throw ApiException.badRequest("Clustering column " + c.name() + " is not defined");
        }
        StringBuilder b = new StringBuilder("CREATE TABLE ")
                .append(Boolean.TRUE.equals(t.ifNotExists()) ? "IF NOT EXISTS " : "")
                .append(qualified(t.keyspace(), t.name())).append(" (\n");
        for (ColumnSpec c : t.columns()) {
            b.append("  ").append(id(c.name())).append(' ').append(raw("Type of " + c.name(), c.type()));
            if (Boolean.TRUE.equals(c.isStatic())) {
                if (clustering.isEmpty()) throw ApiException.badRequest("Static columns need clustering columns");
                b.append(" STATIC");
            }
            b.append(",\n");
        }
        String pk = t.partitionKey().size() == 1 ? id(t.partitionKey().get(0))
                : "(" + String.join(", ", t.partitionKey().stream().map(DdlBuilder::id).toList()) + ")";
        b.append("  PRIMARY KEY (").append(pk);
        for (ClusteringSpec c : clustering) b.append(", ").append(id(c.name()));
        b.append(")\n)");
        List<String> with = new ArrayList<>();
        if (clustering.stream().anyMatch(c -> c.order() != null && !c.order().isBlank())) {
            with.add("CLUSTERING ORDER BY (" + String.join(", ", clustering.stream()
                    .map(c -> id(c.name()) + " " + order(c.order())).toList()) + ")");
        }
        with.addAll(options(t.options()));
        if (!with.isEmpty()) b.append(" WITH ").append(String.join("\n  AND ", with));
        return b.append(';').toString();
    }

    public static String alterTableOptions(String keyspace, String table, Map<String, String> options) {
        List<String> o = options(options);
        if (o.isEmpty()) throw ApiException.badRequest("No options to change");
        return "ALTER TABLE " + qualified(keyspace, table) + " WITH " + String.join("\n  AND ", o) + ";";
    }

    public static String addColumn(String keyspace, String table, ColumnSpec c) {
        return "ALTER TABLE " + qualified(keyspace, table) + " ADD " + id(c.name()) + " " + raw("Type", c.type())
                + (Boolean.TRUE.equals(c.isStatic()) ? " STATIC" : "") + ";";
    }

    public static String dropColumn(String keyspace, String table, String column) {
        return "ALTER TABLE " + qualified(keyspace, table) + " DROP " + id(column) + ";";
    }

    public static String dropTable(String keyspace, String table) {
        return "DROP TABLE " + qualified(keyspace, table) + ";";
    }

    public static String truncate(String keyspace, String table) {
        return "TRUNCATE " + qualified(keyspace, table) + ";";
    }

    // ---- types, indexes, views -------------------------------------------

    public record TypeSpec(String keyspace, String name, List<ColumnSpec> fields) {}

    public static String createType(TypeSpec t) {
        if (t.fields() == null || t.fields().isEmpty()) throw ApiException.badRequest("A type needs fields");
        return "CREATE TYPE " + qualified(t.keyspace(), t.name()) + " (\n"
                + String.join(",\n", t.fields().stream().map(f -> "  " + id(f.name()) + " " + raw("Type", f.type())).toList())
                + "\n);";
    }

    public static String dropType(String keyspace, String name) {
        return "DROP TYPE " + qualified(keyspace, name) + ";";
    }

    public record IndexSpec(String keyspace, String table, String name, String column, String target, String using) {}

    /** target: null (values), "keys", "entries", "full"; using: null (2i), "sai", "SASI" class or other class. */
    public static String createIndex(IndexSpec i) {
        String col = id(i.column());
        String target = i.target() == null || i.target().isBlank() ? col
                : switch (i.target().toLowerCase(Locale.ROOT)) {
                    case "keys" -> "KEYS(" + col + ")";
                    case "entries" -> "ENTRIES(" + col + ")";
                    case "full" -> "FULL(" + col + ")";
                    case "values" -> "VALUES(" + col + ")";
                    default -> throw ApiException.badRequest("Unknown index target " + i.target());
                };
        String using = "";
        if (i.using() != null && !i.using().isBlank()) {
            String u = i.using().strip();
            using = u.equalsIgnoreCase("sai") ? " USING 'sai'"
                    : u.equalsIgnoreCase("sasi") ? " USING 'org.apache.cassandra.index.sasi.SASIIndex'"
                    : " USING " + literal(raw("Index class", u));
        }
        return "CREATE INDEX " + (i.name() == null || i.name().isBlank() ? "" : id(i.name()) + " ")
                + "ON " + qualified(i.keyspace(), i.table()) + " (" + target + ")" + using + ";";
    }

    public static String dropIndex(String keyspace, String name) {
        return "DROP INDEX " + qualified(keyspace, name) + ";";
    }

    public static String dropView(String keyspace, String name) {
        return "DROP MATERIALIZED VIEW " + qualified(keyspace, name) + ";";
    }

    // ---- helpers ------------------------------------------------------------

    private static String order(String o) {
        String u = o == null ? "ASC" : o.strip().toUpperCase(Locale.ROOT);
        if (!u.equals("ASC") && !u.equals("DESC")) throw ApiException.badRequest("Clustering order must be ASC or DESC");
        return u;
    }

    /** Option values are CQL literals as typed in the form, e.g. 864000 or {'class': 'LeveledCompactionStrategy'}. */
    private static List<String> options(Map<String, String> options) {
        List<String> out = new ArrayList<>();
        if (options == null) return out;
        options.forEach((k, v) -> {
            String key = k.strip().toLowerCase(Locale.ROOT);
            if (!OPTION_NAME.matcher(key).matches()) throw ApiException.badRequest("Bad option name " + k);
            out.add(key + " = " + raw("Value of " + k, v));
        });
        return out;
    }
}
