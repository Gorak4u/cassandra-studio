package com.cassandrastudio.engine.metrics;

import com.cassandrastudio.engine.cql.ClusterService.NodeInfo;
import java.util.Locale;
import java.util.Map;

/**
 * Two-letter node state like {@code nodetool status} (UN, DN, UJ, UL, UM): Up/Down from gossip
 * as one reachable node sees it, else from the driver; the mode from the node's own
 * OperationMode, else from gossip. "?" means neither source knows whether the node is up.
 */
final class NodeStates {
    private NodeStates() {}

    static String state(NodeInfo n, boolean readOk, String operationMode, NodeReader.Gossip gossip) {
        String endpoint = endpointOf(n, gossip);
        String status;
        if (endpoint != null && gossip.live().contains(endpoint)) status = "U";
        else if (endpoint != null && gossip.unreachable().contains(endpoint)) status = "D";
        else status = switch (n.state() == null ? "UNKNOWN" : n.state()) {
            case "UP" -> "U";
            case "DOWN", "FORCED_DOWN" -> "D";
            default -> readOk ? "U" : "?";
        };
        return status + mode(operationMode, endpoint, gossip);
    }

    private static String mode(String operationMode, String endpoint, NodeReader.Gossip gossip) {
        if (operationMode != null) {
            return switch (operationMode.toUpperCase(Locale.ROOT)) {
                case "JOINING", "STARTING" -> "J";
                case "LEAVING", "DECOMMISSIONED" -> "L";
                case "MOVING" -> "M";
                default -> "N";
            };
        }
        if (endpoint != null) {
            if (gossip.joining().contains(endpoint)) return "J";
            if (gossip.leaving().contains(endpoint)) return "L";
            if (gossip.moving().contains(endpoint)) return "M";
        }
        return "N";
    }

    /** The node's gossip (listen) address: found by host id, else assumed equal to its RPC address. */
    private static String endpointOf(NodeInfo n, NodeReader.Gossip gossip) {
        if (n.hostId() != null) {
            for (Map.Entry<String, String> e : gossip.hostIdByEndpoint().entrySet()) {
                if (n.hostId().equalsIgnoreCase(e.getValue())) return e.getKey();
            }
        }
        boolean known = gossip.live().contains(n.address()) || gossip.unreachable().contains(n.address());
        return known ? n.address() : null;
    }
}
