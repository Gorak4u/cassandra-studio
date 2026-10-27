package com.cassandrastudio.engine.cql;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.metadata.Metadata;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.metadata.NodeState;
import com.datastax.oss.driver.api.core.metadata.TokenMap;
import com.datastax.oss.driver.api.core.servererrors.InvalidQueryException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the driver knows about the cluster's nodes (CON-5, MON-11). A node's state is the cluster's own
 * view where Studio can get it: gossip heartbeats over CQL ({@link GossipLiveness}, Cassandra 4.1+).
 * Otherwise the driver's state, except that a node Studio has never been able to connect to is
 * UNKNOWN rather than DOWN: the driver only knows its own connections, and nodes whose addresses
 * this computer cannot reach are usually healthy.
 */
public final class ClusterService {
    private final SessionManager sessions;
    private final GossipLiveness gossip = new GossipLiveness();
    /** Host ids the driver has seen UP, per connection: such a node going DOWN is a real change. */
    private final Map<String, Set<String>> everUp = new ConcurrentHashMap<>();

    public ClusterService(SessionManager sessions) {
        this.sessions = sessions;
    }

    /** Drops what is remembered about a connection's nodes (disconnect, edit, delete). */
    public void forget(String connectionId) {
        gossip.forget(connectionId);
        everUp.remove(connectionId);
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
        Map<String, String> cluster = gossip.view(connectionId, () -> readGossip(s));
        Set<String> seenUp = everUp.computeIfAbsent(connectionId, k -> ConcurrentHashMap.newKeySet());
        for (Node n : md.getNodes().values()) {
            int port = n.getBroadcastRpcAddress().map(a -> a.getPort()).orElse(9042);
            String version = n.getCassandraVersion() == null ? null : n.getCassandraVersion().toString();
            nodes.add(new NodeInfo(n.getHostId() == null ? null : n.getHostId().toString(), SessionManager.address(n), port,
                    n.getDatacenter(), n.getRack(), version, state(n, cluster, seenUp),
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

    /** UP / DOWN / UNKNOWN for one node; see the class comment. */
    static String state(Node n, Map<String, String> cluster, Set<String> everUp) {
        String id = n.getHostId() == null ? null : n.getHostId().toString().toLowerCase(Locale.ROOT);
        NodeState driver = n.getState();
        if (driver == NodeState.UP && id != null) everUp.add(id);
        String fromGossip = cluster == null || id == null ? null : cluster.get(id);
        if (driver == NodeState.UP) return "UP"; // Studio is talking to it right now
        if ("UP".equals(fromGossip) || "DOWN".equals(fromGossip)) return fromGossip;
        if (driver == NodeState.DOWN || driver == NodeState.FORCED_DOWN) {
            return id != null && everUp.contains(id) ? "DOWN" : "UNKNOWN";
        }
        return "UNKNOWN";
    }

    /** gossip_info rows from whichever node coordinates; null when the table does not exist (before 4.1). */
    private static List<GossipLiveness.Beat> readGossip(CqlSession s) {
        try {
            List<GossipLiveness.Beat> out = new ArrayList<>();
            for (Row r : s.execute(SimpleStatement.newInstance(
                    "SELECT host_id, generation, heartbeat, status FROM system_views.gossip_info")
                    .setTimeout(java.time.Duration.ofSeconds(3)))) {
                out.add(new GossipLiveness.Beat(r.getString("host_id"), r.isNull("generation") ? 0 : r.getInt("generation"),
                        r.isNull("heartbeat") ? 0 : r.getInt("heartbeat"), r.getString("status")));
            }
            return out;
        } catch (InvalidQueryException e) {
            return null;
        }
    }
}
