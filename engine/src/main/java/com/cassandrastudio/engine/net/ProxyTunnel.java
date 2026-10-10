package com.cassandrastudio.engine.net;

import com.cassandrastudio.engine.model.ConnectionConfig.ProxyType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Opens a TCP connection to {@code host:port} through a proxy: HTTP CONNECT (RFC 9110 §9.3.6, optional
 * Basic auth) or SOCKS5 (RFC 1928, optional user/password RFC 1929). The target name is sent to the proxy
 * unresolved, so names that only resolve on the far side work. The returned socket carries raw bytes.
 */
public final class ProxyTunnel {
    private ProxyTunnel() {}

    /** Thrown with a message fit for the UI (proxy, target, reason; never the password). */
    public static final class ProxyException extends IOException {
        public ProxyException(String message) {
            super(message);
        }

        public ProxyException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static Socket open(ProxyEndpoint proxy, String host, int port, Duration timeout) throws IOException {
        Socket s = new Socket(java.net.Proxy.NO_PROXY);
        int ms = (int) Math.max(1, Math.min(Integer.MAX_VALUE, timeout.toMillis()));
        try {
            try {
                s.connect(new InetSocketAddress(proxy.host(), proxy.port()), ms);
            } catch (SocketTimeoutException e) {
                throw new ProxyException("proxy " + proxy.host() + ":" + proxy.port() + " did not answer within " + ms / 1000 + " s", e);
            } catch (IOException e) {
                throw new ProxyException("cannot reach proxy " + proxy.host() + ":" + proxy.port() + ": " + e.getMessage(), e);
            }
            s.setSoTimeout(ms); // for the handshake only
            if (proxy.type() == ProxyType.HTTP) connect(s, proxy, host, port);
            else socks5(s, proxy, host, port);
            s.setSoTimeout(0);
            return s;
        } catch (SocketTimeoutException e) {
            s.close();
            throw new ProxyException("proxy " + proxy.host() + ":" + proxy.port() + " timed out opening a tunnel to "
                    + host + ":" + port, e);
        } catch (IOException | RuntimeException e) {
            s.close();
            throw e;
        }
    }

    // ---- HTTP CONNECT -------------------------------------------------------------------------

    private static void connect(Socket s, ProxyEndpoint proxy, String host, int port) throws IOException {
        String authority = (host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host) + ":" + port;
        StringBuilder req = new StringBuilder()
                .append("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
                .append("Host: ").append(authority).append("\r\n")
                .append("User-Agent: CassandraStudio\r\n");
        if (proxy.hasCredentials()) {
            String token = proxy.username() + ":" + (proxy.password() == null ? "" : proxy.password());
            req.append("Proxy-Authorization: Basic ")
                    .append(Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8))).append("\r\n");
        }
        req.append("\r\n");
        OutputStream out = s.getOutputStream();
        out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        String head = readHead(s.getInputStream());
        String status = head.lines().findFirst().orElse("");
        String[] parts = status.split(" ", 3);
        int code;
        try {
            code = parts.length >= 2 && parts[0].startsWith("HTTP/") ? Integer.parseInt(parts[1]) : -1;
        } catch (NumberFormatException e) {
            code = -1;
        }
        if (code >= 200 && code < 300) return;
        String where = "proxy " + proxy.host() + ":" + proxy.port();
        if (code == 407) {
            throw new ProxyException(where + " requires authentication" + (proxy.hasCredentials()
                    ? ": user " + proxy.username() + " was rejected" : ": set the proxy user and password"));
        }
        if (code < 0) throw new ProxyException(where + " is not an HTTP proxy (answered: " + truncate(status) + ")");
        throw new ProxyException(where + " refused the tunnel to " + authority + ": " + truncate(status));
    }

    /** The response head, byte by byte, so no tunnelled byte is consumed. */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int matched = 0;
        byte[] end = {'\r', '\n', '\r', '\n'};
        while (b.size() < 16 * 1024) {
            int c = in.read();
            if (c < 0) throw new ProxyException("proxy closed the connection during CONNECT");
            b.write(c);
            matched = c == end[matched] ? matched + 1 : c == '\r' ? 1 : 0;
            if (matched == 4) return b.toString(StandardCharsets.ISO_8859_1);
        }
        throw new ProxyException("proxy response header too long");
    }

    // ---- SOCKS5 ---------------------------------------------------------------------------------

    private static void socks5(Socket s, ProxyEndpoint proxy, String host, int port) throws IOException {
        OutputStream out = s.getOutputStream();
        InputStream in = s.getInputStream();
        String where = "SOCKS5 proxy " + proxy.host() + ":" + proxy.port();
        out.write(proxy.hasCredentials() ? new byte[] {5, 2, 0, 2} : new byte[] {5, 1, 0});
        out.flush();
        byte[] choice = readN(in, 2);
        if (choice[0] != 5) throw new ProxyException(where + " is not a SOCKS5 proxy");
        int method = choice[1] & 0xFF;
        if (method == 2) {
            if (!proxy.hasCredentials()) throw new ProxyException(where + " requires a user and password");
            byte[] u = proxy.username().getBytes(StandardCharsets.UTF_8);
            byte[] p = (proxy.password() == null ? "" : proxy.password()).getBytes(StandardCharsets.UTF_8);
            if (u.length > 255 || p.length > 255) throw new ProxyException("SOCKS5 user or password longer than 255 bytes");
            ByteArrayOutputStream a = new ByteArrayOutputStream();
            a.write(1);
            a.write(u.length);
            a.write(u);
            a.write(p.length);
            a.write(p);
            out.write(a.toByteArray());
            out.flush();
            byte[] r = readN(in, 2);
            if (r[1] != 0) throw new ProxyException(where + " rejected user " + proxy.username());
        } else if (method == 0xFF) {
            throw new ProxyException(where + " accepts none of the offered authentication methods"
                    + (proxy.hasCredentials() ? "" : " (set a user and password?)"));
        } else if (method != 0) {
            throw new ProxyException(where + " chose an unsupported authentication method " + method);
        }
        byte[] name = host.getBytes(StandardCharsets.US_ASCII);
        if (name.length > 255) throw new ProxyException("host name too long for SOCKS5: " + host);
        ByteArrayOutputStream req = new ByteArrayOutputStream();
        req.write(new byte[] {5, 1, 0, 3, (byte) name.length});
        req.write(name);
        req.write((port >> 8) & 0xFF);
        req.write(port & 0xFF);
        out.write(req.toByteArray());
        out.flush();
        byte[] head = readN(in, 4);
        if (head[1] != 0) throw new ProxyException(where + " could not connect to " + host + ":" + port + ": " + socksError(head[1]));
        int skip = switch (head[3]) {
            case 1 -> 4;
            case 4 -> 16;
            case 3 -> readN(in, 1)[0] & 0xFF;
            default -> throw new ProxyException(where + " sent an invalid reply");
        };
        readN(in, skip + 2); // bound address and port
    }

    static String socksError(byte code) {
        return switch (code) {
            case 1 -> "general failure";
            case 2 -> "not allowed by the proxy's rules";
            case 3 -> "network unreachable";
            case 4 -> "host unreachable";
            case 5 -> "connection refused";
            case 6 -> "TTL expired";
            case 7 -> "command not supported";
            case 8 -> "address type not supported";
            default -> "error " + (code & 0xFF);
        };
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = in.readNBytes(n);
        if (b.length < n) throw new ProxyException("proxy closed the connection during the handshake");
        return b;
    }

    private static String truncate(String s) {
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}
