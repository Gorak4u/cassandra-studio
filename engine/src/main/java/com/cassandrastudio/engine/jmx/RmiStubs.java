package com.cassandrastudio.engine.jmx;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.OutputStream;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.server.RMIClientSocketFactory;
import javax.management.remote.rmi.RMIConnection;
import javax.management.remote.rmi.RMIServer;

/**
 * Re-points RMI stubs at the endpoint Studio can actually reach, using only public serialization
 * APIs (no reflection, no --add-opens).
 *
 * <p>The stubs a node hands out name the address it believes in: 127.0.0.1 with LOCAL_JMX=yes, and
 * the RMI server object's port, which is 7199 when com.sun.management.jmxremote.rmi.port is set
 * (LOCAL_JMX=no) but a random port otherwise (the stock LOCAL_JMX=yes setup). Through an SSH tunnel
 * that address is wrong, and with several nodes it is the same wrong address for all of them.
 * A stub is serialised through a stream that rewrites its endpoint (host, port per
 * {@link Relocation}, and the session's client socket factory) on the way out, and read back: the
 * copy reaches the node through the session's tunnel and sockets, including its DGC lease
 * renewals. Every {@code RMIConnection} the server stub returns is rewritten the same way by
 * {@link #server}.
 */
final class RmiStubs {
    /** TCPEndpoint wire format: host and port, then a client socket factory object. */
    private static final int FORMAT_HOST_PORT_FACTORY = 1;

    private RmiStubs() {}

    /** A reachable host and port. */
    record Endpoint(String host, int port) {}

    /** Where a stub that the node advertises at {@code host:port} is reached from here. */
    @FunctionalInterface
    interface Relocation {
        Endpoint map(String host, int port) throws IOException;
    }

    /** The server stub, rewritten, and wrapped so the connections it creates are rewritten too. */
    static RMIServer server(RMIServer stub, Relocation to, RMIClientSocketFactory sockets) throws IOException {
        RMIServer moved = relocate(stub, to, sockets);
        return new RMIServer() {
            @Override
            public String getVersion() throws RemoteException {
                return moved.getVersion();
            }

            @Override
            public RMIConnection newClient(Object credentials) throws IOException {
                return relocate(moved.newClient(credentials), to, sockets);
            }
        };
    }

    /** A copy of {@code stub} that dials {@code to} through {@code sockets}. */
    static <T extends Remote> T relocate(T stub, Relocation to, RMIClientSocketFactory sockets) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Rewriter out = new Rewriter(bytes, to, sockets)) {
            out.writeObject(stub);
            if (!out.rewritten) throw new IOException("unexpected RMI stub " + stub.getClass().getName());
        }
        try (ObjectInputStream in = new LocalObjectInputStream(bytes.toByteArray())) {
            @SuppressWarnings("unchecked")
            T copy = (T) in.readObject();
            return copy;
        } catch (ClassNotFoundException e) {
            throw new IOException("cannot rebuild RMI stub: " + e.getMessage(), e);
        }
    }

    /**
     * Rewrites what RemoteObject.writeObject emits for a live reference: ref class "UnicastRef"
     * (host, port) or "UnicastRef2" (format byte, host, port [, factory]) always becomes
     * "UnicastRef2" with the new host, port and Studio's factory. Everything else passes through.
     */
    private static final class Rewriter extends ObjectOutputStream {
        private enum State { IDLE, FORMAT, HOST, PORT, FACTORY }

        private final Relocation to;
        private final RMIClientSocketFactory sockets;
        private State state = State.IDLE;
        private boolean hadFactory;
        private String host;
        boolean rewritten;

        Rewriter(OutputStream out, Relocation to, RMIClientSocketFactory sockets) throws IOException {
            super(out);
            this.to = to;
            this.sockets = sockets;
            enableReplaceObject(true);
        }

        @Override
        public void writeUTF(String s) throws IOException {
            if (state == State.IDLE && "UnicastRef".equals(s)) {
                super.writeUTF("UnicastRef2");
                super.writeByte(FORMAT_HOST_PORT_FACTORY);
                hadFactory = false;
                state = State.HOST;
            } else if (state == State.IDLE && "UnicastRef2".equals(s)) {
                super.writeUTF(s);
                state = State.FORMAT;
            } else if (state == State.HOST) {
                host = s; // written with the port, which decides where it goes
                state = State.PORT;
            } else {
                super.writeUTF(s);
            }
        }

        @Override
        public void writeByte(int b) throws IOException {
            if (state == State.FORMAT) {
                hadFactory = b == FORMAT_HOST_PORT_FACTORY;
                super.writeByte(FORMAT_HOST_PORT_FACTORY);
                state = State.HOST;
            } else {
                super.writeByte(b);
            }
        }

        @Override
        public void writeInt(int port) throws IOException {
            if (state != State.PORT) {
                super.writeInt(port);
                return;
            }
            Endpoint e = to.map(host, port);
            super.writeUTF(e.host());
            super.writeInt(e.port());
            rewritten = true;
            if (hadFactory) {
                state = State.FACTORY; // the node's factory object comes next: replaceObject swaps it
            } else {
                state = State.IDLE;
                writeObject(sockets);
            }
        }

        @Override
        protected Object replaceObject(Object obj) {
            if (state == State.FACTORY && obj instanceof RMIClientSocketFactory) {
                state = State.IDLE;
                return sockets;
            }
            return obj;
        }
    }

    /** Resolves Studio's own classes (the socket factory) whatever the calling thread's loader. */
    private static final class LocalObjectInputStream extends ObjectInputStream {
        LocalObjectInputStream(byte[] data) throws IOException {
            super(new ByteArrayInputStream(data));
        }

        @Override
        protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
            try {
                return super.resolveClass(desc);
            } catch (ClassNotFoundException | InvalidClassException e) {
                return Class.forName(desc.getName(), false, RmiStubs.class.getClassLoader());
            }
        }
    }
}
