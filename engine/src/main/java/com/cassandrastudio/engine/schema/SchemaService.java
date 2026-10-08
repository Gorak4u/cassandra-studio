package com.cassandrastudio.engine.schema;

import com.cassandrastudio.engine.cql.SessionManager;
import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.metadata.Metadata;
import com.datastax.oss.driver.api.core.metadata.schema.ClusteringOrder;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.IndexMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.ViewMetadata;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Schema browser (SCH-1 ... SCH-3). */
public final class SchemaService {
    private final SessionManager sessions;

    public SchemaService(SessionManager sessions) {
        this.sessions = sessions;
    }

    public record ObjectRef(String name, String kind) {}

    public record KeyspaceNode(String name, boolean system, List<ObjectRef> tables, List<ObjectRef> views,
                               List<ObjectRef> indexes, List<ObjectRef> types, List<ObjectRef> functions,
                               List<ObjectRef> aggregates) {}

    public record Tree(String clusterName, List<KeyspaceNode> keyspaces) {}

    public Tree tree(String connectionId, boolean refresh) {
        CqlSession s = sessions.session(connectionId);
        Metadata md = refresh ? s.refreshSchema() : s.getMetadata();
        List<KeyspaceNode> out = new ArrayList<>();
        for (KeyspaceMetadata k : md.getKeyspaces().values()) {
            List<ObjectRef> indexes = new ArrayList<>();
            k.getTables().values().forEach(t -> t.getIndexes().values().forEach(i ->
                    indexes.add(new ObjectRef(t.getName().asInternal() + "." + i.getName().asInternal(), indexKind(i)))));
            out.add(new KeyspaceNode(k.getName().asInternal(), Describer.isSystem(k.getName().asInternal()),
                    names(k.getTables().keySet(), "table"), names(k.getViews().keySet(), "view"),
                    sortRefs(indexes), names(k.getUserDefinedTypes().keySet(), "type"),
                    k.getFunctions().values().stream().map(f -> new ObjectRef(f.getSignature().toString(), "function"))
                            .sorted(Comparator.comparing(ObjectRef::name)).toList(),
                    k.getAggregates().values().stream().map(a -> new ObjectRef(a.getSignature().toString(), "aggregate"))
                            .sorted(Comparator.comparing(ObjectRef::name)).toList()));
        }
        out.sort(Comparator.comparing(KeyspaceNode::system).thenComparing(KeyspaceNode::name));
        return new Tree(md.getClusterName().orElse(null), out);
    }

    public record KeyspaceDetails(String name, boolean system, Map<String, String> replication, boolean durableWrites,
                                  boolean virtual, List<String> tables, List<String> views, List<String> types, String ddl) {}

    public KeyspaceDetails keyspace(String connectionId, String keyspace) {
        KeyspaceMetadata k = Describer.keyspace(sessions.session(connectionId).getMetadata(), keyspace);
        return new KeyspaceDetails(k.getName().asInternal(), Describer.isSystem(k.getName().asInternal()),
                new LinkedHashMap<>(k.getReplication()), k.isDurableWrites(), k.isVirtual(),
                names(k.getTables().keySet(), "table").stream().map(ObjectRef::name).toList(),
                names(k.getViews().keySet(), "view").stream().map(ObjectRef::name).toList(),
                names(k.getUserDefinedTypes().keySet(), "type").stream().map(ObjectRef::name).toList(),
                k.describeWithChildren(true));
    }

    public record ColumnInfo(String name, String type, String kind, int position, String clusteringOrder) {}

    public record IndexInfo(String name, String kind, String target, String className, Map<String, String> options,
                            String ddl) {}

    public record TableDetails(String keyspace, String name, boolean virtual, String id, List<ColumnInfo> columns,
                               List<String> partitionKey, List<String> clusteringColumns, Map<String, Object> options,
                               List<IndexInfo> indexes, List<String> views, String ddl) {}

    public TableDetails table(String connectionId, String keyspace, String table) {
        Metadata md = sessions.session(connectionId).getMetadata();
        KeyspaceMetadata k = Describer.keyspace(md, keyspace);
        TableMetadata t = k.getTable(CqlIdentifier.fromInternal(table))
                .or(() -> k.getTable(CqlIdentifier.fromCql(table)))
                .orElseThrow(() -> new com.cassandrastudio.engine.util.ApiException(404, "not_found", "Table " + table + " not found"));
        List<ColumnInfo> cols = new ArrayList<>();
        List<String> pk = new ArrayList<>();
        int pos = 0;
        for (ColumnMetadata c : t.getPartitionKey()) {
            cols.add(new ColumnInfo(c.getName().asInternal(), c.getType().asCql(false, true), "partition_key", pos++, null));
            pk.add(c.getName().asInternal());
        }
        List<String> ck = new ArrayList<>();
        pos = 0;
        for (Map.Entry<ColumnMetadata, ClusteringOrder> e : t.getClusteringColumns().entrySet()) {
            ColumnMetadata c = e.getKey();
            cols.add(new ColumnInfo(c.getName().asInternal(), c.getType().asCql(false, true), "clustering", pos++, e.getValue().name()));
            ck.add(c.getName().asInternal());
        }
        List<ColumnInfo> regular = new ArrayList<>();
        for (ColumnMetadata c : t.getColumns().values()) {
            String n = c.getName().asInternal();
            if (pk.contains(n) || ck.contains(n)) continue;
            regular.add(new ColumnInfo(n, c.getType().asCql(false, true), c.isStatic() ? "static" : "regular", -1, null));
        }
        regular.sort(Comparator.comparing((ColumnInfo c) -> !c.kind().equals("static")).thenComparing(ColumnInfo::name));
        cols.addAll(regular);

        Map<String, Object> options = new LinkedHashMap<>();
        t.getOptions().forEach((key, v) -> options.put(key.asInternal(), v));
        List<IndexInfo> indexes = new ArrayList<>();
        for (IndexMetadata i : t.getIndexes().values()) {
            indexes.add(new IndexInfo(i.getName().asInternal(), indexKind(i), i.getTarget(), i.getClassName().orElse(null),
                    i.getOptions(), i.describe(true)));
        }
        List<String> views = new ArrayList<>();
        for (ViewMetadata v : k.getViews().values()) {
            if (v.getBaseTable().equals(t.getName())) views.add(v.getName().asInternal());
        }
        return new TableDetails(k.getName().asInternal(), t.getName().asInternal(), t.isVirtual(),
                t.getId().map(Object::toString).orElse(null), cols, pk, ck, options, indexes, views, t.describe(true));
    }

    /** Keyspace/table/column names for editor auto-complete (CQL-1). */
    public Map<String, Map<String, List<String>>> completions(String connectionId) {
        Map<String, Map<String, List<String>>> out = new LinkedHashMap<>();
        for (KeyspaceMetadata k : sessions.session(connectionId).getMetadata().getKeyspaces().values()) {
            Map<String, List<String>> tables = new LinkedHashMap<>();
            k.getTables().values().forEach(t -> tables.put(t.getName().asInternal(),
                    t.getColumns().keySet().stream().map(CqlIdentifier::asInternal).toList()));
            k.getViews().values().forEach(v -> tables.put(v.getName().asInternal(),
                    v.getColumns().keySet().stream().map(CqlIdentifier::asInternal).toList()));
            out.put(k.getName().asInternal(), tables);
        }
        return out;
    }

    private static String indexKind(IndexMetadata i) {
        String cls = i.getClassName().orElse("");
        if (cls.contains("SASI")) return "SASI";
        if (cls.contains("StorageAttachedIndex") || cls.equalsIgnoreCase("sai")) return "SAI";
        return i.getKind().name();
    }

    private static List<ObjectRef> names(java.util.Collection<CqlIdentifier> ids, String kind) {
        return ids.stream().map(CqlIdentifier::asInternal).sorted().map(n -> new ObjectRef(n, kind)).toList();
    }

    private static List<ObjectRef> sortRefs(List<ObjectRef> refs) {
        refs.sort(Comparator.comparing(ObjectRef::name));
        return refs;
    }
}
