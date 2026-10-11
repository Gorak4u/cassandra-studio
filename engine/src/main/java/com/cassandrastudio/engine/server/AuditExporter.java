package com.cassandrastudio.engine.server;

import com.cassandrastudio.engine.audit.AuditLog;
import com.cassandrastudio.engine.util.Json;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends every audit entry to a SIEM (SRV-6): syslog (RFC 5424) over UDP or TCP, and/or JSON lines to a
 * file or a TCP endpoint. Each destination has its own queue and thread, so a slow or unreachable SIEM
 * never holds up Studio: entries wait (up to {@value #QUEUE} per destination, oldest dropped and counted
 * beyond that) and are re-sent once the destination is back. Every exported entry carries its audit-chain
 * hash, so the SIEM copy can be compared with {@code GET /api/audit/verify}.
 */
public final class AuditExporter implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(AuditExporter.class);
    static final int QUEUE = 10_000;
    /** Largest UDP syslog message; longer ones are cut (TCP sends everything). */
    static final int MAX_UDP_BYTES = 8_192;

    /** One destination. */
    interface Sink extends AutoCloseable {
        String name();

        void send(AuditLog.Entry e, String hash) throws IOException;

        @Override
        void close();
    }

    private record Item(AuditLog.Entry entry, String hash) {}

    private final List<Worker> workers = new ArrayList<>();

    AuditExporter(List<Sink> sinks) {
        for (Sink s : sinks) workers.add(new Worker(s));
    }

    /** The destinations configured in {@code cfg}; empty when none is. */
    public static AuditExporter fromConfig(ServerConfig cfg) {
        List<Sink> sinks = new ArrayList<>();
        String host = hostName();
        if (cfg.get("audit.syslog.host") != null) {
            sinks.add(new SyslogSink(cfg.get("audit.syslog.host"), cfg.getInt("audit.syslog.port", 514),
                    cfg.get("audit.syslog.protocol", "udp").equals("tcp"), cfg.get("audit.syslog.framing", "octet-counting")
                    .equals("octet-counting"), cfg.getInt("audit.syslog.facility", 13), cfg.get("audit.syslog.appName",
                    "cassandra-studio"), host));
        }
        if (cfg.path("audit.json.file") != null) sinks.add(new JsonFileSink(cfg.path("audit.json.file")));
        if (cfg.get("audit.json.host") != null) {
            sinks.add(new JsonTcpSink(cfg.get("audit.json.host"), cfg.getInt("audit.json.port", 5170)));
        }
        return new AuditExporter(sinks);
    }

    public boolean isEmpty() {
        return workers.isEmpty();
    }

    /** Queues an entry for every destination (called by the audit log after each write). */
    public void accept(AuditLog.Entry e, String hash) {
        for (Worker w : workers) w.offer(new Item(e, hash));
    }

    /** Per destination: sent, waiting, dropped, last error. */
    public List<Map<String, Object>> status() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Worker w : workers) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("destination", w.sink.name());
            m.put("sent", w.sent.get());
            m.put("waiting", w.queue.size());
            m.put("dropped", w.dropped.get());
            m.put("lastError", w.lastError);
            out.add(m);
        }
        return out;
    }

    /** Waits up to {@code timeoutMs} for every queue to drain (shutdown, tests). */
    public boolean flush(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (workers.stream().allMatch(w -> w.queue.isEmpty() && !w.busy)) return true;
            Thread.sleep(20);
        }
        return false;
    }

    @Override
    public void close() {
        try {
            flush(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (Worker w : workers) w.stop();
    }

    private static final class Worker {
        final Sink sink;
        final LinkedBlockingDeque<Item> queue = new LinkedBlockingDeque<>();
        final AtomicLong sent = new AtomicLong();
        final AtomicLong dropped = new AtomicLong();
        final Thread thread;
        volatile String lastError;
        volatile boolean busy;
        volatile boolean stopped;

        Worker(Sink sink) {
            this.sink = sink;
            this.thread = new Thread(this::run, "audit-export " + sink.name());
            thread.setDaemon(true);
            thread.start();
        }

        void offer(Item i) {
            while (queue.size() >= QUEUE) {
                if (queue.pollFirst() != null) dropped.incrementAndGet();
            }
            queue.offerLast(i);
        }

        void run() {
            long backoff = 500;
            while (!stopped) {
                Item item;
                try {
                    item = queue.pollFirst(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    return;
                }
                if (item == null) continue;
                busy = true;
                try {
                    sink.send(item.entry(), item.hash());
                    sent.incrementAndGet();
                    lastError = null;
                    backoff = 500;
                } catch (IOException | RuntimeException e) {
                    queue.offerFirst(item); // keep it; re-sent when the destination is back
                    if (lastError == null) LOG.warn("Audit export to {} failed: {}", sink.name(), e.toString());
                    lastError = Instant.now() + " " + e;
                    sink.close();
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        return;
                    }
                    backoff = Math.min(backoff * 2, 30_000);
                } finally {
                    busy = false;
                }
            }
        }

        void stop() {
            stopped = true;
            thread.interrupt();
            sink.close();
        }
    }

    // ---- formats -----------------------------------------------------------

    /** The entry and its chain hash as one JSON object (the JSON-lines format and the syslog message). */
    static String json(AuditLog.Entry e, String hash) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("at", e.at());
        m.put("actor", e.actor());
        m.put("connectionId", e.connectionId());
        m.put("connectionName", e.connectionName());
        m.put("environment", e.environment());
        m.put("node", e.node());
        m.put("category", e.category());
        m.put("action", e.action());
        m.put("detail", e.detail());
        m.put("outcome", e.outcome());
        m.put("error", e.error());
        m.put("hash", hash);
        return Json.write(m);
    }

    /** An RFC 5424 syslog message: header, one structured-data element with the key fields, JSON message. */
    static String rfc5424(AuditLog.Entry e, String hash, int facility, String appName, String hostname, long pid) {
        int severity = "SUCCESS".equals(e.outcome()) ? 5 : 4; // notice; warning for FAILED and BLOCKED
        String ts;
        try {
            ts = Instant.parse(e.at()).truncatedTo(ChronoUnit.MILLIS).toString();
        } catch (RuntimeException ex) {
            ts = "-";
        }
        StringBuilder sd = new StringBuilder("[studio@32473");
        param(sd, "id", String.valueOf(e.id()));
        param(sd, "actor", e.actor());
        param(sd, "category", e.category());
        param(sd, "outcome", e.outcome());
        param(sd, "connection", e.connectionName());
        param(sd, "environment", e.environment());
        param(sd, "hash", hash);
        sd.append(']');
        return "<" + (facility * 8 + severity) + ">1 " + ts + " " + header(hostname, 255) + " " + header(appName, 48) + " " + pid
                + " audit " + sd + " " + json(e, hash);
    }

    private static void param(StringBuilder sd, String name, String value) {
        if (value == null) return;
        sd.append(' ').append(name).append("=\"")
                .append(value.replace("\\", "\\\\").replace("\"", "\\\"").replace("]", "\\]")).append('"');
    }

    /** A header field: printable US-ASCII without spaces, or "-". */
    private static String header(String s, int max) {
        if (s == null || s.isBlank()) return "-";
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c > 32 && c < 127) b.append(c);
        }
        String out = b.toString();
        return out.isEmpty() ? "-" : out.length() > max ? out.substring(0, max) : out;
    }

    static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "-";
        }
    }

    // ---- destinations ------------------------------------------------------

    static final class SyslogSink implements Sink {
        private final String host;
        private final int port;
        private final boolean tcp;
        private final boolean octetCounting;
        private final int facility;
        private final String appName;
        private final String hostname;
        private final long pid = ProcessHandle.current().pid();
        private Socket socket;
        private DatagramSocket udp;

        SyslogSink(String host, int port, boolean tcp, boolean octetCounting, int facility, String appName, String hostname) {
            this.host = host;
            this.port = port;
            this.tcp = tcp;
            this.octetCounting = octetCounting;
            this.facility = facility;
            this.appName = appName;
            this.hostname = hostname;
        }

        @Override
        public String name() {
            return "syslog " + (tcp ? "tcp" : "udp") + "://" + host + ":" + port;
        }

        @Override
        public synchronized void send(AuditLog.Entry e, String hash) throws IOException {
            byte[] msg = rfc5424(e, hash, facility, appName, hostname, pid).getBytes(StandardCharsets.UTF_8);
            if (tcp) {
                if (socket == null || socket.isClosed()) {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(host, port), 5_000);
                    socket.setSoTimeout(10_000);
                }
                OutputStream out = socket.getOutputStream();
                if (octetCounting) out.write((msg.length + " ").getBytes(StandardCharsets.US_ASCII));
                out.write(msg);
                if (!octetCounting) out.write('\n');
                out.flush();
            } else {
                if (udp == null) udp = new DatagramSocket();
                int len = Math.min(msg.length, MAX_UDP_BYTES);
                udp.send(new DatagramPacket(msg, len, new InetSocketAddress(host, port)));
            }
        }

        @Override
        public synchronized void close() {
            try {
                if (socket != null) socket.close();
            } catch (IOException ignored) {
                // reconnects on next send
            }
            socket = null;
            if (udp != null) udp.close();
            udp = null;
        }
    }

    static final class JsonFileSink implements Sink {
        private final Path file;

        JsonFileSink(Path file) {
            this.file = file;
        }

        @Override
        public String name() {
            return "json file " + file;
        }

        @Override
        public void send(AuditLog.Entry e, String hash) throws IOException {
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            Files.writeString(file, json(e, hash) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        }

        @Override
        public void close() {}
    }

    static final class JsonTcpSink implements Sink {
        private final String host;
        private final int port;
        private Socket socket;

        JsonTcpSink(String host, int port) {
            this.host = host;
            this.port = port;
        }

        @Override
        public String name() {
            return "json tcp://" + host + ":" + port;
        }

        @Override
        public synchronized void send(AuditLog.Entry e, String hash) throws IOException {
            if (socket == null || socket.isClosed()) {
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), 5_000);
                socket.setSoTimeout(10_000);
            }
            OutputStream out = socket.getOutputStream();
            out.write((json(e, hash) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        @Override
        public synchronized void close() {
            try {
                if (socket != null) socket.close();
            } catch (IOException ignored) {
                // reconnects on next send
            }
            socket = null;
        }
    }
}
