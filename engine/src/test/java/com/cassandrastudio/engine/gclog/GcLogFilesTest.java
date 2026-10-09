package com.cassandrastudio.engine.gclog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cassandrastudio.engine.jobs.JobContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class GcLogFilesTest {

    @Test
    void logPathFromJvmOptions() {
        assertThat(GcLogFiles.configuredPath(List.of("-Xms512M", "-Xloggc:/opt/cassandra/logs/gc.log")))
                .isEqualTo("/opt/cassandra/logs/gc.log");
        assertThat(GcLogFiles.configuredPath(List.of("-Xlog:gc=info,heap*=trace,age*=debug,safepoint=info,promotion*=trace"
                + ":file=/opt/cassandra/logs/gc.log:time,uptime,pid,tid,level:filecount=10,filesize=10485760")))
                .isEqualTo("/opt/cassandra/logs/gc.log");
        assertThat(GcLogFiles.configuredPath(List.of("-Xlog:gc*:/var/log/gc-%p.log"))).isEqualTo("/var/log/gc-%p.log");
        assertThat(GcLogFiles.configuredPath(List.of("-Xlog:gc*:stdout"))).isNull();
        assertThat(GcLogFiles.configuredPath(List.of("-Xlog:gc:file=\"/x/y gc.log\"::filecount=2"))).isEqualTo("/x/y gc.log");
        assertThat(GcLogFiles.configuredPath(List.of("-Xmx1G"))).isNull();
    }

    @Test
    void patternsAndShellWords() {
        assertThat(GcLogFiles.patterns("/var/log/gc-%p-%t.log"))
                .containsExactly("/var/log/gc-*-*.log*", "/var/log/cassandra/gc*", "/opt/cassandra/logs/gc*");
        assertThat(GcLogFiles.patterns(null)).containsExactly("/var/log/cassandra/gc*", "/opt/cassandra/logs/gc*");
        assertThat(GcLogFiles.glob("/a b/gc-*.log*")).isEqualTo("'/a b/gc-'*'.log'*");
        assertThat(GcLogFiles.glob("/x/it's")).isEqualTo("'/x/it'\\''s'");
    }

    @Test
    void listingDedupesAndMarksCurrent() {
        String out = """
                54401 1791534000 /var/log/cassandra/gc.log.0.current
                10485820 1791533000 /var/log/cassandra/gc.log.1
                54401 1791534000 /opt/cassandra/logs/gc.log.0.current
                garbage line
                #gzip
                """;
        List<GcLogFiles.RemoteFile> files = GcLogFiles.parseListing(out);
        assertThat(files).extracting(GcLogFiles.RemoteFile::path)
                .containsExactly("/var/log/cassandra/gc.log.0.current", "/var/log/cassandra/gc.log.1");
        assertThat(files.get(0).current()).isTrue();
        assertThat(files.get(1).current()).isFalse();
        assertThat(files.get(1).modifiedMs()).isEqualTo(1791533000000L);
    }

    @Test
    void pathChecks() {
        assertThat(GcLogFiles.checkPath("/var/log/cassandra/gc.log.1")).isNull();
        assertThat(GcLogFiles.checkPath("/etc/passwd")).contains("not a GC log");
        assertThat(GcLogFiles.checkPath("relative/gc.log")).contains("absolute");
        assertThat(GcLogFiles.checkPath("/var/log/../../etc/gc")).contains("absolute");
        assertThat(GcLogFiles.checkPath("/var/log/gc.log; rm -rf /")).contains("absolute");
    }

    /** A node with two rotated files, answering like the real shell commands. */
    private static final class FakeNode implements GcLogFiles.Shell {
        final List<String> commands = new ArrayList<>();
        final byte[] older, current;

        FakeNode(byte[] older, byte[] current) {
            this.older = older;
            this.current = current;
        }

        @Override
        public String exec(String command, java.time.Duration timeout, int maxBytes) {
            commands.add(command);
            if (command.startsWith("for f in")) {
                return older.length + " 1000 /var/log/cassandra/gc.log.1\n" + current.length + " 2000 /var/log/cassandra/gc.log\n#gzip\n";
            }
            byte[] src = command.contains("gc.log.1") ? older : current;
            if (command.contains("tail -c ")) {
                int n = Integer.parseInt(command.replaceAll(".*tail -c (\\d+) .*", "$1"));
                byte[] t = new byte[n];
                System.arraycopy(src, src.length - n, t, 0, n);
                src = t;
            }
            return Base64.getMimeEncoder().encodeToString(gzip(src));
        }
    }

    private static final JobContext CTX = new JobContext() {
        @Override
        public void progress(Double fraction, String message) {}

        @Override
        public void log(String line) {}

        @Override
        public boolean cancelled() {
            return false;
        }

        @Override
        public void onCancel(Runnable action) {}
    };

    @Test
    void fetchReadsOldestFirstAndCapsFromTheNewest() throws IOException {
        byte[] older = fixture("java8-cms-cassandra311.log");
        byte[] current = fixture("java8-cms-failures.log");
        FakeNode node = new FakeNode(older, current);
        GcLogParser p = new GcLogParser();
        GcLogFiles.Fetched got = GcLogFiles.fetch(node, "10.0.0.1",
                List.of("/var/log/cassandra/gc.log", "/var/log/cassandra/gc.log.1"), 1L << 30, p, CTX);
        assertThat(got.files()).extracting(GcReport.SourceFile::path)
                .containsExactly("/var/log/cassandra/gc.log.1", "/var/log/cassandra/gc.log");
        assertThat(node.commands.get(1)).contains("gzip -c | base64").contains("'/var/log/cassandra/gc.log.1'");
        GcLog log = p.finish();
        assertThat(log.concurrentModeFailures).isEqualTo(1);
        assertThat(log.events.size()).isGreaterThan(20);

        // a cap smaller than both files: only the end of the newest file
        FakeNode capped = new FakeNode(older, current);
        GcLogParser p2 = new GcLogParser();
        GcLogFiles.Fetched part = GcLogFiles.fetch(capped, "10.0.0.1",
                List.of("/var/log/cassandra/gc.log", "/var/log/cassandra/gc.log.1"), 3000, p2, CTX);
        assertThat(part.files()).hasSize(1);
        assertThat(part.files().get(0).truncated()).isTrue();
        assertThat(capped.commands.get(1)).contains("tail -c 3000");
        assertThat(p2.finish().events).isNotEmpty();
    }

    @Test
    void cutOffTransferIsRetriedOnce() throws IOException {
        byte[] log = fixture("java8-cms-failures.log");
        FakeNode node = new FakeNode(log, log);
        int[] reads = {0};
        GcLogFiles.Shell flaky = (cmd, timeout, max) -> {
            String out = node.exec(cmd, timeout, max);
            if (cmd.startsWith("for f in") || reads[0]++ > 0) return out;
            return Base64.getMimeEncoder().encodeToString(java.util.Arrays.copyOf(Base64.getMimeDecoder().decode(out), 40));
        };
        GcLogParser p = new GcLogParser();
        GcLogFiles.fetch(flaky, "n", List.of("/var/log/cassandra/gc.log"), 1L << 30, p, CTX);
        assertThat(reads[0]).isEqualTo(3); // the fake node lists two files; the first read is cut off and retried
        assertThat(p.finish().concurrentModeFailures).isEqualTo(2); // each file parsed once

        GcLogFiles.Shell broken = (cmd, timeout, max) -> cmd.startsWith("for f in") ? node.exec(cmd, timeout, max)
                : Base64.getMimeEncoder().encodeToString(java.util.Arrays.copyOf(gzip(log), 40));
        assertThatThrownBy(() -> GcLogFiles.fetch(broken, "n", List.of("/var/log/cassandra/gc.log"), 1L << 30, new GcLogParser(), CTX))
                .hasMessageContaining("incomplete");
    }

    @Test
    void uploadPlainGzipAndZip() throws IOException {
        byte[] log = fixture("java17-g1-cassandra50.log");
        GcLogParser plain = new GcLogParser();
        GcLogUpload.read(new ByteArrayInputStream(log), "gc.log", 1L << 30, plain);
        int events = plain.finish().events.size();
        assertThat(events).isPositive();

        GcLogParser gz = new GcLogParser();
        GcLogUpload.read(new ByteArrayInputStream(gzip(log)), "gc.log.gz", 1L << 30, gz);
        assertThat(gz.finish().events).hasSize(events);

        ByteArrayOutputStream zbytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(zbytes)) {
            z.putNextEntry(new ZipEntry("logs/"));
            z.putNextEntry(new ZipEntry("logs/gc.log.1"));
            z.write(fixture("java8-cms-cassandra311.log"));
            z.putNextEntry(new ZipEntry("logs/gc.log.0.gz"));
            z.write(gzip(fixture("java8-cms-failures.log")));
            z.putNextEntry(new ZipEntry("__MACOSX/._gc.log"));
            z.write("junk".getBytes(StandardCharsets.UTF_8));
        }
        GcLogParser zp = new GcLogParser();
        List<GcReport.SourceFile> files = GcLogUpload.read(new ByteArrayInputStream(zbytes.toByteArray()), "logs.zip",
                1L << 30, zp);
        assertThat(files).extracting(GcReport.SourceFile::path).containsExactly("logs/gc.log.1", "logs/gc.log.0.gz");
        GcLog merged = zp.finish();
        assertThat(merged.collector).isEqualTo("CMS");
        assertThat(merged.concurrentModeFailures).isEqualTo(1);

        assertThatThrownBy(() -> GcLogUpload.read(new ByteArrayInputStream(log), "gc.log", 1000, new GcLogParser()))
                .isInstanceOf(GcLogUpload.TooLargeException.class);
    }

    static byte[] fixture(String name) throws IOException {
        try (InputStream in = GcLogFilesTest.class.getResourceAsStream("/gclog/" + name)) {
            return in.readAllBytes();
        }
    }

    static byte[] gzip(byte[] b) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream z = new GZIPOutputStream(out)) {
                z.write(b);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
