package com.cassandrastudio.engine.server;

import java.io.IOException;
import java.io.Reader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Studio Server settings (docs/guide/server.md): a Java properties file, each key overridable by an
 * environment variable {@code STUDIO_<KEY>} (upper case, dots as underscores: {@code store.password} is
 * {@code STUDIO_STORE_PASSWORD}). Secrets can also come from files ({@code *File} keys), e.g. Docker or
 * Kubernetes secrets.
 */
public final class ServerConfig {
    /** Every key the server reads; anything else in the file is reported as a typo. */
    static final Set<String> KEYS = new TreeSet<>(List.of(
            "server.host", "server.port", "server.insecureHttp", "server.dataDir", "server.uiDir", "server.instanceId",
            "server.corsOrigins",
            "tls.keystore", "tls.keystorePassword", "tls.keystorePasswordFile", "tls.keystoreType",
            "store.url", "store.user", "store.password", "store.passwordFile",
            "secrets.provider", "secrets.keyFile", "secrets.key",
            "vault.address", "vault.token", "vault.tokenFile", "vault.mount", "vault.path", "vault.namespace", "vault.caFile",
            "audit.syslog.host", "audit.syslog.port", "audit.syslog.protocol", "audit.syslog.facility",
            "audit.syslog.appName", "audit.syslog.framing",
            "audit.json.file", "audit.json.host", "audit.json.port",
            "ha.leaseSeconds"));

    private final Properties props;
    private final Map<String, String> env;
    private final Path baseDir;

    private ServerConfig(Properties props, Map<String, String> env, Path baseDir) {
        this.props = props;
        this.env = env;
        this.baseDir = baseDir;
    }

    /** Reads {@code file} (may be null: environment and defaults only). */
    public static ServerConfig load(Path file, Map<String, String> env) {
        Properties p = new Properties();
        if (file != null) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read server config " + file + ": " + e.getMessage(), e);
            }
            List<String> unknown = p.stringPropertyNames().stream().filter(k -> !KEYS.contains(k)).sorted().toList();
            if (!unknown.isEmpty()) throw new IllegalArgumentException("Unknown setting(s) in " + file + ": " + unknown);
        }
        Path base = file == null ? Path.of("").toAbsolutePath() : file.toAbsolutePath().getParent();
        return new ServerConfig(p, env, base);
    }

    /** For tests and the CLI: settings given as key/value pairs. */
    public static ServerConfig of(Map<String, String> settings) {
        Properties p = new Properties();
        p.putAll(settings);
        return new ServerConfig(p, Map.of(), Path.of("").toAbsolutePath());
    }

    /** Applies command-line overrides (e.g. {@code --insecure-http}). */
    public ServerConfig with(String key, String value) {
        Properties p = new Properties();
        p.putAll(props);
        p.setProperty(key, value);
        return new ServerConfig(p, env, baseDir);
    }

    public String get(String key) {
        String e = env.get("STUDIO_" + key.replace('.', '_').toUpperCase(Locale.ROOT));
        if (e != null && !e.isBlank()) return e.strip();
        String v = props.getProperty(key);
        return v == null || v.isBlank() ? null : v.strip();
    }

    public String get(String key, String dflt) {
        String v = get(key);
        return v == null ? dflt : v;
    }

    public int getInt(String key, int dflt) {
        String v = get(key);
        try {
            return v == null ? dflt : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number, got " + v);
        }
    }

    public boolean getBool(String key) {
        return Boolean.parseBoolean(get(key, "false"));
    }

    /** A path setting, relative to the config file's directory. */
    public Path path(String key) {
        String v = get(key);
        return v == null ? null : baseDir.resolve(v).normalize();
    }

    /** A secret given inline ({@code key}) or in a file ({@code keyFile}); the file wins when both are set. */
    public String secret(String key) {
        Path f = path(key + "File");
        if (f != null) {
            try {
                return Files.readString(f, StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read " + key + "File " + f + ": " + e.getMessage(), e);
            }
        }
        return get(key);
    }

    // ---- typed settings --------------------------------------------------

    public String host() {
        return get("server.host", "127.0.0.1");
    }

    public int port() {
        return getInt("server.port", 8443);
    }

    public boolean insecureHttp() {
        return getBool("server.insecureHttp");
    }

    public Path dataDir() {
        Path p = path("server.dataDir");
        return p != null ? p : Path.of("/var/lib/cassandra-studio");
    }

    public Path uiDir() {
        return path("server.uiDir");
    }

    public List<String> corsOrigins() {
        String v = get("server.corsOrigins");
        return v == null ? List.of() : Arrays.stream(v.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    public Path keystore() {
        return path("tls.keystore");
    }

    public String storeUrl() {
        return get("store.url", "jdbc:sqlite:" + dataDir().resolve("studio.db"));
    }

    public String instanceId() {
        String v = get("server.instanceId");
        if (v != null) return v;
        try {
            return InetAddress.getLocalHost().getHostName() + ":" + port();
        } catch (UnknownHostException e) {
            return "studio-server:" + port();
        }
    }

    public int leaseSeconds() {
        int s = getInt("ha.leaseSeconds", 20);
        if (s < 4 || s > 600) throw new IllegalArgumentException("ha.leaseSeconds must be between 4 and 600");
        return s;
    }

    /** Checks the settings before anything starts; returns them (TLS on non-loopback binds, ranges). */
    public ServerConfig validate() {
        List<String> problems = new ArrayList<>();
        int port = port();
        if (port < 0 || port > 65535) problems.add("server.port must be 0..65535");
        Path ks = keystore();
        if (ks != null) {
            if (!Files.isReadable(ks)) problems.add("tls.keystore " + ks + " is not readable");
            if (secret("tls.keystorePassword") == null) problems.add("tls.keystorePassword (or tls.keystorePasswordFile) is required with tls.keystore");
        } else if (!isLoopback(host()) && !insecureHttp()) {
            problems.add("Refusing to listen on " + host() + " without TLS: set tls.keystore (a PKCS12 file), or pass"
                    + " --insecure-http (server.insecureHttp=true) only when a TLS-terminating proxy sits in front");
        }
        String url = storeUrl();
        if (!url.startsWith("jdbc:postgresql:") && !url.startsWith("jdbc:sqlite:")) {
            problems.add("store.url must start with jdbc:postgresql: or jdbc:sqlite:");
        }
        String provider = get("secrets.provider", "local");
        if (!provider.equals("local") && !provider.equals("vault")) problems.add("secrets.provider must be local or vault");
        if (provider.equals("vault") && get("vault.address") == null) problems.add("vault.address is required with secrets.provider=vault");
        String proto = get("audit.syslog.protocol", "udp");
        if (!proto.equals("udp") && !proto.equals("tcp")) problems.add("audit.syslog.protocol must be udp or tcp");
        String framing = get("audit.syslog.framing", "octet-counting");
        if (!framing.equals("octet-counting") && !framing.equals("newline")) {
            problems.add("audit.syslog.framing must be octet-counting or newline");
        }
        int facility = getInt("audit.syslog.facility", 13);
        if (facility < 0 || facility > 23) problems.add("audit.syslog.facility must be 0..23");
        try {
            leaseSeconds();
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
        }
        if (!problems.isEmpty()) throw new IllegalArgumentException(String.join("; ", problems));
        return this;
    }

    static boolean isLoopback(String host) {
        if (host.equals("localhost")) return true;
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
