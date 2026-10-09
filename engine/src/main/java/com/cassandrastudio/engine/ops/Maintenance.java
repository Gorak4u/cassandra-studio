package com.cassandrastudio.engine.ops;

import static com.cassandrastudio.engine.ops.Beans.COMPACTION_MANAGER;
import static com.cassandrastudio.engine.ops.Beans.STORAGE_SERVICE;
import static com.cassandrastudio.engine.ops.Beans.STR;
import static com.cassandrastudio.engine.ops.Beans.STRS;

import com.cassandrastudio.engine.ops.Beans.Call;
import com.cassandrastudio.engine.util.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * OPS-2: flush, major and user-defined compaction, cleanup, scrub, upgradesstables and
 * garbagecollect. Each one has its nodetool preview, its warnings, and the StorageService /
 * CompactionManager call, with the overloads that exist on 3.11, 4.x and 5.0 tried newest first.
 */
public final class Maintenance {
    private Maintenance() {}

    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,222}");

    public enum Kind {
        FLUSH("flush", "Flush", null),
        COMPACT("compact", "Major compaction", "COMPACTION"),
        USER_COMPACT("usercompact", "User-defined compaction", "COMPACTION"),
        CLEANUP("cleanup", "Cleanup", "CLEANUP"),
        SCRUB("scrub", "Scrub", "SCRUB"),
        UPGRADESSTABLES("upgradesstables", "Upgrade SSTables", "UPGRADE_SSTABLES"),
        GARBAGECOLLECT("garbagecollect", "Garbage collect", "GARBAGE_COLLECT");

        public final String id;
        public final String label;
        /** CompactionManager.stopCompaction type to stop it on cancel; null = cannot be stopped. */
        final String stopType;

        Kind(String id, String label, String stopType) {
            this.id = id;
            this.label = label;
            this.stopType = stopType;
        }

        public static Kind of(String id) {
            for (Kind k : values()) {
                if (k.id.equalsIgnoreCase(id)) return k;
            }
            List<String> ids = new ArrayList<>();
            for (Kind k : values()) ids.add(k.id);
            throw ApiException.badRequest("Unknown operation '" + id + "'; one of " + String.join(", ", ids));
        }
    }

    /**
     * What to run. {@code files} only for USER_COMPACT (SSTable Data.db paths on the node);
     * {@code jobs} 0 = Cassandra's default (all compaction threads).
     */
    public record Request(Kind kind, List<String> nodes, String keyspace, List<String> tables, boolean splitOutput, int jobs,
                          boolean disableSnapshot, boolean skipCorrupted, boolean noValidate, boolean reinsertOverflowedTtl,
                          boolean includeAll, String granularity, List<String> files, boolean continueOnError) {
        public Request {
            tables = tables == null ? List.of() : List.copyOf(tables);
            files = files == null ? List.of() : List.copyOf(files);
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            granularity = granularity == null ? "ROW" : granularity.toUpperCase(Locale.ROOT);
        }
    }

    /** 400 with a clear message when the request does not make sense. */
    static void validate(Request r) {
        if (r.kind() == Kind.USER_COMPACT) {
            if (r.files().isEmpty()) throw ApiException.badRequest("files is required: SSTable Data.db paths on the node");
            for (String f : r.files()) {
                if (f.isBlank() || f.contains(",") || !f.endsWith("-Data.db")) {
                    throw ApiException.badRequest("Not an SSTable data file: '" + f + "' (expected a path ending in -Data.db)");
                }
            }
            return;
        }
        if (r.keyspace() == null) throw ApiException.badRequest("keyspace is required");
        name(r.keyspace(), "keyspace");
        r.tables().forEach(t -> name(t, "table"));
        if (r.jobs() < 0 || r.jobs() > 64) throw ApiException.badRequest("jobs must be between 0 and 64");
        if (!r.granularity().equals("ROW") && !r.granularity().equals("CELL")) {
            throw ApiException.badRequest("granularity must be ROW or CELL");
        }
    }

    static void name(String v, String what) {
        if (v == null || !NAME.matcher(v).matches()) throw ApiException.badRequest("Invalid " + what + " name '" + v + "'");
    }

    /** The nodetool command this runs on one node. */
    static String preview(Request r, String node) {
        StringBuilder sb = new StringBuilder("nodetool -h ").append(node).append(' ').append(command(r.kind()));
        switch (r.kind()) {
            case COMPACT -> {
                if (r.splitOutput()) sb.append(" -s");
            }
            case USER_COMPACT -> {
                sb.append(" --user-defined");
                r.files().forEach(f -> sb.append(' ').append(com.cassandrastudio.engine.ssh.NodeShell.quote(f)));
                return sb.toString();
            }
            case SCRUB -> {
                if (r.disableSnapshot()) sb.append(" -ns");
                if (r.skipCorrupted()) sb.append(" -s");
                if (r.noValidate()) sb.append(" -n");
                if (r.reinsertOverflowedTtl()) sb.append(" -r");
            }
            case UPGRADESSTABLES -> {
                if (r.includeAll()) sb.append(" -a");
            }
            case GARBAGECOLLECT -> sb.append(" -g ").append(r.granularity());
            default -> { }
        }
        if (r.jobs() > 0 && r.kind() != Kind.FLUSH && r.kind() != Kind.COMPACT) sb.append(" -j ").append(r.jobs());
        sb.append(" -- ").append(r.keyspace());
        r.tables().forEach(t -> sb.append(' ').append(t));
        return sb.toString();
    }

    private static String command(Kind k) {
        return k == Kind.USER_COMPACT ? "compact" : k.id;
    }

    static List<String> warnings(Request r, int nodeCount) {
        List<String> w = new ArrayList<>();
        switch (r.kind()) {
            case COMPACT -> w.add(r.splitOutput()
                    ? "Major compaction rewrites every SSTable of the table(s); it needs free disk space and adds I/O."
                    : "Major compaction creates one large SSTable per table; with SizeTieredCompactionStrategy it may not "
                            + "be compacted again for a long time. Consider split output (-s).");
            case USER_COMPACT -> w.add("Compacts exactly the listed SSTables together.");
            case CLEANUP -> w.add("Cleanup rewrites all SSTables of the keyspace to drop data the node no longer owns; "
                    + "it needs free disk space and adds I/O. Run it after adding nodes, one node at a time.");
            case SCRUB -> {
                w.add("Scrub rewrites all SSTables" + (r.disableSnapshot() ? " and takes NO snapshot first." : "; a snapshot is taken first."));
                if (r.skipCorrupted()) w.add("Skip corrupted: rows in corrupt partitions are DROPPED (data loss).");
            }
            case UPGRADESSTABLES -> w.add(r.includeAll() ? "Rewrites ALL SSTables, also those already on the current format."
                    : "Rewrites SSTables that are not on the current format.");
            case GARBAGECOLLECT -> w.add("Rewrites SSTables to remove deleted data (" + r.granularity()
                    + " granularity); heavy I/O.");
            default -> { }
        }
        if (nodeCount > 1) {
            w.add("Runs on " + nodeCount + " nodes, one after another" + (r.continueOnError() ? "" : "; stops at the first failure") + ".");
        }
        return w;
    }

    static boolean destructive(Request r) {
        return r.kind() == Kind.SCRUB && r.skipCorrupted();
    }

    static String summary(Request r) {
        String target = r.kind() == Kind.USER_COMPACT ? r.files().size() + " SSTable(s)"
                : r.keyspace() + (r.tables().isEmpty() ? "" : " (" + String.join(", ", r.tables()) + ")");
        return r.kind().label + " " + target;
    }

    /**
     * Runs the operation on one node; blocks until Cassandra finishes it. Returns a short result
     * text; throws {@link OpsException} on failure.
     */
    static String run(Beans b, Request r) {
        String[] tables = r.tables().toArray(String[]::new);
        String ks = r.keyspace();
        int jobs = r.jobs();
        Object status = switch (r.kind()) {
            case FLUSH -> b.invoke(STORAGE_SERVICE, "forceKeyspaceFlush", Call.of(STR, ks, STRS, tables));
            case COMPACT -> b.invoke(STORAGE_SERVICE, "forceKeyspaceCompaction",
                    Call.of("boolean", r.splitOutput(), STR, ks, STRS, tables));
            case USER_COMPACT -> b.invoke(COMPACTION_MANAGER, "forceUserDefinedCompaction", Call.of(STR, String.join(",", r.files())));
            case CLEANUP -> b.invoke(STORAGE_SERVICE, "forceKeyspaceCleanup",
                    Call.of("int", jobs, STR, ks, STRS, tables), Call.of(STR, ks, STRS, tables));
            case SCRUB -> b.invoke(STORAGE_SERVICE, "scrub",
                    Call.of("boolean", r.disableSnapshot(), "boolean", r.skipCorrupted(), "boolean", !r.noValidate(),
                            "boolean", r.reinsertOverflowedTtl(), "int", jobs, STR, ks, STRS, tables),
                    Call.of("boolean", r.disableSnapshot(), "boolean", r.skipCorrupted(), "boolean", !r.noValidate(),
                            "int", jobs, STR, ks, STRS, tables),
                    Call.of("boolean", r.disableSnapshot(), "boolean", r.skipCorrupted(), "boolean", !r.noValidate(),
                            STR, ks, STRS, tables));
            case UPGRADESSTABLES -> b.invoke(STORAGE_SERVICE, "upgradeSSTables",
                    Call.of(STR, ks, "boolean", !r.includeAll(), "int", jobs, STRS, tables),
                    Call.of(STR, ks, "boolean", !r.includeAll(), STRS, tables));
            case GARBAGECOLLECT -> b.invoke(STORAGE_SERVICE, "garbageCollect",
                    Call.of(STR, r.granularity(), "int", jobs, STR, ks, STRS, tables));
        };
        return checkStatus(r.kind(), status);
    }

    /** cleanup, scrub, upgradesstables and garbagecollect return an AllSSTableOpStatus code. */
    static String checkStatus(Kind kind, Object status) {
        if (!(status instanceof Integer code) || code == 0) return "done";
        String why = switch (code) {
            case 1 -> "aborted: another operation is using the SSTables, or the node is shutting down";
            case 2 -> "unable to cancel running compactions on the SSTables";
            default -> "failed (status " + code + "); see the node's system.log";
        };
        throw new OpsException(kind.label + " " + why);
    }
}
