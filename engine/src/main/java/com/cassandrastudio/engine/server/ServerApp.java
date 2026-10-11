package com.cassandrastudio.engine.server;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.Version;
import com.cassandrastudio.engine.alerts.StudioAlert;
import com.cassandrastudio.engine.api.EngineServer;
import com.cassandrastudio.engine.api.ServerMode;
import com.cassandrastudio.engine.audit.Actor;
import com.cassandrastudio.engine.audit.AuditChain;
import com.cassandrastudio.engine.audit.AuditLog;
import com.cassandrastudio.engine.jobs.JobService;
import com.cassandrastudio.engine.jobs.JobTask;
import com.cassandrastudio.engine.sched.Schedule;
import com.cassandrastudio.engine.sched.ScheduledTask;
import com.cassandrastudio.engine.secrets.DbSecretStore;
import com.cassandrastudio.engine.secrets.SecretStore;
import com.cassandrastudio.engine.secrets.VaultSecretStore;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.Json;
import io.javalin.config.JavalinConfig;
import io.javalin.config.RoutesConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Studio Server (requirement 4.8): the desktop engine, headless, with named API tokens, TLS, a shared
 * PostgreSQL or SQLite store, leader election for schedules, server-side secrets and audit export.
 * {@link ServerMain} starts it; tests start it in-process.
 */
public final class ServerApp implements ServerMode, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ServerApp.class);
    /** The engine's own user in the audit log, for work not started by a person (start-up, leader changes). */
    public static final String SERVER_ACTOR = "studio-server";

    private final ServerConfig cfg;
    public final Database db;
    public final Engine engine;
    public final TokenStore tokens;
    public final LeaderElection leader;
    public final AuditExporter exporter;
    private final EngineServer http;
    private final boolean tls;

    private ServerApp(ServerConfig cfg) {
        this.cfg = cfg;
        this.tls = cfg.keystore() != null;
        Database d = Database.connect(cfg.storeUrl(), cfg.get("store.user"), cfg.secret("store.password"));
        Engine e = null;
        AuditExporter x = null;
        LeaderElection l = null;
        EngineServer h = null;
        try {
            SecretStore secrets = secretStore(cfg, d);
            e = new Engine(d, secrets, SERVER_ACTOR);
            x = AuditExporter.fromConfig(cfg);
            if (!x.isEmpty()) e.audit.addListener(x::accept);
            tokens = new TokenStore(d);
            registerScheduleTypes(e);
            Engine engine0 = e;
            l = new LeaderElection(d, cfg.instanceId(), AuditExporter.hostName(), Version.VERSION, cfg.leaseSeconds() * 1000L,
                    () -> engine0.schedules.tick(System.currentTimeMillis()));
            e.schedules.setLeader(l::isLeader);
            this.db = d;
            this.engine = e;
            this.exporter = x;
            this.leader = l;
            // Routes (and with them every feature's schedule types) exist before the first election round.
            h = new EngineServer(e, new EngineServer.Options(cfg.host(), cfg.port(), null, cfg.uiDir(), false), this);
            this.http = h;
            l.start();
            e.schedules.start();
            Actor.as(SERVER_ACTOR, () -> engine0.audit.record(null, null, "server", "start",
                    "instance " + cfg.instanceId() + ", version " + Version.VERSION + ", store " + d.description()
                            + (tls ? ", TLS" : ", plain HTTP"), AuditLog.Outcome.SUCCESS, null));
        } catch (RuntimeException ex) {
            if (h != null) h.close();
            if (l != null) l.close();
            if (x != null) x.close();
            if (e != null) e.close();
            else d.close();
            throw ex;
        }
    }

    /** Validates {@code cfg} and starts a server instance. */
    public static ServerApp start(ServerConfig cfg) {
        return new ServerApp(cfg.validate());
    }

    public int port() {
        return http.port();
    }

    public boolean tls() {
        return tls;
    }

    // ---- secrets (SRV-3) ------------------------------------------------------

    /**
     * Vault (secrets.provider=vault) or the store's encrypted table with a key from secrets.keyFile /
     * STUDIO_SECRETS_KEY. With neither set, a key is created next to the data on first start (single server).
     */
    static SecretStore secretStore(ServerConfig cfg, Database db) {
        if ("vault".equals(cfg.get("secrets.provider", "local"))) {
            VaultSecretStore v = new VaultSecretStore(cfg.get("vault.address"), cfg.secret("vault.token"), cfg.get("vault.mount"),
                    cfg.get("vault.path"), cfg.get("vault.namespace"), cfg.get("vault.caFile"));
            v.check();
            return v;
        }
        return new DbSecretStore(db, DbSecretStore.decodeKey(secretKey(cfg)));
    }

    private static String secretKey(ServerConfig cfg) {
        String inline = cfg.get("secrets.key");
        if (inline != null) return inline;
        Path file = cfg.path("secrets.keyFile");
        if (file == null) file = cfg.dataDir().resolve("secret.key");
        try {
            if (Files.exists(file)) return Files.readString(file, StandardCharsets.US_ASCII).strip();
            if (cfg.path("secrets.keyFile") != null) throw new IllegalArgumentException("secrets.keyFile " + file + " does not exist");
            Files.createDirectories(file.toAbsolutePath().getParent());
            String k = DbSecretStore.newKey();
            Files.writeString(file, k + "\n", StandardCharsets.US_ASCII);
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // not a POSIX file system
            }
            LOG.warn("Created the secrets key {}: back it up; every server instance needs the same key", file);
            return k;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read or create the secrets key " + file + ": " + e.getMessage(), e);
        }
    }

    // ---- built-in schedule type ---------------------------------------------

    /** "audit-verify": checks the audit chain on a schedule and raises a RED alert when it is broken. */
    private static void registerScheduleTypes(Engine engine) {
        engine.schedules.register("audit-verify", new ScheduledTask() {
            @Override
            public JobService.Spec spec(Schedule s) {
                return new JobService.Spec(null, "audit-verify", "Verify audit log: " + s.name(), null, null, false);
            }

            @Override
            public JobTask task(Schedule s) {
                return ctx -> {
                    ctx.progress(null, "Checking the audit chain");
                    AuditChain.Verification v = AuditChain.verify(engine.db);
                    engine.alerts.publish(new StudioAlert(null, null, null, "audit", "chain",
                            v.ok() ? StudioAlert.Severity.GREEN : StudioAlert.Severity.RED,
                            v.ok() ? "Audit log intact" : "Audit log was tampered with",
                            v.ok() ? v.checked() + " entries checked" : "First broken entry " + v.brokenAt() + ": " + v.reason(),
                            System.currentTimeMillis()));
                    ctx.log(v.ok() ? "Audit chain intact: " + v.checked() + " entries" : "Broken at " + v.brokenAt() + ": " + v.reason());
                    if (!v.ok()) throw new IllegalStateException("Audit chain broken at entry " + v.brokenAt() + ": " + v.reason());
                    return v;
                };
            }
        });
    }

    // ---- ServerMode ------------------------------------------------------------

    @Override
    public String authenticate(String token) {
        return tokens.authenticate(token);
    }

    @Override
    public boolean isPublic(String path) {
        return path.equals("/api/health") || path.equals("/api/health/active");
    }

    @Override
    public void configure(JavalinConfig c) {
        if (tls) {
            c.jetty.addConnector((server, httpConfig) -> {
                SslContextFactory.Server ssl = new SslContextFactory.Server();
                ssl.setKeyStore(loadKeystore());
                ssl.setKeyStorePassword(cfg.secret("tls.keystorePassword"));
                ssl.setIncludeProtocols("TLSv1.3", "TLSv1.2");
                HttpConfiguration https = new HttpConfiguration(httpConfig);
                SecureRequestCustomizer src = new SecureRequestCustomizer();
                src.setSniHostCheck(false); // clients reach the server by IP or by names not in the certificate's SAN
                https.addCustomizer(src);
                ServerConnector connector = new ServerConnector(server, new SslConnectionFactory(ssl, "http/1.1"),
                        new HttpConnectionFactory(https));
                connector.setHost(cfg.host());
                connector.setPort(cfg.port());
                return connector;
            });
        }
        if (!cfg.corsOrigins().isEmpty()) {
            c.bundledPlugins.enableCors(cors -> cors.addRule(rule -> {
                if (cfg.corsOrigins().contains("*")) rule.anyHost();
                else rule.allowHost(cfg.corsOrigins().get(0), cfg.corsOrigins().subList(1, cfg.corsOrigins().size()).toArray(String[]::new));
                rule.exposeHeader("Content-Disposition");
            }));
        }
    }

    private KeyStore loadKeystore() {
        try (InputStream in = Files.newInputStream(cfg.keystore())) {
            KeyStore ks = KeyStore.getInstance(cfg.get("tls.keystoreType", "PKCS12"));
            ks.load(in, cfg.secret("tls.keystorePassword").toCharArray());
            return ks;
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot load tls.keystore " + cfg.keystore() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void routes(RoutesConfig app) {
        // Load balancers: 200 while this instance can reach the store; /active only on the leader (active/passive).
        app.get("/api/health", ctx -> {
            boolean up = db.ping();
            ctx.status(up ? 200 : 503).json(health(up));
        });
        app.get("/api/health/active", ctx -> {
            boolean up = db.ping() && leader.isLeader();
            ctx.status(up ? 200 : 503).json(health(up));
        });
        app.get("/api/server/info", ctx -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mode", "server");
            m.put("version", Version.VERSION);
            m.put("user", Actor.current());
            m.put("instanceId", leader.instanceId());
            m.put("leader", leader.isLeader());
            m.put("store", db.dialect().name().toLowerCase());
            m.put("secretStore", engine.connections.secretStoreDescription());
            m.put("tls", tls);
            ctx.json(m);
        });
        app.get("/api/server/status", ctx -> {
            Map<String, Object> m = new LinkedHashMap<>(leader.status());
            m.put("store", db.description());
            m.put("auditExport", exporter.status());
            ctx.json(m);
        });
    }

    private Map<String, Object> health(boolean up) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", up ? "UP" : "DOWN");
        m.put("role", leader.isLeader() ? "leader" : "standby");
        m.put("instanceId", leader.instanceId());
        m.put("version", Version.VERSION);
        return m;
    }

    /** One line for logs and the launcher: {@code STUDIO_SERVER_READY {...}}. */
    public String readyLine() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("port", port());
        m.put("tls", tls);
        m.put("instanceId", leader.instanceId());
        m.put("store", db.dialect().name().toLowerCase());
        m.put("version", Version.VERSION);
        return "STUDIO_SERVER_READY " + Json.write(m);
    }

    @Override
    public void close() {
        http.close();
        leader.close(); // gives the lease up: another instance takes over at once
        exporter.close();
        engine.close();
    }
}
