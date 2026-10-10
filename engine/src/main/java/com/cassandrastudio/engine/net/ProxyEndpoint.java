package com.cassandrastudio.engine.net;

import com.cassandrastudio.engine.model.ConnectionConfig.ProxyType;
import java.net.URI;
import java.util.Locale;

/** One proxy server: HTTP (CONNECT) or SOCKS5, with optional credentials. {@link #toString} never shows the password. */
public record ProxyEndpoint(ProxyType type, String host, int port, String username, String password) {

    public ProxyEndpoint {
        if (type == null) throw new IllegalArgumentException("proxy type is required");
        if (host == null || host.isBlank()) throw new IllegalArgumentException("proxy host is required");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("proxy port must be 1-65535");
        username = username == null || username.isEmpty() ? null : username;
    }

    public boolean hasCredentials() {
        return username != null;
    }

    /**
     * Parses a proxy as written in HTTPS_PROXY / HTTP_PROXY: {@code [scheme://][user[:password]@]host[:port][/]}.
     * Scheme http or https (both mean an HTTP proxy) or socks5/socks5h; default port 80 / 1080.
     */
    public static ProxyEndpoint parse(String value) {
        String v = value.trim();
        if (!v.contains("://")) v = "http://" + v;
        URI u;
        try {
            u = URI.create(v);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("not a proxy URL: " + redact(value));
        }
        String scheme = u.getScheme().toLowerCase(Locale.ROOT);
        ProxyType type = switch (scheme) {
            case "http", "https" -> ProxyType.HTTP;
            case "socks5", "socks5h", "socks" -> ProxyType.SOCKS5;
            default -> throw new IllegalArgumentException("unsupported proxy scheme " + scheme);
        };
        if (u.getHost() == null) throw new IllegalArgumentException("not a proxy URL: " + redact(value));
        int port = u.getPort() > 0 ? u.getPort() : type == ProxyType.SOCKS5 ? 1080 : "https".equals(scheme) ? 443 : 80;
        String user = null;
        String pw = null;
        if (u.getRawUserInfo() != null) {
            String[] ui = u.getRawUserInfo().split(":", 2);
            user = java.net.URLDecoder.decode(ui[0], java.nio.charset.StandardCharsets.UTF_8);
            pw = ui.length > 1 ? java.net.URLDecoder.decode(ui[1], java.nio.charset.StandardCharsets.UTF_8) : null;
        }
        return new ProxyEndpoint(type, u.getHost(), port, user, pw);
    }

    /** The URL with any password replaced, for messages. */
    static String redact(String url) {
        return url == null ? null : url.replaceAll("//([^:/@]+):[^@/]*@", "//$1:***@");
    }

    @Override
    public String toString() {
        return (type == ProxyType.SOCKS5 ? "socks5://" : "http://") + (username != null ? username + "@" : "") + host + ":" + port;
    }
}
