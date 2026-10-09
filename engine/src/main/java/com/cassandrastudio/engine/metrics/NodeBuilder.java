package com.cassandrastudio.engine.metrics;

import com.cassandrastudio.engine.metrics.MonitoringModel.ClientRequests;
import com.cassandrastudio.engine.metrics.MonitoringModel.DataDir;
import com.cassandrastudio.engine.metrics.MonitoringModel.GcCollector;
import com.cassandrastudio.engine.metrics.MonitoringModel.NodeSnapshot;
import com.cassandrastudio.engine.metrics.MonitoringModel.ThreadPool;
import java.util.List;
import java.util.Map;

/** Mutable staging for the 33-field {@link NodeSnapshot} record. */
final class NodeBuilder {
    String hostId, address, datacenter, rack, state, route, error;
    String cassandraVersion, javaVersion, javaVendor;
    Long uptimeSec, loadBytes;
    Integer tokens;
    Long heapUsedBytes, heapMaxBytes, offHeapBytes;
    List<GcCollector> gc;
    Double gcTimePct, cpuProcessPct, cpuSystemPct;
    Long openFds, maxFds, pendingCompactions, activeCompactions, completedCompactions, hintsInProgress, totalHints;
    List<ThreadPool> threadPools;
    Map<String, Long> dropped;
    ClientRequests clientRequests;
    Long liveSSTables;
    List<DataDir> dataDirs;

    static NodeBuilder of(NodeSnapshot n) {
        NodeBuilder b = new NodeBuilder();
        b.hostId = n.hostId();
        b.address = n.address();
        b.datacenter = n.datacenter();
        b.rack = n.rack();
        b.state = n.state();
        b.route = n.route();
        b.error = n.error();
        b.cassandraVersion = n.cassandraVersion();
        b.javaVersion = n.javaVersion();
        b.javaVendor = n.javaVendor();
        b.uptimeSec = n.uptimeSec();
        b.loadBytes = n.loadBytes();
        b.tokens = n.tokens();
        b.heapUsedBytes = n.heapUsedBytes();
        b.heapMaxBytes = n.heapMaxBytes();
        b.offHeapBytes = n.offHeapBytes();
        b.gc = n.gc();
        b.gcTimePct = n.gcTimePct();
        b.cpuProcessPct = n.cpuProcessPct();
        b.cpuSystemPct = n.cpuSystemPct();
        b.openFds = n.openFds();
        b.maxFds = n.maxFds();
        b.pendingCompactions = n.pendingCompactions();
        b.activeCompactions = n.activeCompactions();
        b.completedCompactions = n.completedCompactions();
        b.hintsInProgress = n.hintsInProgress();
        b.totalHints = n.totalHints();
        b.threadPools = n.threadPools();
        b.dropped = n.dropped();
        b.clientRequests = n.clientRequests();
        b.liveSSTables = n.liveSSTables();
        b.dataDirs = n.dataDirs();
        return b;
    }

    NodeSnapshot build() {
        return new NodeSnapshot(hostId, address, datacenter, rack, state, route, error, cassandraVersion, javaVersion,
                javaVendor, uptimeSec, loadBytes, tokens, heapUsedBytes, heapMaxBytes, offHeapBytes, gc, gcTimePct,
                cpuProcessPct, cpuSystemPct, openFds, maxFds, pendingCompactions, activeCompactions,
                completedCompactions, hintsInProgress, totalHints, threadPools, dropped, clientRequests, liveSSTables,
                dataDirs);
    }

    /** A node that could not be read: identity and state only, everything else null. */
    static NodeSnapshot failed(String hostId, String address, String dc, String rack, String version, String route,
                               String error) {
        NodeBuilder b = new NodeBuilder();
        b.hostId = hostId;
        b.address = address;
        b.datacenter = dc;
        b.rack = rack;
        b.cassandraVersion = version;
        b.route = route;
        b.error = error;
        return b.build();
    }

    static NodeSnapshot withState(NodeSnapshot n, String state) {
        NodeBuilder b = of(n);
        b.state = state;
        return b.build();
    }
}
