package com.cassandrastudio.engine.metrics;

import com.cassandrastudio.engine.cql.ClusterService.ClusterInfo;
import java.util.List;
import java.util.Map;

/**
 * What the driver knows about a cluster: nodes with state, DC/rack and host id, so a node whose
 * JMX is unreachable still shows correctly. Separate from {@code ClusterService} so tests can
 * fake it without a live cluster.
 */
public interface Topology {
    ClusterInfo info(String connectionId);

    /** Tokens per host id from the driver's token map (fallback when JMX is unreachable). */
    default Map<String, List<String>> tokens(String connectionId) {
        return Map.of();
    }

    /** Non-system keyspaces, sorted. */
    default List<String> keyspaces(String connectionId) {
        return List.of();
    }
}
