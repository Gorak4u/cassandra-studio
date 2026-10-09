package com.cassandrastudio.engine.config;

import java.util.List;
import java.util.Map;

/** JSON shapes of the config API (docs/api/config.md); mirrored in ui/src/panels/config/configTypes.ts. */
public final class ConfigModel {
    private ConfigModel() {}

    /** Setting categories: cassandra.yaml, JVM (flags, system properties) and OS (limits, kernel). */
    public static final String YAML = "yaml";
    public static final String JVM = "jvm";
    public static final String OS = "os";

    /**
     * One effective setting on one node. {@code name} is the canonical (newest) name and
     * {@code value} the normalised value used for comparison; {@code rawName}/{@code raw} are what
     * the node reported. {@code source} says where it was read (settings table, file, JMX, SSH).
     */
    public record Setting(String category, String name, String value, String rawName, String raw, String source) {}

    /** Everything read from one node. {@code notices} explain what could not be read and why. */
    public record NodeConfig(String address, String hostId, String datacenter, String rack, String version,
                             Map<String, String> sources, List<Setting> settings, List<String> notices) {}

    public record Snapshot(long collectedAtMs, List<NodeConfig> nodes) {}

    public record NodeRef(String address, String datacenter, String rack, String version) {}

    /**
     * One setting across nodes. {@code values} by node address (absent = the node does not report
     * it). {@code differsInCluster}: values differ between nodes; {@code dcsDiffering}: the DCs in
     * which they differ. {@code expected}: Hiera's value per node, {@code expectedSource} the data
     * file it came from, {@code mismatches} the nodes whose value is not the expected one.
     */
    public record DriftRow(String category, String name, boolean perNode, Map<String, String> values,
                           boolean differsInCluster, List<String> dcsDiffering, List<String> missingOn,
                           Map<String, String> expected, Map<String, String> expectedSource,
                           List<String> mismatches) {}

    public record DriftSummary(int settings, int differInCluster, int differInDc, int hieraCompared,
                               int hieraMismatches) {}

    public record DriftReport(String scope, boolean onlyDifferences, long collectedAtMs, List<NodeRef> nodes,
                              List<DriftRow> rows, DriftSummary summary, HieraStatus hiera) {}

    /** Hiera comparison settings, stored per connection. */
    public record HieraSettings(boolean enabled, String repoPath, Map<String, String> facts,
                                Map<String, String> certnames) {}

    /** What the Hiera comparison used per node: the data files that matched, or why it could not run. */
    public record HieraStatus(boolean enabled, String error, Map<String, List<String>> layersByNode,
                              int mappedKeys) {}

    /** Values found in the control repo, for the fact pickers. */
    public record HieraOptions(String repoPath, boolean found, String error, Map<String, List<String>> values,
                               List<String> variables) {}
}
