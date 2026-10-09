package com.cassandrastudio.engine.ops;

import com.cassandrastudio.engine.cql.ClusterService.ClusterInfo;
import com.cassandrastudio.engine.cql.ClusterService.NodeInfo;
import com.cassandrastudio.engine.jmx.JmxAccess;
import com.cassandrastudio.engine.jmx.NodeEndpoint;
import com.cassandrastudio.engine.metrics.Topology;
import com.cassandrastudio.engine.model.ConnectionConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.MBeanServer;
import javax.management.MBeanServerConnection;
import javax.management.MBeanServerFactory;
import javax.management.Notification;
import javax.management.NotificationBroadcasterSupport;
import javax.management.ObjectName;
import javax.management.ReflectionException;

/**
 * In-process stand-ins for Cassandra nodes: one MBeanServer per node with dynamic MBeans under
 * the real ObjectNames, attribute names and operation signatures; operations are lambdas and
 * every call is recorded. No Docker, no network.
 */
final class FakeCassandra {
    private FakeCassandra() {}

    /** A DynamicMBean with map attributes, lambda operations (by name + signature) and notifications. */
    static final class Bean extends NotificationBroadcasterSupport implements DynamicMBean {
        final Map<String, Object> attrs = new ConcurrentHashMap<>();
        final Map<String, Function<Object[], Object>> ops = new ConcurrentHashMap<>();
        final List<String> calls = new CopyOnWriteArrayList<>();
        private long seq;

        Bean attr(String name, Object value) {
            attrs.put(name, value);
            return this;
        }

        /** Registers an operation; {@code sig} are parameter type names. */
        Bean op(String name, List<String> sig, Function<Object[], Object> f) {
            ops.put(name + "(" + String.join(",", sig) + ")", f);
            return this;
        }

        void emit(String type, Object source, String message, Object userData) {
            Notification n = new Notification(type, source, ++seq, message);
            n.setUserData(userData);
            sendNotification(n);
        }

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
            AttributeList l = new AttributeList();
            for (String n : names) {
                if (attrs.containsKey(n)) l.add(new Attribute(n, attrs.get(n)));
            }
            return l;
        }

        @Override
        public AttributeList setAttributes(AttributeList attributes) {
            return new AttributeList();
        }

        @Override
        public Object invoke(String name, Object[] params, String[] signature) throws ReflectionException {
            String key = name + "(" + String.join(",", signature == null ? new String[0] : signature) + ")";
            Function<Object[], Object> f = ops.get(key);
            if (f == null) throw new ReflectionException(new NoSuchMethodException(key), "No such operation " + key);
            calls.add(name + " " + Arrays.deepToString(params == null ? new Object[0] : params));
            return f.apply(params == null ? new Object[0] : params);
        }

        @Override
        public MBeanInfo getMBeanInfo() {
            List<MBeanOperationInfo> infos = new ArrayList<>();
            ops.keySet().forEach(k -> {
                String name = k.substring(0, k.indexOf('('));
                String inner = k.substring(k.indexOf('(') + 1, k.length() - 1);
                List<MBeanParameterInfo> ps = new ArrayList<>();
                if (!inner.isEmpty()) {
                    int i = 0;
                    for (String t : inner.split(",")) ps.add(new MBeanParameterInfo("p" + i++, t, ""));
                }
                infos.add(new MBeanOperationInfo(name, "", ps.toArray(MBeanParameterInfo[]::new), "java.lang.Object",
                        MBeanOperationInfo.ACTION));
            });
            List<MBeanAttributeInfo> ai = new ArrayList<>();
            attrs.forEach((k, v) -> ai.add(new MBeanAttributeInfo(k, v.getClass().getName(), "", true, false, false)));
            return new MBeanInfo(getClass().getName(), "fake", ai.toArray(MBeanAttributeInfo[]::new), null,
                    infos.toArray(MBeanOperationInfo[]::new), new MBeanNotificationInfo[0]);
        }
    }

    /** One fake node. */
    static final class Node {
        final String address;
        final MBeanServer server = MBeanServerFactory.newMBeanServer();
        final Bean storage = new Bean();
        final Bean compaction = new Bean();

        Node(String address) {
            this.address = address;
            register(Beans.STORAGE_SERVICE, storage);
            register(Beans.COMPACTION_MANAGER, compaction);
        }

        Node register(String name, Bean b) {
            try {
                server.registerMBean(b, new ObjectName(name));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return this;
        }
    }

    static final String S = String.class.getName(), SA = String[].class.getName(), M = Map.class.getName();

    /** A 4.1-like node: flush, compaction, cleanup, repair, snapshot operations, all succeeding. */
    static Node cassandra41(String address) {
        Node n = new Node(address);
        n.storage.attr("ReleaseVersion", "4.1.5").attr("ClusterName", "test").attr("OperationMode", "NORMAL")
                .op("forceKeyspaceFlush", List.of(S, SA), a -> null)
                .op("forceKeyspaceCompaction", List.of("boolean", S, SA), a -> null)
                .op("forceKeyspaceCleanup", List.of("int", S, SA), a -> 0)
                .op("forceKeyspaceCleanup", List.of(S, SA), a -> 0)
                .op("garbageCollect", List.of(S, "int", S, SA), a -> 0)
                .op("upgradeSSTables", List.of(S, "boolean", "int", SA), a -> 0)
                .op("scrub", List.of("boolean", "boolean", "boolean", "boolean", "int", S, SA), a -> 0)
                .op("takeSnapshot", List.of(S, M, SA), a -> null)
                .op("clearSnapshot", List.of(S, SA), a -> null)
                .op("forceTerminateAllRepairSessions", List.of(), a -> null);
        n.compaction.attr("Compactions", new ArrayList<>())
                .op("stopCompaction", List.of(S), a -> null)
                .op("forceUserDefinedCompaction", List.of(S), a -> null);
        return n;
    }

    static final class Jmx implements JmxAccess {
        final Map<String, Node> nodes = new ConcurrentHashMap<>();

        Jmx add(Node n) {
            nodes.put(n.address, n);
            return this;
        }

        @Override
        public JmxSession session(ConnectionConfig cfg, Map<String, String> secrets, NodeEndpoint node) {
            Node n = nodes.get(node.address());
            if (n == null) throw new JmxUnavailableException("Connection refused to " + node.address() + ":7199", null);
            return new JmxSession() {
                @Override
                public MBeanServerConnection mbeans() {
                    return n.server;
                }

                @Override
                public String route() {
                    return "fake";
                }
            };
        }

        @Override
        public List<ExporterSample> scrapeExporter(ConnectionConfig cfg, NodeEndpoint node) {
            return List.of();
        }

        @Override
        public void closeConnection(String connectionId) {}

        @Override
        public void close() {}
    }

    static final class Topo implements Topology {
        volatile List<NodeInfo> nodes = new ArrayList<>(List.of(
                info("h1", "10.0.0.1", "4.1.5"), info("h2", "10.0.0.2", "4.1.5")));

        static NodeInfo info(String hostId, String address, String version) {
            return new NodeInfo(hostId, address, 9042, "dc1", "rack1", version, "UP", 16, "s1", 1);
        }

        @Override
        public ClusterInfo info(String connectionId) {
            return new ClusterInfo("test", "org.apache.cassandra.dht.Murmur3Partitioner", List.of("dc1"), nodes, true,
                    List.of("4.1.5"), "V5");
        }
    }
}
