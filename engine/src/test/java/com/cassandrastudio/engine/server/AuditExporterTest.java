package com.cassandrastudio.engine.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.cassandrastudio.engine.audit.AuditLog;
import com.cassandrastudio.engine.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditExporterTest {
    @TempDir
    Path dir;

    private static AuditLog.Entry entry(long id, String outcome, String detail) {
        return new AuditLog.Entry(id, "2026-10-10T12:00:00.123456789Z", "alice", "c1", "acme \"core\"]", "PROD", "10.0.0.1",
                "cql", "INSERT", detail, outcome, null);
    }

    @Test
    void rfc5424Format() {
        String m = AuditExporter.rfc5424(entry(7, "SUCCESS", "x"), "ab".repeat(32), 13, "cassandra studio", "host-1", 42);
        // <PRI>VERSION TIMESTAMP HOSTNAME APP-NAME PROCID MSGID [SD] MSG; PRI = 13*8 + 5 (notice)
        assertThat(m).startsWith("<109>1 2026-10-10T12:00:00.123Z host-1 cassandrastudio 42 audit [studio@32473 id=\"7\" actor=\"alice\"");
        assertThat(m).contains("connection=\"acme \\\"core\\\"\\]\"").contains("environment=\"PROD\"").contains("hash=\"abab");
        String json = m.substring(m.indexOf("] {") + 2);
        JsonNode n = Json.read(json, JsonNode.class);
        assertThat(n.get("id").asLong()).isEqualTo(7);
        assertThat(n.get("hash").asText()).hasSize(64);
        assertThat(AuditExporter.rfc5424(entry(8, "BLOCKED", "x"), "h", 16, "a", "h", 1)).startsWith("<132>1 "); // 16*8 + 4
    }

    @Test
    void syslogOverUdpAndTcpAndJsonLines() throws Exception {
        try (DatagramSocket udp = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             ServerSocket tcp = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
             ServerSocket json = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            BlockingQueue<String> tcpFrames = new LinkedBlockingQueue<>();
            BlockingQueue<String> jsonLines = new LinkedBlockingQueue<>();
            Thread.ofVirtual().start(() -> readOctetCounted(tcp, tcpFrames));
            Thread.ofVirtual().start(() -> readLines(json, jsonLines));
            Path file = dir.resolve("audit/studio-audit.jsonl");
            ServerConfig cfg = ServerConfig.of(Map.of("audit.syslog.host", "127.0.0.1", "audit.syslog.port",
                    String.valueOf(udp.getLocalPort()), "audit.json.file", file.toString(), "audit.json.host", "127.0.0.1",
                    "audit.json.port", String.valueOf(json.getLocalPort())));
            ServerConfig tcpCfg = ServerConfig.of(Map.of("audit.syslog.host", "127.0.0.1", "audit.syslog.port",
                    String.valueOf(tcp.getLocalPort()), "audit.syslog.protocol", "tcp"));
            try (AuditExporter x = AuditExporter.fromConfig(cfg); AuditExporter t = AuditExporter.fromConfig(tcpCfg)) {
                assertThat(x.status()).hasSize(3);
                for (int i = 1; i <= 3; i++) {
                    x.accept(entry(i, "SUCCESS", "line\nbreak " + i), "h" + i);
                    t.accept(entry(i, "FAILED", "d" + i), "h" + i);
                }
                byte[] buf = new byte[65536];
                udp.setSoTimeout(5000);
                for (int i = 1; i <= 3; i++) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    udp.receive(p);
                    String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    assertThat(msg).startsWith("<109>1 ").contains("id=\"" + i + "\"").contains("\"hash\":\"h" + i + "\"");
                }
                for (int i = 1; i <= 3; i++) {
                    String frame = tcpFrames.poll(5, TimeUnit.SECONDS);
                    assertThat(frame).startsWith("<108>1 ").contains("id=\"" + i + "\"");
                    String line = jsonLines.poll(5, TimeUnit.SECONDS);
                    assertThat(Json.read(line, JsonNode.class).get("detail").asText()).isEqualTo("line\nbreak " + i);
                }
                assertThat(x.flush(5000)).isTrue();
                List<String> lines = Files.readAllLines(file);
                assertThat(lines).hasSize(3);
                assertThat(Json.read(lines.get(2), JsonNode.class).get("hash").asText()).isEqualTo("h3");
                assertThat(x.status()).allSatisfy(s -> assertThat(s.get("sent")).isEqualTo(3L));
            }
        }
    }

    @Test
    void entriesWaitWhileTheSiemIsDown() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        ServerConfig cfg = ServerConfig.of(Map.of("audit.json.host", "127.0.0.1", "audit.json.port", String.valueOf(port)));
        try (AuditExporter x = AuditExporter.fromConfig(cfg)) {
            for (int i = 1; i <= 3; i++) x.accept(entry(i, "SUCCESS", "d"), "h" + i);
            Thread.sleep(800);
            assertThat(x.status().get(0).get("lastError")).isNotNull();
            assertThat(x.status().get(0).get("sent")).isEqualTo(0L);
            BlockingQueue<String> lines = new LinkedBlockingQueue<>();
            try (ServerSocket back = new ServerSocket(port, 5, InetAddress.getLoopbackAddress())) {
                Thread.ofVirtual().start(() -> readLines(back, lines));
                for (int i = 1; i <= 3; i++) {
                    String l = lines.poll(15, TimeUnit.SECONDS);
                    assertThat(l).as("entry %d after the SIEM came back", i).isNotNull();
                    assertThat(Json.read(l, JsonNode.class).get("id").asInt()).isEqualTo(i);
                }
            }
        }
    }

    static void readOctetCounted(ServerSocket server, BlockingQueue<String> out) {
        try (Socket s = server.accept(); DataInputStream in = new DataInputStream(s.getInputStream())) {
            while (true) {
                StringBuilder len = new StringBuilder();
                int c;
                while ((c = in.read()) != ' ') {
                    if (c < 0) return;
                    len.append((char) c);
                }
                byte[] msg = new byte[Integer.parseInt(len.toString())];
                in.readFully(msg);
                out.add(new String(msg, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            // listener closed
        }
    }

    static void readLines(ServerSocket server, BlockingQueue<String> out) {
        try (Socket s = server.accept();
             BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.add(line);
        } catch (Exception e) {
            // listener closed
        }
    }
}
