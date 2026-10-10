package com.cassandrastudio.engine.server;

import com.cassandrastudio.engine.audit.AuditChain;
import com.cassandrastudio.engine.audit.AuditLog;
import com.cassandrastudio.engine.secrets.DbSecretStore;
import com.cassandrastudio.engine.store.CrashLog;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.Json;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * The {@code studio-server} command (docs/guide/server.md).
 *
 * <pre>
 *   studio-server [run] [--config FILE] [--insecure-http]   start the server
 *   studio-server token add USER [--label TEXT]             create an API token (printed once)
 *   studio-server token list                                list tokens (never the tokens themselves)
 *   studio-server token revoke ID|USER                      revoke one token, or all of a user's
 *   studio-server audit verify                              check the audit log's hash chain
 *   studio-server secret-key                                print a new key for secrets.keyFile
 *   studio-server health                                    exit 0 when this host's server answers /api/health
 * </pre>
 * The config file is {@code --config}, else {@code $STUDIO_CONFIG}, else /etc/cassandra-studio/server.conf when present.
 */
public final class ServerMain {
    private ServerMain() {}

    public static void main(String[] args) {
        int code;
        try {
            code = run(args, System.out, System.err, System.getenv());
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("studio-server: " + e.getMessage());
            code = 2;
        }
        if (code >= 0) System.exit(code);
    }

    /** Runs a command; returns the exit code, or -1 when the server was started and keeps running. */
    static int run(String[] args, PrintStream out, PrintStream err, Map<String, String> env) {
        List<String> pos = new ArrayList<>();
        Path config = null;
        String label = null;
        boolean insecure = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> config = Path.of(need(args, ++i, "--config"));
                case "--label" -> label = need(args, ++i, "--label");
                case "--insecure-http" -> insecure = true;
                case "--help", "-h" -> {
                    out.println(USAGE);
                    return 0;
                }
                default -> {
                    if (args[i].startsWith("--")) throw new IllegalArgumentException("Unknown option " + args[i] + "\n" + USAGE);
                    pos.add(args[i]);
                }
            }
        }
        if (config == null && env.get("STUDIO_CONFIG") != null) config = Path.of(env.get("STUDIO_CONFIG"));
        if (config == null && Files.exists(Path.of("/etc/cassandra-studio/server.conf"))) config = Path.of("/etc/cassandra-studio/server.conf");
        ServerConfig cfg = ServerConfig.load(config, env);
        if (insecure) cfg = cfg.with("server.insecureHttp", "true");
        String cmd = pos.isEmpty() ? "run" : pos.get(0);
        switch (cmd) {
            case "run" -> {
                serve(cfg, out);
                return -1;
            }
            case "token" -> {
                return token(cfg, pos, label, out, err);
            }
            case "audit" -> {
                if (pos.size() != 2 || !pos.get(1).equals("verify")) throw new IllegalArgumentException("Usage: studio-server audit verify");
                try (Database db = Database.connect(cfg.storeUrl(), cfg.get("store.user"), cfg.secret("store.password"))) {
                    AuditChain.Verification v = AuditChain.verify(db);
                    out.println(Json.write(v));
                    return v.ok() ? 0 : 1;
                }
            }
            case "secret-key" -> {
                out.println(DbSecretStore.newKey());
                return 0;
            }
            case "health" -> {
                return health(cfg, out, err);
            }
            default -> throw new IllegalArgumentException("Unknown command " + cmd + "\n" + USAGE);
        }
    }

    static final String USAGE = """
            Usage: studio-server [run] [--config FILE] [--insecure-http]
                   studio-server token add USER [--label TEXT] | token list | token revoke ID|USER
                   studio-server audit verify
                   studio-server secret-key
                   studio-server health""";

    private static String need(String[] args, int i, String opt) {
        if (i >= args.length) throw new IllegalArgumentException(opt + " needs a value");
        return args[i];
    }

    private static void serve(ServerConfig cfg, PrintStream out) {
        CrashLog.install(cfg.dataDir());
        ServerApp app = ServerApp.start(cfg);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.close();
        }, "studio-server-shutdown"));
        out.println(app.readyLine());
        out.flush();
    }

    private static int token(ServerConfig cfg, List<String> pos, String label, PrintStream out, PrintStream err) {
        if (pos.size() < 2) throw new IllegalArgumentException("Usage: studio-server token add USER | list | revoke ID|USER");
        String operator = "cli: " + System.getProperty("user.name", "unknown");
        try (Database db = Database.connect(cfg.storeUrl(), cfg.get("store.user"), cfg.secret("store.password"));
             AuditExporter exporter = AuditExporter.fromConfig(cfg)) {
            TokenStore tokens = new TokenStore(db);
            AuditLog audit = new AuditLog(db, operator);
            if (!exporter.isEmpty()) audit.addListener(exporter::accept);
            switch (pos.get(1)) {
                case "add" -> {
                    if (pos.size() != 3) throw new IllegalArgumentException("Usage: studio-server token add USER [--label TEXT]");
                    TokenStore.Created c = tokens.add(pos.get(2), label);
                    audit.record(null, null, "server", "token add", "user " + c.token().user() + ", token id " + c.token().id()
                            + (label == null ? "" : ", label " + label), AuditLog.Outcome.SUCCESS, null);
                    out.println(c.secret());
                    err.println("API token " + c.token().id() + " for " + c.token().user()
                            + " created. It is shown only now; give it to the user.");
                    return 0;
                }
                case "list" -> {
                    out.printf("%-10s %-24s %-20s %-24s %-24s %s%n", "ID", "USER", "LABEL", "CREATED", "LAST USED", "STATUS");
                    for (TokenStore.Token t : tokens.list()) {
                        out.printf("%-10s %-24s %-20s %-24s %-24s %s%n", t.id(), t.user(), t.label() == null ? "" : t.label(),
                                t.createdAt(), t.lastUsedAt() == null ? "never" : t.lastUsedAt(),
                                t.active() ? "active" : "revoked " + t.revokedAt());
                    }
                    return 0;
                }
                case "revoke" -> {
                    if (pos.size() != 3) throw new IllegalArgumentException("Usage: studio-server token revoke ID|USER");
                    int n = tokens.revoke(pos.get(2));
                    audit.record(null, null, "server", "token revoke", pos.get(2) + ": " + n + " token(s)",
                            n > 0 ? AuditLog.Outcome.SUCCESS : AuditLog.Outcome.FAILED, n > 0 ? null : "no active token");
                    if (n == 0) {
                        err.println("No active token with id or user " + pos.get(2));
                        return 1;
                    }
                    out.println("Revoked " + n + " token(s). Servers stop accepting them within " + TokenStore.CACHE_MS / 1000 + " s.");
                    return 0;
                }
                default -> throw new IllegalArgumentException("Unknown token command " + pos.get(1));
            }
        }
    }

    /**
     * Asks this host's server for /api/health (container HEALTHCHECK). The address is the loopback one, so the
     * server's certificate is not checked: only that it answers and its store is reachable.
     */
    private static int health(ServerConfig cfg, PrintStream out, PrintStream err) {
        String host = cfg.host();
        if (host.equals("0.0.0.0") || host.equals("::") || host.equals("localhost")) host = "127.0.0.1";
        if (host.contains(":")) host = "[" + host + "]";
        String url = (cfg.keystore() != null ? "https" : "http") + "://" + host + ":" + cfg.port() + "/api/health";
        try {
            HttpClient.Builder b = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(5));
            if (cfg.keystore() != null) {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[] {new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] c, String a) {}
                    public void checkServerTrusted(X509Certificate[] c, String a) {}
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }}, new SecureRandom());
                b.sslContext(ctx);
                System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
            }
            HttpResponse<String> r = b.build().send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString());
            out.println(r.body());
            return r.statusCode() == 200 ? 0 : 1;
        } catch (Exception e) {
            err.println("Not healthy: " + url + ": " + e);
            return 1;
        }
    }
}
