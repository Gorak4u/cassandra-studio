package com.cassandrastudio.engine.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;

/**
 * In-process proxy for tests: HTTP CONNECT and SOCKS5 on the same port (told apart by the first byte),
 * optional credentials, and a host map so names like "updates.example.test" reach 127.0.0.1.
 * Records every "host:port" it tunnelled to.
 */
public final class TestProxy implements AutoCloseable {
    private final ServerSocket server;
    private final String user;
    private final String password;
    private final UnaryOperator<String> hosts;
    public final List<String> tunnels = new CopyOnWriteArrayList<>();
    public final List<String> rejected = new CopyOnWriteArrayList<>();

    public TestProxy(String user, String password, UnaryOperator<String> hosts) throws IOException {
        this.user = user;
        this.password = password;
        this.hosts = hosts;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(this::acceptLoop);
    }

    public TestProxy() throws IOException {
        this(null, null, h -> h);
    }

    public int port() {
        return server.getLocalPort();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                Thread.ofVirtual().start(() -> handle(s));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket s) {
        try {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            int first = in.read();
            if (first == 5) socks(s, in, out);
            else if (first > 0) http(s, (char) first + head(in), out);
            else s.close();
        } catch (IOException e) {
            close(s);
        }
    }

    private void http(Socket s, String head, OutputStream out) throws IOException {
        String[] request = head.lines().findFirst().orElse("").split(" ");
        if (request.length < 3 || !request[0].equals("CONNECT")) {
            out.write("HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            s.close();
            return;
        }
        if (user != null) {
            String expected = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
            boolean ok = head.lines().anyMatch(l -> l.regionMatches(true, 0, "Proxy-Authorization:", 0, 20)
                    && l.substring(20).trim().equals(expected));
            if (!ok) {
                rejected.add(request[1]);
                out.write(("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"test\"\r\n"
                        + "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                // keep the connection: clients may retry with credentials on it
                String next = head(s.getInputStream());
                if (next.isEmpty()) {
                    s.close();
                    return;
                }
                http(s, next, out);
                return;
            }
        }
        int colon = request[1].lastIndexOf(':');
        String host = request[1].substring(0, colon).replace("[", "").replace("]", "");
        int port = Integer.parseInt(request[1].substring(colon + 1));
        Socket up;
        try {
            up = new Socket(hosts.apply(host), port);
        } catch (IOException e) {
            out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            s.close();
            return;
        }
        tunnels.add(host + ":" + port);
        out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        relay(s, up);
    }

    private void socks(Socket s, InputStream in, OutputStream out) throws IOException {
        int n = in.read();
        byte[] methods = in.readNBytes(n);
        boolean wantsAuth = user != null;
        int chosen = wantsAuth ? (contains(methods, 2) ? 2 : 0xFF) : (contains(methods, 0) ? 0 : 0xFF);
        out.write(new byte[] {5, (byte) chosen});
        out.flush();
        if (chosen == 0xFF) {
            s.close();
            return;
        }
        if (chosen == 2) {
            in.read(); // version 1
            String u = new String(in.readNBytes(in.read()), StandardCharsets.UTF_8);
            String p = new String(in.readNBytes(in.read()), StandardCharsets.UTF_8);
            boolean ok = u.equals(user) && p.equals(password);
            out.write(new byte[] {1, (byte) (ok ? 0 : 1)});
            out.flush();
            if (!ok) {
                rejected.add(u);
                s.close();
                return;
            }
        }
        byte[] req = in.readNBytes(4);
        String host;
        if (req[3] == 3) host = new String(in.readNBytes(in.read()), StandardCharsets.US_ASCII);
        else if (req[3] == 1) host = InetAddress.getByAddress(in.readNBytes(4)).getHostAddress();
        else {
            s.close();
            return;
        }
        byte[] p = in.readNBytes(2);
        int port = ((p[0] & 0xFF) << 8) | (p[1] & 0xFF);
        Socket up;
        try {
            up = new Socket(hosts.apply(host), port);
        } catch (IOException e) {
            out.write(new byte[] {5, 5, 0, 1, 0, 0, 0, 0, 0, 0});
            s.close();
            return;
        }
        tunnels.add(host + ":" + port);
        out.write(new byte[] {5, 0, 0, 1, 127, 0, 0, 1, 0, 0});
        out.flush();
        relay(s, up);
    }

    private static boolean contains(byte[] a, int v) {
        for (byte b : a) if ((b & 0xFF) == v) return true;
        return false;
    }

    private static String head(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        while (true) {
            int c = in.read();
            if (c < 0) return b.toString(StandardCharsets.ISO_8859_1);
            b.write(c);
            String s = b.toString(StandardCharsets.ISO_8859_1);
            if (s.endsWith("\r\n\r\n")) return s;
        }
    }

    private static void relay(Socket a, Socket b) {
        Thread.ofVirtual().start(() -> pump(a, b));
        pump(b, a);
    }

    private static void pump(Socket from, Socket to) {
        try {
            from.getInputStream().transferTo(to.getOutputStream());
        } catch (IOException ignored) {
            // closed
        } finally {
            close(from);
            close(to);
        }
    }

    private static void close(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // closing
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
