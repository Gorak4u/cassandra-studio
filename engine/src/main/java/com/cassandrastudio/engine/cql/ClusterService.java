package com.cassandrastudio.engine.cql;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.metadata.Metadata;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.metadata.TokenMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** What the driver knows about the cluster's nodes (CON-5, MON-11). */
public final class ClusterService {
    private final SessionManager sessions;

    public ClusterService(SessionManager sessions) {
        this.sessions = sessions;
    }

    public record NodeInfo(String hostId, String address, int cqlPort, String datacenter, String rack, String version,
                           String state, int tokens, String schemaVersion, int openConnections) {}

    public record ClusterInfo(String name, String partitioner, List<String> datacenters, List<NodeInfo> nodes,
                              boolean schemaAgreement, List<String> versions, String protocolVersion) {}

    public ClusterInfo info(String connectionId) {
        CqlSession s = sessions.session(connectionId);
        Metadata md = s.getMetadata();
        TokenMap tm = md.getTokenMap().orElse(null);
        List<NodeInfo> nodes = new ArrayList<>();
        Set<String> dcs = new HashSet<>();
        Set<String> versions = new HashSet<>();
        Set<String> schemas = new HashSet<>();
        for (Node n : md.getNodes().values()) {
            int port = n.getBroadcastRpcAddress().map(a -> a.getPort()).orElse(9042);
            String version = n.getCassandraVersion() == null ? null : n.getCassandraVersion().toString();
            nodes.add(new NodeInfo(n.getHostId() == null ? null : n.getHostId().toString(), SessionManager.address(n), port,
                    n.getDatacenter(), n.getRack(), version, n.getState().name(),
                    tm == null ? 0 : tm.getTokens(n).size(),
                    n.getSchemaVersion() == null ? null : n.getSchemaVersion().toString(), n.getOpenConnections()));
            if (n.getDatacenter() != null) dcs.add(n.getDatacenter());
            if (version != null) versions.add(version);
            if (n.getSchemaVersion() != null && n.getState() == com.datastax.oss.driver.api.core.metadata.NodeState.UP) {
                schemas.add(n.getSchemaVersion().toString());
            }
        }
        nodes.sort(Comparator.comparing(NodeInfo::datacenter, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(NodeInfo::rack, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(NodeInfo::address));
        return new ClusterInfo(md.getClusterName().orElse(null), tm == null ? null : tm.getPartitionerName(),
                dcs.stream().sorted().toList(), nodes, schemas.size() <= 1, versions.stream().sorted().toList(),
                s.getContext().getProtocolVersion().toString());
    }
}
