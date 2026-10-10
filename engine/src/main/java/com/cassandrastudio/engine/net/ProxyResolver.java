package com.cassandrastudio.engine.net;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Decides which proxy, if any, Studio's own HTTP(S) calls use for a URL, from the global
 * {@link NetworkSettings} and, in SYSTEM mode, the environment (HTTPS_PROXY, HTTP_PROXY, NO_PROXY, also
 * lower case) or the Java properties (https.proxyHost/Port, http.proxyHost/Port, http.nonProxyHosts).
 * Loopback addresses are always direct. Pure: the environment and properties are passed in.
 */
public final class ProxyResolver {
    private final NetworkSettings settings;
    private final String password;
    private final Map<String, String> env;
    private final UnaryOperator<String> props;

    public ProxyResolver(NetworkSettings settings, String password, Map<String, String> env, UnaryOperator<String> props) {
        this.settings = settings;
        this.password = password;
        this.env = env;
        this.props = props;
    }

    /** The live process environment and system properties. */
    public static ProxyResolver live(NetworkSettings settings, String password) {
        return new ProxyResolver(settings, password, System.getenv(), System::getProperty);
    }

    /** The proxy for this URL, or empty for a direct connection. */
    public Optional<ProxyEndpoint> forUri(URI uri) {
        String host = uri.getHost();
        if (host == null || isLoopback(host)) return Optional.empty();
        boolean https = "https".equalsIgnoreCase(uri.getScheme()) || "wss".equalsIgnoreCase(uri.getScheme());
        return switch (settings.proxyMode()) {
            case NONE -> Optional.empty();
            case MANUAL -> bypass(settings.noProxy(), host) ? Optional.empty()
                    : Optional.of(new ProxyEndpoint(com.cassandrastudio.engine.model.ConnectionConfig.ProxyType.HTTP,
                            settings.proxyHost(), settings.proxyPort(), settings.proxyUsername(), password));
            case SYSTEM -> system(host, https);
        };
    }

    /** What SYSTEM mode found, for the settings page ("none" when nothing is configured). */
    public String describeSystem() {
        Optional<ProxyEndpoint> p = system("example.invalid", true);
        return p.map(e -> e + " (" + systemSource() + ")").orElse("none (no HTTPS_PROXY or https.proxyHost set)");
    }

    private String systemSource() {
        return env("HTTPS_PROXY") != null ? "HTTPS_PROXY" : "https.proxyHost";
    }

    private Optional<ProxyEndpoint> system(String host, boolean https) {
        String fromEnv = https ? env("HTTPS_PROXY") : env("HTTP_PROXY");
        if (fromEnv != null) {
            String noProxy = env("NO_PROXY");
            if (noProxy != null && bypass(List.of(noProxy.toLowerCase(Locale.ROOT).split("[,\\s]+")), host)) return Optional.empty();
            ProxyEndpoint e = ProxyEndpoint.parse(fromEnv);
            // Settings' user/password override the URL's (keeps the password out of the environment).
            if (settings.proxyUsername() != null) {
                e = new ProxyEndpoint(e.type(), e.host(), e.port(), settings.proxyUsername(), password);
            }
            return Optional.of(e);
        }
        String prefix = https ? "https" : "http";
        String phost = props.apply(prefix + ".proxyHost");
        if (phost == null || phost.isBlank()) return Optional.empty();
        String nonProxy = props.apply("http.nonProxyHosts");
        if (nonProxy != null && bypass(javaNonProxy(nonProxy), host)) return Optional.empty();
        int port;
        try {
            String pp = props.apply(prefix + ".proxyPort");
            port = pp == null || pp.isBlank() ? (https ? 443 : 80) : Integer.parseInt(pp.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(prefix + ".proxyPort is not a number");
        }
        return Optional.of(new ProxyEndpoint(com.cassandrastudio.engine.model.ConnectionConfig.ProxyType.HTTP,
                phost.trim(), port, settings.proxyUsername(), password));
    }

    private String env(String name) {
        // curl's convention: lower case first (HTTP_PROXY upper case is unsafe under CGI, but desktop apps accept both).
        String v = env.get(name.toLowerCase(Locale.ROOT));
        if (v == null || v.isBlank()) v = env.get(name);
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** http.nonProxyHosts uses '|' and '*' wildcards: "*.corp|10.*|localhost". */
    static List<String> javaNonProxy(String value) {
        List<String> out = new ArrayList<>();
        for (String p : value.toLowerCase(Locale.ROOT).split("\\|")) {
            if (!p.isBlank()) out.add(p.trim());
        }
        return out;
    }

    /**
     * Whether {@code host} matches a no-proxy list. Entries: {@code *} (everything), {@code host},
     * {@code domain} or {@code .domain} or {@code *.domain} (the domain and its sub-domains), {@code 10.*} style
     * prefixes, IPv4 addresses and IPv4 CIDR ranges ({@code 10.0.0.0/8}). A {@code :port} suffix is ignored.
     */
    public static boolean bypass(List<String> entries, String host) {
        String h = strip(host.toLowerCase(Locale.ROOT));
        for (String raw : entries) {
            String e = raw.trim().toLowerCase(Locale.ROOT);
            if (e.isEmpty()) continue;
            if (e.equals("*")) return true;
            if (e.contains("/")) {
                if (inCidr(h, e)) return true;
                continue;
            }
            e = strip(e.replaceFirst(":\\d+$", ""));
            if (e.startsWith("*.")) e = e.substring(1);
            if (e.endsWith(".*")) {
                if (h.startsWith(e.substring(0, e.length() - 1))) return true;
                continue;
            }
            if (e.startsWith(".")) {
                if (h.endsWith(e) || h.equals(e.substring(1))) return true;
                continue;
            }
            if (h.equals(e) || h.endsWith("." + e)) return true;
        }
        return false;
    }

    private static String strip(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    static boolean inCidr(String host, String cidr) {
        if (!host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return false;
        String[] parts = cidr.split("/");
        if (parts.length != 2 || !parts[0].matches("\\d{1,3}(\\.\\d{1,3}){3}")) return false;
        try {
            int bits = Integer.parseInt(parts[1]);
            if (bits < 0 || bits > 32) return false;
            long mask = bits == 0 ? 0 : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
            return (ipv4(host) & mask) == (ipv4(parts[0]) & mask);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static long ipv4(String ip) {
        long v = 0;
        for (String p : ip.split("\\.")) v = (v << 8) | (Integer.parseInt(p) & 0xFF);
        return v;
    }

    static boolean isLoopback(String host) {
        String h = strip(host.toLowerCase(Locale.ROOT));
        if (h.equals("localhost") || h.startsWith("127.") || h.equals("::1")) return true;
        if (h.matches("[0-9a-f:]+") && h.contains(":")) {
            try {
                return InetAddress.getByName(h).isLoopbackAddress(); // literal: no DNS lookup
            } catch (UnknownHostException e) {
                return false;
            }
        }
        return false;
    }

    public NetworkSettings settings() {
        return settings;
    }
}
