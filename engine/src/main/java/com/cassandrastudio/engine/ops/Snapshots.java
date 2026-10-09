package com.cassandrastudio.engine.ops;

import static com.cassandrastudio.engine.ops.Beans.MAP;
import static com.cassandrastudio.engine.ops.Beans.STORAGE_SERVICE;
import static com.cassandrastudio.engine.ops.Beans.STR;
import static com.cassandrastudio.engine.ops.Beans.STRS;

import com.cassandrastudio.engine.ops.Beans.Call;
import com.cassandrastudio.engine.ops.OpsModel.Snapshot;
import com.cassandrastudio.engine.util.ApiException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

/** OPS-4: create, list and clear snapshots per node (StorageService, 3.11 to 5.0). */
public final class Snapshots {
    private Snapshots() {}

    static final Pattern TAG = Pattern.compile("[A-Za-z0-9_.\\-]{1,128}");

    /**
     * Take a snapshot. {@code tables} are "keyspace.table" entries and win over {@code keyspaces};
     * both empty = every keyspace.
     */
    public record Create(List<String> nodes, String tag, List<String> keyspaces, List<String> tables, boolean skipFlush) {
        public Create {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            keyspaces = keyspaces == null ? List.of() : List.copyOf(keyspaces);
            tables = tables == null ? List.of() : List.copyOf(tables);
        }
    }

    /** Clear snapshots by tag (and keyspaces); {@code all} clears every snapshot, which needs tag null. */
    public record Clear(List<String> nodes, String tag, List<String> keyspaces, boolean all) {
        public Clear {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            keyspaces = keyspaces == null ? List.of() : List.copyOf(keyspaces);
        }
    }

    static void validate(Create c) {
        if (c.tag() == null || !TAG.matcher(c.tag()).matches()) {
            throw ApiException.badRequest("tag must be 1-128 letters, digits, '_', '.' or '-'");
        }
        c.keyspaces().forEach(k -> Maintenance.name(k, "keyspace"));
        for (String t : c.tables()) {
            String[] p = t.split("\\.", -1);
            if (p.length != 2) throw ApiException.badRequest("tables entries must be keyspace.table, got '" + t + "'");
            Maintenance.name(p[0], "keyspace");
            Maintenance.name(p[1], "table");
        }
    }

    static void validate(Clear c) {
        if (c.all()) {
            if (c.tag() != null) throw ApiException.badRequest("Use either all or tag, not both");
        } else if (c.tag() == null || !TAG.matcher(c.tag()).matches()) {
            throw ApiException.badRequest("tag is required (or all=true to clear every snapshot)");
        }
        c.keyspaces().forEach(k -> Maintenance.name(k, "keyspace"));
    }

    static String preview(Create c, String node) {
        StringBuilder sb = new StringBuilder("nodetool -h ").append(node).append(" snapshot -t ").append(c.tag());
        if (c.skipFlush()) sb.append(" -sf");
        if (!c.tables().isEmpty()) sb.append(" -kt ").append(String.join(",", c.tables()));
        else if (!c.keyspaces().isEmpty()) sb.append(" -- ").append(String.join(" ", c.keyspaces()));
        return sb.toString();
    }

    static String preview(Clear c, String node) {
        StringBuilder sb = new StringBuilder("nodetool -h ").append(node).append(" clearsnapshot");
        sb.append(c.all() ? " --all" : " -t " + c.tag());
        if (!c.keyspaces().isEmpty()) sb.append(" -- ").append(String.join(" ", c.keyspaces()));
        return sb.toString();
    }

    static void take(Beans b, Create c) {
        String[] entities = (c.tables().isEmpty() ? c.keyspaces() : c.tables()).toArray(String[]::new);
        Map<String, String> options = new HashMap<>();
        options.put("skipFlush", String.valueOf(c.skipFlush()));
        b.invoke(STORAGE_SERVICE, "takeSnapshot", Call.of(STR, c.tag(), MAP, options, STRS, entities));
    }

    static void clear(Beans b, Clear c) {
        b.invoke(STORAGE_SERVICE, "clearSnapshot",
                Call.of(STR, c.all() ? "" : c.tag(), STRS, c.keyspaces().toArray(String[]::new)));
    }

    /** Snapshots on one node from SnapshotDetails (attribute on all versions; 4.1+ also an operation). */
    static List<Snapshot> list(Beans b, String node) {
        Object details = b.attr(STORAGE_SERVICE, "SnapshotDetails");
        if (details == null && b.has(STORAGE_SERVICE, "getSnapshotDetails", MAP)) {
            details = b.invoke(STORAGE_SERVICE, "getSnapshotDetails", Call.of(MAP, new HashMap<String, String>()));
        }
        List<Snapshot> out = new ArrayList<>();
        if (!(details instanceof Map<?, ?> m)) return out;
        for (Object v : m.values()) {
            if (!(v instanceof TabularData td)) continue;
            for (Object row : td.values()) {
                if (row instanceof CompositeData cd) out.add(row(node, cd));
            }
        }
        Comparator<String> nulls = Comparator.nullsFirst(Comparator.naturalOrder());
        out.sort(Comparator.comparing(Snapshot::tag, nulls).thenComparing(Snapshot::keyspace, nulls)
                .thenComparing(Snapshot::table, nulls));
        return out;
    }

    static Snapshot row(String node, CompositeData cd) {
        String trueSize = s(cd, "True size"), onDisk = s(cd, "Size on disk");
        String expires = s(cd, "Expiration time");
        return new Snapshot(node, s(cd, "Snapshot name"), s(cd, "Keyspace name"), s(cd, "Column family name"), trueSize, onDisk,
                Fmt.parseSize(trueSize), Fmt.parseSize(onDisk), s(cd, "Creation time"),
                expires == null || expires.equals("null") ? null : expires);
    }

    private static String s(CompositeData cd, String key) {
        return cd.containsKey(key) && cd.get(key) != null ? cd.get(key).toString() : null;
    }
}
