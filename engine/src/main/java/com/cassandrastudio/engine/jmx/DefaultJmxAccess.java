package com.cassandrastudio.engine.jmx;

import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.JmxMethod;
import com.cassandrastudio.engine.model.ConnectionConfig.SecretKeys;
import com.cassandrastudio.engine.ssh.SshAccessException;
import com.cassandrastudio.engine.ssh.SshConnection;
import com.cassandrastudio.engine.ssh.SshConnector;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.rmi.NotBoundException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.rmi.RMIConnector;
import javax.management.remote.rmi.RMIServer;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link JmxAccess} for real nodes (CON-6, CON-7): JMX through an SSH tunnel (optionally via a
 * jump host) or direct JMX/RMI (optionally TLS and login), and jmx_exporter scraping.
 *
 * <p>One session per (connection, node), shared by all callers and re-checked cheaply on reuse.
 * A failed node is not hammered: until its back-off expires, {@link #session} fails at once with
 * the remembered reason. Every step is bounded by the connect timeout; MBean calls by the read
 * timeout. Thread-safe; different nodes connect in parallel.
 *
 * <p>Through a tunnel, every node's RMI stubs advertise the same 127.0.0.1 address. {@link RmiStubs}
 * rewrites them per session onto the session's own forwarded ports and {@link RmiSockets} factory,
 * so sessions never share or mix RMI connections. No reflection or JVM flags are needed.
 */
public final class DefaultJmxAccess implements JmxAccess {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultJmxAccess.class);
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);
    static final Duration BACKOFF_INITIAL = Duration.ofSeconds(2);
    static final Duration BACKOFF_MAX = Duration.ofMinutes(1);
    /** A session unused for this long is pinged (one cheap MBean call) before it is handed out again. */
    static final Duration IDLE_CHECK = Duration.ofSeconds(30);

    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final LongSupplier clock;
    private final SshConnector ssh = new SshConnector();
    private final Map<SessionKey, Slot> slots = new ConcurrentHashMap<>();
    private HttpClient http;
    private volatile boolean closed;

    record SessionKey(String connectionId, String node) {}

    /** Per-node state; {@code lock} serialises connecting to that node. */
    private static final class Slot {
        final ReentrantLock lock = new ReentrantLock();
        final Backoff backoff = new Backoff(BACKOFF_INITIAL, BACKOFF_MAX);
        volatile NodeSession session;
        volatile boolean retired;
        String fingerprint;

        void retire() {
            retired = true;
            NodeSession s = session;
            session = null;
            if (s != null) s.close();
        }
    }

    /** 5 s connect timeout, 30 s read timeout. */
    public DefaultJmxAccess() {
        this(DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    /**
     * @param connectTimeout bound for establishing a session (SSH, jump host, tunnel, RMI, login)
     * @param readTimeout    bound for one MBean call on an open session; zero means none (blocking
     *                       operations such as cleanup can run longer than any poll timeout)
     */
    public DefaultJmxAccess(Duration connectTimeout, Duration readTimeout) {
        this(connectTimeout, readTimeout, System::nanoTime);
    }

    DefaultJmxAccess(Duration connectTimeout, Duration readTimeout, LongSupplier clock) {
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.clock = clock;
        RmiDefaultSockets.install(connectTimeout);
    }

    @Override
    public JmxSession session(ConnectionConfig cfg, Map<String, String> secrets, NodeEndpoint node) {
        JmxMethod method = cfg.jmx().method();
        if (method != JmxMethod.SSH_TUNNEL && method != JmxMethod.DIRECT) {
            throw new UnsupportedOperationException("JMX access method " + method + " has no JMX session");
        }
        if (closed) throw new IllegalStateException("JMX access is closed");
        Map<String, String> sec = secrets == null ? Map.of() : secrets;
        String fp = fingerprint(cfg, sec, node);
        Slot slot = slots.computeIfAbsent(new SessionKey(cfg.id(), nodeKey(node)), k -> new Slot());
        NodeSession s = slot.session;
        if (s != null && s.fingerprint.equals(fp) && healthy(s)) return s;
        lock(slot, node);
        try {
            s = slot.session;
            if (s != null) {
                if (s.fingerprint.equals(fp) && healthy(s)) return s;
                slot.session = null;
                s.close();
            }
            if (!fp.equals(slot.fingerprint)) { // new or changed settings: forget the old back-off
                slot.fingerprint = fp;
                slot.backoff.success();
            }
            Duration wait = slot.backoff.waitLeft(clock.getAsLong());
            if (wait != null) {
                RuntimeException last = slot.backoff.lastFailure();
                long secs = Math.max(1, (wait.toMillis() + 999) / 1000);
                throw new JmxUnavailableException(last.getMessage() + " (next retry in " + secs + " s)", last);
            }
            try {
                NodeSession fresh = RmiDefaultSockets.whileConnecting(() -> connect(cfg, sec, node, fp));
                if (slot.retired || closed) {
                    fresh.close();
                    throw new JmxUnavailableException("JMX connection to " + node.address() + " was closed", null);
                }
                slot.session = fresh;
                slot.backoff.success();
                LOG.info("JMX session to {} open ({})", node.address(), fresh.route);
                return fresh;
            } catch (JmxUnavailableException e) {
                slot.backoff.failure(clock.getAsLong(), e);
                LOG.info("JMX to {} unavailable: {}", node.address(), e.getMessage());
                throw e;
            }
        } finally {
            slot.lock.unlock();
        }
    }

    private void lock(Slot slot, NodeEndpoint node) {
        try {
            if (slot.lock.tryLock(connectTimeout.toNanos(), TimeUnit.NANOSECONDS)) return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JmxUnavailableException("interrupted while waiting for JMX to " + node.address(), e);
        }
        throw new JmxUnavailableException("timed out after " + seconds(connectTimeout)
                + " waiting for another JMX connect to " + node.address(), null);
    }

    private boolean healthy(NodeSession s) {
        if (s.broken || !s.transportOpen()) return false;
        long now = clock.getAsLong();
        if (now - s.lastOk < IDLE_CHECK.toNanos()) return true;
        try {
            s.raw.getMBeanCount();
            s.lastOk = now;
            return true;
        } catch (IOException | RuntimeException e) {
            s.broken = true;
            return false;
        }
    }

    private NodeSession connect(ConnectionConfig cfg, Map<String, String> secrets, NodeEndpoint node, String fp) {
        long deadline = System.nanoTime() + connectTimeout.toNanos();
        int port = cfg.jmx().port();
        boolean tunnelled = cfg.jmx().method() == JmxMethod.SSH_TUNNEL;
        String where = node.address() + ":" + port;
        SshConnection tunnel = null;
        RmiSockets sockets = null;
        JMXConnector connector = null;
        try {
            String host;
            int dialPort;
            RmiStubs.Relocation to;
            if (tunnelled) {
                tunnel = ssh.connect(cfg.ssh(), secrets, node.address(), connectTimeout);
                String refused = tunnel.probe("127.0.0.1", port, remaining(deadline, where));
                if (refused != null) {
                    throw new JmxUnavailableException("JMX not listening on " + where + " (from the node: "
                            + refused + ")", null);
                }
                host = "127.0.0.1";
                dialPort = tunnel.forwardLocalPort(host, port);
                to = new TunnelPorts(tunnel, port, dialPort);
            } else {
                host = node.address();
                dialPort = port;
                to = (advertisedHost, advertisedPort) -> new RmiStubs.Endpoint(node.address(), advertisedPort);
            }
            SSLSocketFactory tls = cfg.jmx().ssl() ? JmxTls.socketFactory(cfg.tls(), secrets) : null;
            Duration left = remaining(deadline, where);
            sockets = new RmiSockets(tls, left, left);
            RmiSockets.Factory factory = sockets.factory();
            Registry registry = LocateRegistry.getRegistry(host, dialPort, factory);
            RMIServer server = (RMIServer) registry.lookup("jmxrmi");
            Map<String, Object> env = environment(cfg, secrets);
            connector = new RMIConnector(RmiStubs.server(server, to, factory), env);
            remaining(deadline, where);
            connector.connect(env);
            sockets.readTimeout(readTimeout);
            return new NodeSession(fp, route(cfg, node), connector, tunnel, sockets);
        } catch (Exception e) {
            closeQuietly(connector);
            if (sockets != null) sockets.close();
            if (tunnel != null) tunnel.close();
            throw describe(e, cfg, where, tunnelled);
        }
    }

    private Duration remaining(long deadline, String where) {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
            throw new JmxUnavailableException("timed out after " + seconds(connectTimeout) + " connecting to JMX on "
                    + where, null);
        }
        return Duration.ofNanos(left);
    }

    private Map<String, Object> environment(ConnectionConfig cfg, Map<String, String> secrets) {
        Map<String, Object> env = new HashMap<>();
        String user = cfg.jmx().username();
        if (user != null && !user.isBlank()) {
            env.put(JMXConnector.CREDENTIALS, new String[] {user, secrets.getOrDefault(SecretKeys.JMX_PASSWORD, "")});
        }
        // Studio checks sessions itself; the JDK's checker would add one thread per node.
        env.put("jmx.remote.x.client.connection.check.period", 0L);
        if (!readTimeout.isZero()) {
            env.put("jmx.remote.x.notification.fetch.timeout", Math.max(1_000L, readTimeout.toMillis() / 2));
        }
        return env;
    }

    /** User-readable reason for a failed connect; never includes secrets. */
    private JmxUnavailableException describe(Exception e, ConnectionConfig cfg, String where, boolean tunnelled) {
        if (e instanceof JmxUnavailableException j) return j;
        if (e instanceof SshAccessException s) return new JmxUnavailableException(s.getMessage(), s);
        String via = tunnelled ? " through the SSH tunnel" : "";
        String user = cfg.jmx().username();
        if (has(e, InterruptedIOException.class)) {
            return new JmxUnavailableException("timed out after " + seconds(connectTimeout) + " connecting to JMX on "
                    + where + via, e);
        }
        if (e instanceof SecurityException) {
            return new JmxUnavailableException(user == null || user.isBlank()
                    ? "JMX on " + where + " requires a user name and password"
                    : "JMX login failed for user " + user + " on " + where, e);
        }
        if (has(e, SSLException.class)) {
            return new JmxUnavailableException("TLS handshake with JMX on " + where + " failed: " + rootMessage(e), e);
        }
        if (e instanceof IllegalArgumentException) return new JmxUnavailableException(e.getMessage(), e);
        if (e instanceof NotBoundException) {
            return new JmxUnavailableException("no JMX server named 'jmxrmi' on " + where, e);
        }
        if (has(e, UnknownHostException.class)) return new JmxUnavailableException("unknown host in " + where, e);
        if (has(e, NoRouteToHostException.class)) return new JmxUnavailableException("no route to " + where, e);
        if (has(e, ConnectException.class)) return new JmxUnavailableException("JMX not listening on " + where + via, e);
        if (has(e, EOFException.class)) {
            return new JmxUnavailableException("JMX on " + where + " closed the connection" + via
                    + (cfg.jmx().ssl() ? "" : " (is JMX using SSL?)"), e);
        }
        return new JmxUnavailableException("JMX connection to " + where + via + " failed: " + rootMessage(e), e);
    }

    private static boolean has(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) return true;
        }
        return false;
    }

    private static String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        String m = r.getMessage();
        return r.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    static String seconds(Duration d) {
        return d.toMillis() % 1000 == 0 ? d.toSeconds() + " s" : String.format("%.1f s", d.toMillis() / 1000.0);
    }

    /** How a node is reached, for the UI, e.g. "ssh tunnel via bastion:22 -> 10.0.0.5:7199". */
    static String route(ConnectionConfig cfg, NodeEndpoint node) {
        ConnectionConfig.Jmx jmx = cfg.jmx();
        String target = hostForUri(node.address()) + ":" + jmx.port();
        String ssl = jmx.ssl() ? " (ssl)" : "";
        return switch (jmx.method()) {
            case SSH_TUNNEL -> {
                ConnectionConfig.Ssh s = cfg.ssh();
                String jump = s.jumpHost() == null || s.jumpHost().isBlank() ? "" : " via " + s.jumpHost() + ":" + s.jumpPort();
                String sshPort = s.port() == 22 ? "" : " (ssh port " + s.port() + ")";
                yield "ssh tunnel" + jump + " -> " + target + sshPort + ssl;
            }
            case DIRECT -> "direct " + target + ssl;
            case EXPORTER -> "jmx_exporter " + exporterUri(cfg, node);
            case SIDECAR -> "sidecar " + hostForUri(node.address()) + ":" + jmx.sidecarPort();
            case NONE -> "none";
        };
    }

    private static String hostForUri(String address) {
        return address.indexOf(':') >= 0 && !address.startsWith("[") ? "[" + address + "]" : address;
    }

    private static URI exporterUri(ConnectionConfig cfg, NodeEndpoint node) {
        return URI.create("http://" + hostForUri(node.address()) + ":" + cfg.jmx().exporterPort() + "/metrics");
    }

    @Override
    public List<ExporterSample> scrapeExporter(ConnectionConfig cfg, NodeEndpoint node) {
        URI uri = exporterUri(cfg, node);
        String where = uri.getHost() + ":" + uri.getPort();
        Duration timeout = readTimeout.isZero() ? DEFAULT_READ_TIMEOUT : readTimeout;
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "text/plain; version=0.0.4")
                .GET().build();
        try {
            HttpResponse<String> r = http().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) {
                throw new JmxUnavailableException("jmx_exporter on " + where + " answered HTTP " + r.statusCode(), null);
            }
            return ExporterParser.parse(r.body());
        } catch (HttpConnectTimeoutException e) {
            throw new JmxUnavailableException("timed out after " + seconds(connectTimeout) + " connecting to jmx_exporter on "
                    + where, e);
        } catch (HttpTimeoutException e) {
            throw new JmxUnavailableException("timed out after " + seconds(timeout) + " reading jmx_exporter on " + where, e);
        } catch (ConnectException e) {
            throw new JmxUnavailableException("jmx_exporter not listening on " + where, e);
        } catch (IOException e) {
            throw new JmxUnavailableException("jmx_exporter on " + where + " failed: " + rootMessage(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JmxUnavailableException("interrupted while reading jmx_exporter on " + where, e);
        }
    }

    private synchronized HttpClient http() {
        if (closed) throw new IllegalStateException("JMX access is closed");
        if (http == null) {
            // Node addresses are internal: never through the corporate HTTP proxy.
            http = HttpClient.newBuilder().connectTimeout(connectTimeout).proxy(HttpClient.Builder.NO_PROXY)
                    .followRedirects(HttpClient.Redirect.NEVER).build();
        }
        return http;
    }

    @Override
    public void closeConnection(String connectionId) {
        slots.entrySet().removeIf(e -> {
            if (!e.getKey().connectionId().equals(connectionId)) return false;
            e.getValue().retire();
            return true;
        });
    }

    @Override
    public void close() {
        closed = true;
        slots.values().forEach(Slot::retire);
        slots.clear();
        ssh.close();
        synchronized (this) {
            if (http != null) http.close();
            http = null;
        }
    }

    /** Open sessions, for tests and diagnostics. */
    int openSessions() {
        return (int) slots.values().stream().filter(s -> s.session != null).count();
    }

    private static String nodeKey(NodeEndpoint node) {
        return node.hostId() != null && !node.hostId().isBlank() ? node.hostId() : node.address();
    }

    /** Hash of everything that shapes a session, so a settings or secret change reconnects. */
    private static String fingerprint(ConnectionConfig cfg, Map<String, String> secrets, NodeEndpoint node) {
        StringBuilder b = new StringBuilder().append(cfg.jmx()).append('|').append(cfg.ssh()).append('|')
                .append(cfg.tls()).append('|').append(node.address());
        for (String k : List.of(SecretKeys.JMX_PASSWORD, SecretKeys.SSH_PASSWORD, SecretKeys.SSH_PASSPHRASE,
                SecretKeys.TRUSTSTORE_PASSWORD, SecretKeys.KEYSTORE_PASSWORD)) {
            b.append('|').append(secrets.getOrDefault(k, ""));
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(b.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void closeQuietly(JMXConnector c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException | RuntimeException ignored) {
            // best effort
        }
    }

    /**
     * Stub endpoints through one SSH session: each port a node advertises (the registry's, and the
     * RMI server's, which is random unless rmi.port is set) gets its own local forward, made once.
     */
    private static final class TunnelPorts implements RmiStubs.Relocation {
        private final SshConnection tunnel;
        private final Map<String, Integer> localPorts = new ConcurrentHashMap<>();

        TunnelPorts(SshConnection tunnel, int jmxPort, int registryLocalPort) {
            this.tunnel = tunnel;
            localPorts.put("127.0.0.1:" + jmxPort, registryLocalPort);
        }

        @Override
        public RmiStubs.Endpoint map(String host, int port) throws IOException {
            try {
                // The advertised host is as the node sees it, so the forward resolves it there.
                int local = localPorts.computeIfAbsent(host + ":" + port, k -> tunnel.forwardLocalPort(host, port));
                return new RmiStubs.Endpoint("127.0.0.1", local);
            } catch (SshAccessException e) {
                throw new IOException(e.getMessage(), e);
            }
        }
    }

    /** One open session; {@link #mbeans()} marks it broken on any I/O failure so the next call reconnects. */
    private final class NodeSession implements JmxSession {
        final String fingerprint;
        final String route;
        final JMXConnector connector;
        final MBeanServerConnection raw;
        final MBeanServerConnection guarded;
        final SshConnection tunnel;
        final RmiSockets sockets;
        volatile boolean broken;
        volatile long lastOk = clock.getAsLong();

        NodeSession(String fingerprint, String route, JMXConnector connector, SshConnection tunnel, RmiSockets sockets)
                throws IOException {
            this.fingerprint = fingerprint;
            this.route = route;
            this.connector = connector;
            this.raw = connector.getMBeanServerConnection();
            this.tunnel = tunnel;
            this.sockets = sockets;
            this.guarded = (MBeanServerConnection) Proxy.newProxyInstance(DefaultJmxAccess.class.getClassLoader(),
                    new Class<?>[] {MBeanServerConnection.class}, (proxy, m, args) -> {
                        if (m.getDeclaringClass() == Object.class) {
                            return switch (m.getName()) {
                                case "equals" -> proxy == args[0];
                                case "hashCode" -> System.identityHashCode(proxy);
                                default -> "JmxSession[" + route + "]";
                            };
                        }
                        try {
                            Object r = m.invoke(raw, args);
                            lastOk = clock.getAsLong();
                            return r;
                        } catch (InvocationTargetException e) {
                            if (e.getCause() instanceof IOException) broken = true;
                            throw e.getCause();
                        }
                    });
        }

        boolean transportOpen() {
            return tunnel == null || tunnel.isOpen();
        }

        @Override
        public MBeanServerConnection mbeans() {
            return guarded;
        }

        @Override
        public String route() {
            return route;
        }

        void close() {
            broken = true;
            sockets.readTimeout(Duration.ofSeconds(2)); // a dead node must not hold up the close
            closeQuietly(connector);
            sockets.close();
            if (tunnel != null) tunnel.close();
        }
    }
}
