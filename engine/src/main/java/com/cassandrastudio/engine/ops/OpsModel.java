package com.cassandrastudio.engine.ops;

import java.util.List;

/** API shapes of the operations feature (docs/api/ops.md). Mirrored by ui/src/panels/ops/opsTypes.ts. */
public final class OpsModel {
    private OpsModel() {}

    /**
     * One nodetool-style view (OPS-1) read from one node.
     *
     * @param command the nodetool equivalent, e.g. "nodetool -h 10.0.0.1 tpstats"
     * @param notes   version or availability remarks shown under the tables
     */
    public record View(String view, String node, String command, List<Section> sections, List<String> notes) {}

    /**
     * A table of a view. Cells are already formatted the way nodetool prints them; null = not
     * available. {@code keyValue} sections have two columns (name, value) and print as "name: value".
     */
    public record Section(String title, List<String> columns, List<List<String>> rows, boolean keyValue) {
        public static Section table(String title, List<String> columns, List<List<String>> rows) {
            return new Section(title, columns, rows, false);
        }

        public static Section keyValues(String title, List<List<String>> rows) {
            return new Section(title, List.of("Name", "Value"), rows, true);
        }
    }

    /** One snapshot of one table on one node (OPS-4). Sizes in bytes when the node reports them parseably. */
    public record Snapshot(String node, String tag, String keyspace, String table, String trueSize, String sizeOnDisk,
                           Long trueSizeBytes, Long sizeOnDiskBytes, String createdAt, String expiresAt) {}

    public record NodeError(String node, String error) {}

    public record SnapshotList(List<Snapshot> snapshots, List<NodeError> errors) {}

    /** Per-node outcome of an operation job, the job's result. */
    public record NodeResult(String node, String status, long ms, String detail) {}
}
