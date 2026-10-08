package com.cassandrastudio.engine.cql;

import com.cassandrastudio.engine.conn.ConnectionRepository;
import com.cassandrastudio.engine.conn.HostPort;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.SecretKeys;
import com.cassandrastudio.engine.util.ApiException;
import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.config.ProgrammaticDriverConfigLoaderBuilder;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.ssl.ProgrammaticSslEngineFactory;
import com.datastax.oss.driver.internal.core.DefaultProtocolFeature;
import com.datastax.oss.driver.internal.core.context.InternalDriverContext;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Open CQL sessions, one per connected cluster (CON-4, CON-5, CON-11).
 *
 * <p>An editor tab can have its own current keyspace. On protocol v5
 * (Cassandra 4.0+) that is set per statement. Older protocols cannot do that,
 * so a separate session bound to the keyspace is opened on demand rather than
 * running {@code USE} on the shared session and changing it for every tab.
 */
public final class SessionManager implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SessionManager.class);
    private static final int MAX_KEYSPACE_SESSIONS = 8;

    private final ConnectionRepository connections;
    private final Map<String, CqlSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Map<String, CqlSession>> keyspaceSessions = new ConcurrentHashMap<>();

    public SessionManager(ConnectionRepository connections) {
        this.connections = connections;
    }

    /** Opens (or returns) the session for a saved connection. */
    public CqlSession session(String connectionId) {
        CqlSession s = sessions.get(connectionId);
        if (s != null && !s.isClosed()) return s;
        synchronized (this) {
            s = sessions.get(connectionId);
            if (s != null && !s.isClosed()) return s;
            ConnectionConfig cfg = connections.get(connectionId);
            s = open(cfg, secretsOf(cfg), null);
            sessions.put(connectionId, s);
            return s;
        }
    }

    public boolean isConnected(String connectionId) {
        CqlSession s = sessions.get(connectionId);
        return s != null && !s.isClosed();
    }

    /** A session to run a statement in {@code keyspace} (may be the shared one). */
    public SessionAndKeyspace sessionFor(String connectionId, String keyspace) {
        CqlSession base = session(connectionId);
        if (keyspace == null || keyspace.isBlank()) return new SessionAndKeyspace(base, null);
        if (supportsPerRequestKeyspace(base)) return new SessionAndKeyspace(base, keyspace);
        Map<String, CqlSession> byKs = keyspaceSessions.computeIfAbsent(connectionId, k -> new ConcurrentHashMap<>());
        CqlSession ks = byKs.get(keyspace);
        if (ks != null && !ks.isClosed()) return new SessionAndKeyspace(ks, null);
        synchronized (this) {
            if (byKs.size() >= MAX_KEYSPACE_SESSIONS) {
                byKs.values().forEach(CqlSession::closeAsync);
                byKs.clear();
            }
            ConnectionConfig cfg = connections.get(connectionId);
            ks = open(cfg, secretsOf(cfg), keyspace);
            byKs.put(keyspace, ks);
            return new SessionAndKeyspace(ks, null);
        }
    }

    public record SessionAndKeyspace(CqlSession session, String perRequestKeyspace) {}

    public static boolean supportsPerRequestKeyspace(CqlSession s) {
        InternalDriverContext ctx = (InternalDriverContext) s.getContext();
        return ctx.getProtocolVersionRegistry().supports(ctx.getProtocolVersion(), DefaultProtocolFeature.PER_REQUEST_KEYSPACE);
    }

    public void disconnect(String connectionId) {
        CqlSession s = sessions.remove(connectionId);
        if (s != null) s.closeAsync();
        Map<String, CqlSession> byKs = keyspaceSessions.remove(connectionId);
        if (byKs != null) byKs.values().forEach(CqlSession::closeAsync);
    }

    /** Opens a throw-away session to check a config before it is saved. */
    public TestResult test(ConnectionConfig cfg, Map<String, String> secrets) {
        long start = System.nanoTime();
        try (CqlSession s = open(cfg, secrets, null)) {
            var row = s.execute("SELECT cluster_name, release_version, data_center FROM system.local").one();
            int nodes = s.getMetadata().getNodes().size();
            return new TestResult(true, null, row == null ? null : row.getString("cluster_name"),
                    row == null ? null : row.getString("release_version"), nodes,
                    s.getContext().getProtocolVersion().toString(), (System.nanoTime() - start) / 1_000_000);
        } catch (Exception e) {
            return new TestResult(false, Errors.describe(e), null, null, 0, null, (System.nanoTime() - start) / 1_000_000);
        }
    }

    public record TestResult(boolean ok, String error, String clusterName, String version, int nodes,
                             String protocolVersion, long elapsedMs) {}

    /** Finds a node by host id or by "address" / "address:port". */
    public static Node findNode(CqlSession s, String ref) {
        Collection<Node> nodes = s.getMetadata().getNodes().values();
        for (Node n : nodes) {
            if (n.getHostId() != null && n.getHostId().toString().equals(ref)) return n;
        }
        for (Node n : nodes) {
            if (ref.equals(address(n))) return n;
        }
        for (Node n : nodes) {
            if (n.getEndPoint().resolve() instanceof InetSocketAddress a) {
                String ip = a.getAddress() == null ? a.getHostString() : a.getAddress().getHostAddress();
                if (ref.equals(ip) || ref.equals(ip + ":" + a.getPort()) || ref.equals(a.getHostString())) return n;
            }
        }
        throw ApiException.badRequest("Node '" + ref + "' is not part of this cluster");
    }

    public static String address(Node n) {
        if (n.getBroadcastRpcAddress().isPresent()) return n.getBroadcastRpcAddress().get().getAddress().getHostAddress();
        if (n.getEndPoint().resolve() instanceof InetSocketAddress a) {
            return a.getAddress() == null ? a.getHostString() : a.getAddress().getHostAddress();
        }
        return n.getEndPoint().toString();
    }

    private Map<String, String> secretsOf(ConnectionConfig cfg) {
        Map<String, String> m = new java.util.HashMap<>();
        for (String k : SecretKeys.ALL) connections.secret(cfg.id(), k).ifPresent(v -> m.put(k, v));
        return m;
    }

    static CqlSession open(ConnectionConfig cfg, Map<String, String> secrets, String keyspace) {
        ProgrammaticDriverConfigLoaderBuilder conf = DriverConfigLoader.programmaticBuilder()
                .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofMillis(cfg.requestTimeoutMs()))
                .withInt(DefaultDriverOption.REQUEST_PAGE_SIZE, cfg.pageSize())
                .withString(DefaultDriverOption.REQUEST_CONSISTENCY, cfg.defaultConsistency())
                .withDuration(DefaultDriverOption.CONNECTION_CONNECT_TIMEOUT, Duration.ofSeconds(10))
                .withDuration(DefaultDriverOption.CONNECTION_INIT_QUERY_TIMEOUT, Duration.ofSeconds(10))
                .withDuration(DefaultDriverOption.CONTROL_CONNECTION_TIMEOUT, Duration.ofSeconds(10))
                .withDuration(DefaultDriverOption.METADATA_SCHEMA_REQUEST_TIMEOUT, Duration.ofSeconds(30))
                .withBoolean(DefaultDriverOption.REQUEST_WARN_IF_SET_KEYSPACE, false)
                // The driver skips system keyspaces by default; the schema browser shows them on request (SCH-1).
                .withStringList(DefaultDriverOption.METADATA_SCHEMA_REFRESHED_KEYSPACES, List.of())
                .withInt(DefaultDriverOption.CONNECTION_POOL_LOCAL_SIZE, 1)
                .withInt(DefaultDriverOption.CONNECTION_POOL_REMOTE_SIZE, 1)
                // Keep a pool to every node in every DC so any node can be pinned as coordinator
                // (CQL-3). LOCAL_* consistency levels still never fail over to a remote DC.
                .withInt(DefaultDriverOption.LOAD_BALANCING_DC_FAILOVER_MAX_NODES_PER_REMOTE_DC, 10_000)
                .withBoolean(DefaultDriverOption.LOAD_BALANCING_DC_FAILOVER_ALLOW_FOR_LOCAL_CONSISTENCY_LEVELS, false)
                .withString(DefaultDriverOption.SESSION_NAME, "studio-" + cfg.name());
        if (!"auto".equalsIgnoreCase(cfg.protocolVersion())) {
            conf.withString(DefaultDriverOption.PROTOCOL_VERSION, cfg.protocolVersion().toUpperCase());
        }
        if (cfg.localDatacenter() == null || cfg.localDatacenter().isBlank()) {
            conf.withString(DefaultDriverOption.LOAD_BALANCING_POLICY_CLASS, "DcInferringLoadBalancingPolicy");
        }

        List<InetSocketAddress> points = new ArrayList<>();
        for (String cp : cfg.contactPoints()) {
            HostPort hp = HostPort.parse(cp, ConnectionConfig.DEFAULT_CQL_PORT);
            points.add(new InetSocketAddress(hp.host(), hp.port()));
        }
        CqlSessionBuilder b = CqlSession.builder()
                .withConfigLoader(conf.build())
                .addContactPoints(points)
                .withApplicationName("Cassandra Studio")
                .withClientId(UUID.randomUUID());
        if (cfg.localDatacenter() != null && !cfg.localDatacenter().isBlank()) b.withLocalDatacenter(cfg.localDatacenter());
        if (cfg.username() != null && !cfg.username().isBlank()) {
            b.withAuthCredentials(cfg.username(), Optional.ofNullable(secrets.get(SecretKeys.PASSWORD)).orElse(""));
        }
        if (cfg.tls().enabled()) {
            b.withSslEngineFactory(new ProgrammaticSslEngineFactory(sslContext(cfg.tls(), secrets), null,
                    cfg.tls().hostnameVerification()));
        }
        if (keyspace != null) b.withKeyspace(CqlIdentifier.fromInternal(keyspace));
        try {
            CqlSession s = b.build();
            LOG.info("Connected to {} (protocol {})", cfg.name(), s.getContext().getProtocolVersion());
            return s;
        } catch (RuntimeException e) {
            throw new ApiException(502, "connect_failed", "Cannot connect to " + cfg.name() + ": " + Errors.describe(e));
        }
    }

    static SSLContext sslContext(ConnectionConfig.Tls tls, Map<String, String> secrets) {
        try {
            TrustManagerFactory tmf = null;
            if (tls.truststorePath() != null && !tls.truststorePath().isBlank()) {
                KeyStore ts = loadStore(tls.truststorePath(), tls.truststoreType(), secrets.get(SecretKeys.TRUSTSTORE_PASSWORD));
                tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ts);
            }
            KeyManagerFactory kmf = null;
            if (tls.keystorePath() != null && !tls.keystorePath().isBlank()) {
                String pw = secrets.get(SecretKeys.KEYSTORE_PASSWORD);
                KeyStore ks = loadStore(tls.keystorePath(), tls.keystoreType(), pw);
                kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, pw == null ? new char[0] : pw.toCharArray());
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf == null ? null : kmf.getKeyManagers(), tmf == null ? null : tmf.getTrustManagers(), null);
            return ctx;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("TLS setup failed: " + Errors.describe(e));
        }
    }

    /** JKS, PKCS12, or PEM (one or more certificates; trust only). */
    static KeyStore loadStore(String path, String type, String password) throws Exception {
        Path p = Path.of(path);
        if (!Files.isReadable(p)) throw ApiException.badRequest("Cannot read " + path);
        String t = type == null || type.isBlank() ? guessType(path) : type.toUpperCase();
        if (t.equals("PEM")) {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            try (InputStream in = Files.newInputStream(p)) {
                int i = 0;
                for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                    ks.setCertificateEntry("cert-" + i++, c);
                }
            }
            return ks;
        }
        KeyStore ks = KeyStore.getInstance(t);
        try (InputStream in = Files.newInputStream(p)) {
            ks.load(in, password == null ? null : password.toCharArray());
        }
        return ks;
    }

    private static String guessType(String path) {
        String l = path.toLowerCase();
        if (l.endsWith(".pem") || l.endsWith(".crt") || l.endsWith(".cer")) return "PEM";
        if (l.endsWith(".p12") || l.endsWith(".pfx")) return "PKCS12";
        return "JKS";
    }

    @Override
    public void close() {
        sessions.keySet().forEach(this::disconnect);
    }
}
