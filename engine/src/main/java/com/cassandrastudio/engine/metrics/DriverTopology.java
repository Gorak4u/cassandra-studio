package com.cassandrastudio.engine.metrics;

import com.cassandrastudio.engine.cql.ClusterService;
import com.cassandrastudio.engine.cql.ClusterService.ClusterInfo;
import com.cassandrastudio.engine.cql.SessionManager;
import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.metadata.Metadata;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.metadata.TokenMap;
import com.datastax.oss.driver.api.core.metadata.token.Token;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** {@link Topology} backed by the open CQL session's metadata. */
public final class DriverTopology implements Topology {
    private final SessionManager sessions;
    private final ClusterService clusters;

    public DriverTopology(SessionManager sessions, ClusterService clusters) {
        this.sessions = sessions;
        this.clusters = clusters;
    }

    @Override
    public ClusterInfo info(String connectionId) {
        return clusters.info(connectionId);
    }

    @Override
    public Map<String, List<String>> tokens(String connectionId) {
        Metadata md = sessions.session(connectionId).getMetadata();
        TokenMap tm = md.getTokenMap().orElse(null);
        Map<String, List<String>> out = new HashMap<>();
        if (tm == null) return out;
        for (Node n : md.getNodes().values()) {
            if (n.getHostId() == null) continue;
            List<String> tokens = new ArrayList<>();
            for (Token t : tm.getTokens(n)) tokens.add(tm.format(t));
            out.put(n.getHostId().toString(), tokens);
        }
        return out;
    }

    @Override
    public List<String> keyspaces(String connectionId) {
        return sessions.session(connectionId).getMetadata().getKeyspaces().keySet().stream()
                .map(CqlIdentifier::asInternal).filter(NodeReader::userKeyspace).sorted().toList();
    }
}
