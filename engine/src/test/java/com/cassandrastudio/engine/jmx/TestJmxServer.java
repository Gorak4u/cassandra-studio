package com.cassandrastudio.engine.jmx;

import java.io.IOException;
import java.net.ServerSocket;
import java.rmi.NoSuchObjectException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.UnicastRemoteObject;
import java.util.Map;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import javax.management.remote.JMXServiceURL;
import javax.management.remote.rmi.RMIConnectorServer;
import javax.management.remote.rmi.RMIJRMPServerImpl;
import javax.management.remote.rmi.RMIServer;

/**
 * In-process JMX server laid out like Cassandra's: RMI registry and server objects on ONE port,
 * stub bound as "jmxrmi", and an MBean {@code test:type=Node} whose {@code Id} tells nodes apart.
 * Optionally the server objects get their own port (as with stock LOCAL_JMX=yes, where
 * rmi.port is unset) and advertise a client socket factory.
 */
public final class TestJmxServer implements AutoCloseable {
    static final ObjectName NODE = name("test:type=Node");

    public interface NodeMBean {
        String getId();
    }

    public static final class Node implements NodeMBean {
        private final String id;

        Node(String id) {
            this.id = id;
        }

        @Override
        public String getId() {
            return id;
        }
    }

    final int port;
    final int objectPort;
    private final Registry registry;
    private final RMIConnectorServer server;
    private final RMIJRMPServerImpl impl;

    TestJmxServer(String id) throws Exception {
        this(id, false, null);
    }

    TestJmxServer(String id, boolean separateObjectPort, RMIClientSocketFactory advertisedFactory) throws Exception {
        port = freePort();
        MBeanServer mbs = MBeanServerFactory.newMBeanServer();
        mbs.registerMBean(new Node(id), NODE);
        registry = LocateRegistry.createRegistry(port);
        objectPort = separateObjectPort ? freePort() : port;
        impl = new RMIJRMPServerImpl(objectPort, advertisedFactory, null, Map.of());
        server = new RMIConnectorServer(new JMXServiceURL("rmi", "127.0.0.1", port), Map.of(), impl, mbs);
        server.start();
        registry.bind("jmxrmi", impl.toStub());
    }

    RMIServer stub() throws IOException {
        return (RMIServer) impl.toStub();
    }

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static ObjectName name(String n) {
        try {
            return new ObjectName(n);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public void close() throws IOException {
        server.stop();
        try {
            UnicastRemoteObject.unexportObject(registry, true);
        } catch (NoSuchObjectException ignored) {
            // already gone
        }
    }
}
