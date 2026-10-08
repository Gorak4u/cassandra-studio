package com.cassandrastudio.engine.schema;

import com.cassandrastudio.engine.util.ApiException;
import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.metadata.Metadata;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.ViewMetadata;
import com.datastax.oss.driver.api.core.type.UserDefinedType;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * cqlsh-style DESCRIBE, answered from driver metadata so it works on every
 * Cassandra version (server-side DESCRIBE only exists from 4.0) (SCH-5).
 */
public final class Describer {
    public static final Set<String> SYSTEM_KEYSPACES = Set.of("system", "system_auth", "system_schema",
            "system_distributed", "system_traces", "system_views", "system_virtual_schema", "dse_system", "dse_security",
            "dse_auth", "dse_leases", "dse_perf", "dse_insights", "dse_insights_local", "dse_system_local", "solr_admin",
            "cfs", "cfs_archive", "dsefs", "OpsCenter", "reaper_db");

    private Describer() {}

    public static boolean isSystem(String keyspace) {
        return SYSTEM_KEYSPACES.contains(keyspace);
    }

    public static String describe(CqlSession session, String statement, String currentKeyspace) {
        String s = statement.strip().replaceAll(";\\s*$", "");
        String[] parts = s.split("\\s+", 2);
        String rest = parts.length > 1 ? parts[1].strip() : "";
        Metadata md = session.getMetadata();
        String upper = rest.toUpperCase(Locale.ROOT);

        if (upper.equals("KEYSPACES")) {
            return md.getKeyspaces().keySet().stream().map(k -> k.asCql(true)).sorted().collect(Collectors.joining("  "));
        }
        if (upper.equals("CLUSTER")) {
            StringBuilder b = new StringBuilder("Cluster: ").append(md.getClusterName().orElse("?")).append('\n');
            md.getTokenMap().ifPresent(tm -> b.append("Partitioner: ").append(tm.getPartitionerName()).append('\n'));
            return b.toString();
        }
        if (upper.equals("SCHEMA") || upper.equals("FULL SCHEMA")) {
            boolean full = upper.startsWith("FULL");
            return md.getKeyspaces().values().stream()
                    .filter(k -> full || !isSystem(k.getName().asInternal()))
                    .sorted((a, b) -> a.getName().asInternal().compareTo(b.getName().asInternal()))
                    .map(k -> k.describeWithChildren(true)).collect(Collectors.joining("\n\n"));
        }
        if (upper.equals("TABLES") || upper.equals("COLUMNFAMILIES")) {
            StringBuilder b = new StringBuilder();
            for (KeyspaceMetadata k : sorted(md)) {
                if (currentKeyspace != null && !k.getName().asInternal().equals(currentKeyspace)) continue;
                b.append("Keyspace ").append(k.getName().asCql(true)).append('\n')
                        .append(k.getTables().keySet().stream().map(t -> t.asCql(true)).sorted().collect(Collectors.joining("  ")))
                        .append("\n\n");
            }
            return b.toString().strip();
        }
        if (upper.equals("TYPES")) {
            StringBuilder b = new StringBuilder();
            for (KeyspaceMetadata k : sorted(md)) {
                if (k.getUserDefinedTypes().isEmpty()) continue;
                b.append("Keyspace ").append(k.getName().asCql(true)).append('\n')
                        .append(k.getUserDefinedTypes().keySet().stream().map(t -> t.asCql(true)).sorted().collect(Collectors.joining("  ")))
                        .append("\n\n");
            }
            return b.toString().strip();
        }
        String[] kw = rest.split("\\s+", 2);
        String kind = kw[0].toUpperCase(Locale.ROOT);
        String name = kw.length > 1 ? kw[1].strip() : "";
        switch (kind) {
            case "KEYSPACE" -> {
                String ks = name.isEmpty() ? requireKs(currentKeyspace) : name;
                return keyspace(md, ks).describeWithChildren(true);
            }
            case "TABLE", "COLUMNFAMILY" -> {
                return table(md, name, currentKeyspace).describe(true);
            }
            case "TYPE" -> {
                QualifiedName q = QualifiedName.parse(name, currentKeyspace);
                UserDefinedType t = keyspace(md, q.keyspace()).getUserDefinedType(CqlIdentifier.fromCql(q.nameCql()))
                        .orElseThrow(() -> new ApiException(404, "not_found", "Type " + name + " not found"));
                return t.describe(true);
            }
            case "MATERIALIZED" -> {
                String viewName = name.replaceFirst("(?i)^VIEW\\s+", "");
                QualifiedName q = QualifiedName.parse(viewName, currentKeyspace);
                ViewMetadata v = keyspace(md, q.keyspace()).getView(CqlIdentifier.fromCql(q.nameCql()))
                        .orElseThrow(() -> new ApiException(404, "not_found", "View " + viewName + " not found"));
                return v.describe(true);
            }
            case "INDEX" -> {
                QualifiedName q = QualifiedName.parse(name, currentKeyspace);
                for (TableMetadata t : keyspace(md, q.keyspace()).getTables().values()) {
                    var idx = t.getIndex(CqlIdentifier.fromCql(q.nameCql()));
                    if (idx.isPresent()) return idx.get().describe(true);
                }
                throw new ApiException(404, "not_found", "Index " + name + " not found");
            }
            default -> {
                // DESCRIBE <name>: a keyspace, or a table in the current keyspace.
                if (rest.isEmpty()) throw ApiException.badRequest("DESCRIBE what?");
                Optional<KeyspaceMetadata> ks = md.getKeyspace(CqlIdentifier.fromCql(rest));
                if (ks.isPresent() && !rest.contains(".")) return ks.get().describeWithChildren(true);
                return table(md, rest, currentKeyspace).describe(true);
            }
        }
    }

    private static List<KeyspaceMetadata> sorted(Metadata md) {
        return md.getKeyspaces().values().stream()
                .sorted((a, b) -> a.getName().asInternal().compareTo(b.getName().asInternal())).toList();
    }

    private static String requireKs(String ks) {
        if (ks == null) throw ApiException.badRequest("No keyspace selected; run USE <keyspace> or name one");
        return ks;
    }

    static KeyspaceMetadata keyspace(Metadata md, String ks) {
        CqlIdentifier id = ks.startsWith("\"") ? CqlIdentifier.fromCql(ks) : CqlIdentifier.fromInternal(ks);
        return md.getKeyspace(id).or(() -> md.getKeyspace(CqlIdentifier.fromCql(ks)))
                .orElseThrow(() -> new ApiException(404, "not_found", "Keyspace " + ks + " not found"));
    }

    static TableMetadata table(Metadata md, String name, String currentKeyspace) {
        QualifiedName q = QualifiedName.parse(name, currentKeyspace);
        return keyspace(md, q.keyspace()).getTable(CqlIdentifier.fromCql(q.nameCql()))
                .orElseThrow(() -> new ApiException(404, "not_found", "Table " + name + " not found"));
    }

    /** "ks.name" or "name" (+ current keyspace); keyspace is internal form, name kept as CQL text. */
    record QualifiedName(String keyspace, String nameCql) {
        static QualifiedName parse(String text, String currentKeyspace) {
            if (text == null || text.isBlank()) throw ApiException.badRequest("Name required");
            String t = text.strip();
            int dot = dotOutsideQuotes(t);
            if (dot < 0) return new QualifiedName(requireKs(currentKeyspace), t);
            String ks = t.substring(0, dot);
            return new QualifiedName(CqlIdentifier.fromCql(ks).asInternal(), t.substring(dot + 1));
        }

        private static int dotOutsideQuotes(String s) {
            boolean q = false;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"') q = !q;
                else if (c == '.' && !q) return i;
            }
            return -1;
        }
    }
}
