package com.cassandrastudio.engine.metrics;

import com.cassandrastudio.engine.cql.ClusterService.ClusterInfo;
import com.cassandrastudio.engine.cql.ClusterService.NodeInfo;
import com.cassandrastudio.engine.jmx.JmxAccess;
import com.cassandrastudio.engine.jmx.NodeEndpoint;
import com.cassandrastudio.engine.model.ConnectionConfig;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.MBeanServerConnection;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;

/**
 * In-process stand-ins for Cassandra nodes: an MBeanServer per node with small dynamic MBeans
 * registered under the real Cassandra ObjectNames and attribute names, a fake JmxAccess over
 * them and a fake driver topology. No Docker, no network.
 */
final class FakeNodes {
    private FakeNodes() {}

    /** A DynamicMBean whose attributes are a mutable map; operations are lambdas. */
    static final class Bean implements DynamicMBean {
        final Map<String, Object> attrs = new ConcurrentHashMap<>();
        final Map<String, Function<Object[], Object>> ops = new ConcurrentHashMap<>();

        @Override
        public Object getAttribute(String name) throws AttributeNotFoundException {
            if (!attrs.containsKey(name)) throw new AttributeNotFoundException(name);
            return attrs.get(name);
        }

        @Override
        public void setAttribute(Attribute attribute) {
            attrs.put(attribute.getName(), attribute.getValue());
        }

        @Override
        public AttributeList getAttributes(String[] names) {
            AttributeList out = new AttributeList();
            for (String n : names) if (attrs.containsKey(n)) out.add(new Attribute(n, attrs.get(n)));
            return out;
        }

        @Override
        public AttributeList setAttributes(AttributeList attributes) {
            return attributes;
        }

        @Override
        public Object invoke(String action, Object[] params, String[] signature) throws ReflectionException {
            Function<Object[], Object> op = ops.get(action);
            if (op == null) throw new ReflectionException(new NoSuchMethodException(action));
            return op.apply(params);
        }

        @Override
        public MBeanInfo getMBeanInfo() {
            return new MBeanInfo(Bean.class.getName(), "fake", null, null, null, null);
        }
    }

    /** One fake node: its own MBeanServer. */
    static final class Node {
        final String address;
        final MBeanServer server = MBeanServerFactory.newMBeanServer();
        final Map<String, Bean> beans = new HashMap<>();

        Node(String address) {
            this.address = address;
        }

        Bean bean(String name) {
            return beans.computeIfAbsent(name, n -> {
                Bean b = new Bean();
                try {
                    server.registerMBean(b, new ObjectName(n));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return b;
            });
        }

        Node set(String bean, String attr, Object value) {
            bean(bean).attrs.put(attr, value);
            return this;
        }

        Node metric(String props, String attr, Object value) {
            return set("org.apache.cassandra.metrics:" + props, attr, value);
        }

        void remove(String name) {
            try {
                server.unregisterMBean(new ObjectName(name));
                beans.remove(name);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        Node gc(String collector, long count, long timeMs) {
            String n = "java.lang:type=GarbageCollector,name=" + collector;
            set(n, "CollectionCount", count);
            return set(n, "CollectionTime", timeMs);
        }
    }

    static CompositeData memoryUsage(long used, long max) {
        try {
            String[] keys = {"init", "used", "committed", "max"};
            OpenType<?>[] types = {SimpleType.LONG, SimpleType.LONG, SimpleType.LONG, SimpleType.LONG};
            CompositeType t = new CompositeType("java.lang.management.MemoryUsage", "usage", keys, keys, types);
            return new CompositeDataSupport(t, keys, new Object[] {used, used, used, max});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static TabularDataSupport systemProperties(Map<String, String> props) {
        try {
            String[] keys = {"key", "value"};
            CompositeType row = new CompositeType("prop", "prop", keys, keys, new OpenType<?>[] {SimpleType.STRING, SimpleType.STRING});
            TabularDataSupport td = new TabularDataSupport(new TabularType("props", "props", row, new String[] {"key"}));
            props.forEach((k, v) -> {
                try {
                    td.put(new CompositeDataSupport(row, keys, new Object[] {k, v}));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            return td;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final String SS = "org.apache.cassandra.db:type=StorageService";

    private static void common(Node n, String version, String hostId, long load, List<String> tokens) {
        n.set("java.lang:type=Memory", "HeapMemoryUsage", memoryUsage(4_000_000_000L, 8_000_000_000L));
        n.set("java.lang:type=Runtime", "Uptime", 3_600_000L);
        n.set("java.lang:type=Runtime", "VmVendor", "Eclipse Adoptium");
        n.set("java.lang:type=Runtime", "SpecVersion", "11");
        n.set("java.lang:type=Runtime", "VmVersion", "11.0.22+7");
        n.set(SS, "ReleaseVersion", version);
        n.set(SS, "Load", load);
        n.set(SS, "Tokens", tokens);
        n.set(SS, "LocalHostId", hostId);
        n.set(SS, "OperationMode", "NORMAL");
        n.set(SS, "AllDataFileLocations", new String[] {"/var/lib/cassandra/data"});
        n.set(SS, "PartitionerName", "org.apache.cassandra.dht.Murmur3Partitioner");
    }

    /** A 4.1 node on JDK 17 with G1, every metric present. */
    static Node cassandra41G1(String address, String hostId) {
        Node n = new Node(address);
        common(n, "4.1.5", hostId, 1_000_000L, List.of("-100", "100"));
        n.set("java.lang:type=Runtime", "SystemProperties", systemProperties(Map.of("java.version", "17.0.10")));
        n.set("java.lang:type=OperatingSystem", "ProcessCpuLoad", 0.25);
        n.set("java.lang:type=OperatingSystem", "CpuLoad", 0.5);
        n.set("java.lang:type=OperatingSystem", "SystemCpuLoad", 0.4);
        n.set("java.lang:type=OperatingSystem", "OpenFileDescriptorCount", 900L);
        n.set("java.lang:type=OperatingSystem", "MaxFileDescriptorCount", 100_000L);
        n.gc("G1 Young Generation", 10, 1_000).gc("G1 Old Generation", 0, 0).gc("G1 Concurrent GC", 5, 50_000);
        n.metric("type=Compaction,name=PendingTasks", "Value", 3);
        n.metric("type=Compaction,name=CompletedTasks", "Value", 42L);
        n.set("org.apache.cassandra.db:type=CompactionManager", "Compactions",
                List.of(Map.of("id", "1", "taskType", "COMPACTION")));
        n.metric("type=Storage,name=TotalHints", "Count", 7L);
        n.metric("type=Storage,name=TotalHintsInProgress", "Count", 0L);
        for (String pool : List.of("MutationStage", "ReadStage")) {
            String base = "type=ThreadPools,path=request,scope=" + pool + ",name=";
            n.metric(base + "ActiveTasks", "Value", 1);
            n.metric(base + "PendingTasks", "Value", 2);
            n.metric(base + "CompletedTasks", "Value", 1000L);
            n.metric(base + "CurrentlyBlockedTasks", "Count", 0L);
            n.metric(base + "TotalBlockedTasks", "Count", 0L);
        }
        n.metric("type=ThreadPools,path=transport,scope=Native-Transport-Requests,name=PendingTasks", "Value", 4);
        n.metric("type=DroppedMessage,scope=MUTATION,name=Dropped", "Count", 0L);
        n.metric("type=DroppedMessage,scope=READ,name=Dropped", "Count", 0L);
        for (String scope : MetricCatalog.CLIENT_SCOPES) {
            String base = "type=ClientRequest,scope=" + scope + ",name=";
            n.metric(base + "Latency", "50thPercentile", 300.0);
            n.metric(base + "Latency", "95thPercentile", 900.0);
            n.metric(base + "Latency", "99thPercentile", 1500.0);
            n.metric(base + "Latency", "Max", 9000.0);
            n.metric(base + "Latency", "OneMinuteRate", 120.5);
            n.metric(base + "Latency", "Count", 5000L);
            n.metric(base + "Latency", "DurationUnit", "microseconds");
            n.metric(base + "Timeouts", "Count", 0L);
            n.metric(base + "Unavailables", "Count", 0L);
            n.metric(base + "Failures", "Count", 0L);
        }
        n.metric("type=Table,name=LiveSSTableCount", "Value", 12);
        n.metric("type=Table,name=MemtableOffHeapSize", "Value", 1000L);
        n.metric("type=Table,name=BloomFilterOffHeapMemoryUsed", "Value", 200L);
        table(n, "Table", "shop", "orders", 10);
        table(n, "Table", "system", "local", 1);
        gossip(n, true);
        return n;
    }

    /** A 3.11 node on JDK 8 with CMS/ParNew, ColumnFamily metrics, internal/request pool paths. */
    static Node cassandra311Cms(String address, String hostId) {
        Node n = new Node(address);
        common(n, "3.11.16", hostId, 3_000_000L, List.of("0", "200"));
        n.set("java.lang:type=Runtime", "SpecVersion", "1.8");
        n.set("java.lang:type=Runtime", "VmVersion", "25.392-b08");
        n.set("java.lang:type=OperatingSystem", "ProcessCpuLoad", 0.1);
        n.set("java.lang:type=OperatingSystem", "SystemCpuLoad", 0.3);
        n.gc("ParNew", 100, 2_000).gc("ConcurrentMarkSweep", 2, 400);
        n.metric("type=Compaction,name=PendingTasks", "Value", 150);
        n.metric("type=ThreadPools,path=internal,scope=CompactionExecutor,name=PendingTasks", "Value", 5);
        n.metric("type=ThreadPools,path=internal,scope=CompactionExecutor,name=ActiveTasks", "Value", 1);
        n.metric("type=ThreadPools,path=request,scope=MutationStage,name=CurrentlyBlockedTasks", "Count", 2L);
        n.metric("type=ThreadPools,path=request,scope=MutationStage,name=TotalBlockedTasks", "Count", 9L);
        n.metric("type=DroppedMessage,scope=MUTATION,name=Dropped", "Count", 5L);
        n.metric("type=ClientRequest,scope=Read,name=Latency", "99thPercentile", 2.5);
        n.metric("type=ClientRequest,scope=Read,name=Latency", "DurationUnit", "milliseconds");
        n.metric("type=ClientRequest,scope=Read,name=Timeouts", "Count", 3L);
        n.metric("type=ColumnFamily,name=LiveSSTableCount", "Value", 30);
        table(n, "ColumnFamily", "shop", "orders", 20);
        gossip(n, false);
        return n;
    }

    /** A 5.0 node on JDK 17 with ZGC where most Cassandra beans are missing. */
    static Node cassandra50Sparse(String address) {
        Node n = new Node(address);
        n.set("java.lang:type=Memory", "HeapMemoryUsage", memoryUsage(1_000L, 2_000L));
        n.set("java.lang:type=Runtime", "Uptime", 10_000L);
        n.set(SS, "ReleaseVersion", "5.0.2");
        n.gc("ZGC Cycles", 4, 4_000).gc("ZGC Pauses", 12, 6);
        return n;
    }

    static void table(Node n, String type, String ks, String table, long sstables) {
        String base = "type=" + type + ",keyspace=" + ks + ",scope=" + table + ",name=";
        n.metric(base + "ReadLatency", "Count", 100L);
        n.metric(base + "ReadLatency", "99thPercentile", 800.0);
        n.metric(base + "ReadLatency", "DurationUnit", "microseconds");
        n.metric(base + "WriteLatency", "Count", 50L);
        n.metric(base + "WriteLatency", "99thPercentile", 90.0 + sstables);
        n.metric(base + "LiveDiskSpaceUsed", "Count", 1_000L);
        n.metric(base + "TotalDiskSpaceUsed", "Count", 1_500L);
        n.metric(base + "LiveSSTableCount", "Value", (int) sstables);
        n.metric(base + "MeanPartitionSize", "Value", 2_000L);
        n.metric(base + "MaxPartitionSize", "Value", 9_000L + sstables);
        n.metric(base + "TombstoneScannedHistogram", "99thPercentile", 3.0);
        n.metric(base + "SSTablesPerReadHistogram", "99thPercentile", 2.0);
        n.metric(base + "BloomFilterFalseRatio", "Value", 0.01);
        n.metric(base + "PendingCompactions", "Value", 1);
        n.metric(base + "KeyCacheHitRate", "Value", 0.9);
    }

    /** Gossip view of the three-node test cluster (10.0.0.1-3); 4.0+ uses the WithPort attributes. */
    static void gossip(Node n, boolean withPort) {
        n.set(SS, "LiveNodes", List.of("10.0.0.1", "10.0.0.2"));
        n.set(SS, "UnreachableNodes", List.of("10.0.0.3"));
        n.set(SS, "JoiningNodes", List.of());
        n.set(SS, "LeavingNodes", List.of());
        n.set(SS, "MovingNodes", List.of());
        n.set(SS, "HostIdMap", Map.of("10.0.0.1", "h1", "10.0.0.2", "h2", "10.0.0.3", "h3"));
        if (withPort) {
            n.set(SS, "TokenToEndpointWithPortMap", new LinkedHashMap<>(Map.of("-100", "/10.0.0.1:7000",
                    "100", "/10.0.0.1:7000", "0", "/10.0.0.2:7000", "200", "/10.0.0.2:7000", "50", "/10.0.0.3:7000")));
            n.set(SS, "OwnershipWithPort", Map.of("/10.0.0.1:7000", 0.5f, "/10.0.0.2:7000", 0.3f, "/10.0.0.3:7000", 0.2f));
            n.bean(SS).ops.put("effectiveOwnershipWithPort", p -> "shop".equals(p[0])
                    ? Map.of("/10.0.0.1:7000", 1.0f, "/10.0.0.2:7000", 1.0f, "/10.0.0.3:7000", 1.0f) : null);
        } else {
            n.set(SS, "Ownership", Map.of(inet("10.0.0.1"), 0.5f, inet("10.0.0.2"), 0.3f, inet("10.0.0.3"), 0.2f));
            n.set(SS, "TokenToEndpointMap", Map.of("-100", "10.0.0.1", "100", "10.0.0.1", "0", "10.0.0.2",
                    "200", "10.0.0.2", "50", "10.0.0.3"));
            n.bean(SS).ops.put("effectiveOwnership", p -> Map.of(inet("10.0.0.1"), 1.0f, inet("10.0.0.2"), 1.0f));
        }
    }

    static InetAddress inet(String ip) {
        try {
            return InetAddress.getByName(ip);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    /** JmxAccess over fake nodes, with unreachable nodes, slow reads and concurrency tracking. */
    static final class Jmx implements JmxAccess {
        final Map<String, Node> nodes = new ConcurrentHashMap<>();
        final Set<String> unreachable = ConcurrentHashMap.newKeySet();
        final Map<String, List<ExporterSample>> exporter = new ConcurrentHashMap<>();
        volatile long delayMs;
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();
        final AtomicInteger sessions = new AtomicInteger();

        Jmx add(Node n) {
            nodes.put(n.address, n);
            return this;
        }

        @Override
        public JmxSession session(ConnectionConfig cfg, Map<String, String> secrets, NodeEndpoint node) {
            sessions.incrementAndGet();
            if (unreachable.contains(node.address()) || !nodes.containsKey(node.address())) {
                throw new JmxUnavailableException("Connection refused to " + node.address() + ":7199", null);
            }
            if (delayMs > 0) {
                int now = active.incrementAndGet();
                maxActive.accumulateAndGet(now, Math::max);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    active.decrementAndGet();
                }
            }
            MBeanServerConnection conn = nodes.get(node.address()).server;
            return new JmxSession() {
                @Override
                public MBeanServerConnection mbeans() {
                    return conn;
                }

                @Override
                public String route() {
                    return "direct";
                }
            };
        }

        @Override
        public List<ExporterSample> scrapeExporter(ConnectionConfig cfg, NodeEndpoint node) {
            List<ExporterSample> s = exporter.get(node.address());
            if (s == null) throw new JmxUnavailableException("exporter not reachable", null);
            return s;
        }

        @Override
        public void closeConnection(String connectionId) {}

        @Override
        public void close() {}
    }

    /** Driver view: three nodes in dc1 (10.0.0.3 down to the driver). */
    static class Topo implements Topology {
        volatile List<NodeInfo> nodes = new ArrayList<>(List.of(
                info("h1", "10.0.0.1", "rack1", "4.1.5", "UP"),
                info("h2", "10.0.0.2", "rack2", "4.1.5", "UP"),
                info("h3", "10.0.0.3", "rack3", "4.1.5", "DOWN")));
        volatile boolean schemaAgreement = true;
        volatile RuntimeException failure;

        static NodeInfo info(String hostId, String address, String rack, String version, String state) {
            return new NodeInfo(hostId, address, 9042, "dc1", rack, version, state, 2, "s1", 1);
        }

        @Override
        public ClusterInfo info(String connectionId) {
            if (failure != null) throw failure;
            return new ClusterInfo("test", "org.apache.cassandra.dht.Murmur3Partitioner", List.of("dc1"), nodes,
                    schemaAgreement, List.of("4.1.5"), "V5");
        }

        @Override
        public List<String> keyspaces(String connectionId) {
            return List.of("shop");
        }
    }
}
