package com.cassandrastudio.engine.gclog;

import com.cassandrastudio.engine.jobs.JobContext;
import com.cassandrastudio.engine.ssh.NodeShell;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * GC log files on a node, over SSH (GCL-1): where they are (from the JVM options, else the usual
 * Cassandra paths), current and rotated files with sizes, and fetching them with a size cap.
 * Transfer is {@code gzip | base64} when the node has both (GC logs compress 10-20x), else plain.
 * Every command only reads; paths are single-quoted.
 */
public final class GcLogFiles {
    /** Where Cassandra packages and images write gc.log when the options do not say otherwise. */
    public static final List<String> DEFAULT_DIRS = List.of("/var/log/cassandra", "/opt/cassandra/logs");
    public static final Duration LIST_TIMEOUT = Duration.ofSeconds(20);
    private static final Pattern XLOGGC = Pattern.compile("^-Xloggc:(.+)$");
    private static final Pattern SAFE_PATH = Pattern.compile("^/[A-Za-z0-9._/@+%=-]+$");
    private static final String GZIP_MARK = "#gzip";

    private GcLogFiles() {}

    /** Runs a read-only command on the node; returns stdout, throws on failure (NodeShell in production). */
    @FunctionalInterface
    public interface Shell {
        String exec(String command, Duration timeout, int maxBytes);
    }

    public record RemoteFile(String path, long sizeBytes, long modifiedMs, boolean current) {}

    /**
     * @param gcOptions      the JVM's GC logging options (empty when JMX could not be read)
     * @param configuredPath the log file the options name, or null
     * @param searched       the glob patterns listed on the node
     * @param compressed     whether the node has gzip and base64 (compressed transfer)
     * @param note           why the options could not be read, or another hint for the UI, or null
     */
    public record Discovery(String node, String javaVersion, List<String> gcOptions, String configuredPath,
                            List<String> searched, List<RemoteFile> files, boolean compressed, String note) {}

    /** The log file named by -Xloggc:file (Java 8) or -Xlog:...:file=... (Java 9+), or null. */
    static String configuredPath(List<String> jvmArgs) {
        String found = null;
        for (String a : jvmArgs) {
            Matcher m = XLOGGC.matcher(a);
            if (m.matches()) found = m.group(1).trim();
            if (a.startsWith("-Xlog:") && a.contains("gc")) {
                String p = xlogFile(a.substring("-Xlog:".length()));
                if (p != null) found = p;
            }
        }
        return found;
    }

    /** The output of one -Xlog option: "gc*:file=/x/gc.log:time,uptime:filecount=10" or "gc:/x/gc.log". */
    static String xlogFile(String spec) {
        List<String> parts = splitXlog(spec);
        if (parts.size() < 2) return null;
        String out = parts.get(1).trim();
        if (out.startsWith("file=")) out = out.substring(5);
        if (out.length() >= 2 && out.startsWith("\"") && out.endsWith("\"")) out = out.substring(1, out.length() - 1);
        if (out.isEmpty() || out.equals("stdout") || out.equals("stderr")) return null;
        return out;
    }

    /** Splits on ':' outside double quotes. */
    private static List<String> splitXlog(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        boolean q = false;
        for (char c : s.toCharArray()) {
            if (c == '"') q = !q;
            if (c == ':' && !q) {
                out.add(b.toString());
                b.setLength(0);
            } else {
                b.append(c);
            }
        }
        out.add(b.toString());
        return out;
    }

    /** The GC logging options among the JVM arguments, for display. */
    static List<String> gcOptions(List<String> jvmArgs) {
        return jvmArgs.stream().filter(a -> a.startsWith("-Xloggc") || a.startsWith("-Xlog:")
                || a.startsWith("-XX:+PrintGC") || a.contains("GCLogFile") || a.startsWith("-XX:+Use") && a.endsWith("GC")
                || a.contains("PrintTenuring") || a.contains("PrintHeapAtGC")).toList();
    }

    /** Glob patterns for the files: the configured log and its rotations first, then the default directories. */
    static List<String> patterns(String configured) {
        Set<String> out = new LinkedHashSet<>();
        if (configured != null && configured.startsWith("/")) {
            // %p (pid) and %t (start time) in the file name become wildcards
            out.add(configured.replaceAll("%[pt]", "*") + "*");
        }
        for (String d : DEFAULT_DIRS) out.add(d + "/gc*");
        return List.copyOf(out);
    }

    /** A shell word for a glob pattern: literal parts quoted, '*' left to the shell. */
    static String glob(String pattern) {
        StringBuilder b = new StringBuilder();
        String[] parts = pattern.split("\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].isEmpty()) b.append(NodeShell.quote(parts[i]));
            if (i < parts.length - 1) b.append('*');
        }
        return b.toString();
    }

    static String listCommand(List<String> words) {
        return "for f in " + String.join(" ", words) + "; do [ -f \"$f\" ] && "
                + "{ stat -L -c '%s %Y %n' \"$f\" 2>/dev/null || echo \"$(wc -c < \"$f\") 0 $f\"; }; done; "
                + "command -v gzip >/dev/null 2>&1 && command -v base64 >/dev/null 2>&1 && echo '" + GZIP_MARK + "'; true";
    }

    /** "size mtime path" lines; the same file seen through two directories is listed once. */
    static List<RemoteFile> parseListing(String out) {
        List<RemoteFile> raw = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : out.split("\n")) {
            String[] p = line.trim().split(" ", 3);
            if (p.length < 3 || !p[0].matches("\\d+") || !p[1].matches("\\d+")) continue;
            String path = p[2];
            String base = path.substring(path.lastIndexOf('/') + 1);
            if (!seen.add(p[0] + "|" + p[1] + "|" + base)) continue;
            raw.add(new RemoteFile(path, Long.parseLong(p[0]), Long.parseLong(p[1]) * 1000, false));
        }
        long newest = raw.stream().mapToLong(RemoteFile::modifiedMs).max().orElse(0);
        List<RemoteFile> files = new ArrayList<>();
        for (RemoteFile f : raw) {
            boolean current = f.path().endsWith(".current") || (f.modifiedMs() == newest && !f.path().matches(".*\\.\\d+$"));
            files.add(new RemoteFile(f.path(), f.sizeBytes(), f.modifiedMs(), current));
        }
        files.sort(Comparator.comparingLong(RemoteFile::modifiedMs).reversed().thenComparing(RemoteFile::path));
        return files;
    }

    /** Lists the GC log files on the node. */
    public static Discovery discover(Shell sh, String node, List<String> jvmArgs, String javaVersion, String jvmNote) {
        String configured = configuredPath(jvmArgs);
        List<String> pats = patterns(configured);
        String out = sh.exec(listCommand(pats.stream().map(GcLogFiles::glob).toList()), LIST_TIMEOUT, 256 * 1024);
        List<RemoteFile> files = parseListing(out);
        String note = jvmNote;
        if (files.isEmpty()) {
            note = (note == null ? "" : note + " ") + "No GC log found in " + String.join(", ", pats)
                    + " as the SSH user. Check that GC logging is on and that the SSH user can read the log directory.";
        }
        return new Discovery(node, javaVersion, gcOptions(jvmArgs), configured, pats, files, out.contains(GZIP_MARK), note);
    }

    /** Null when {@code path} may be fetched, else why not (absolute, plain characters, a GC log name). */
    public static String checkPath(String path) {
        if (path == null || !SAFE_PATH.matcher(path).matches() || path.contains("/../") || path.endsWith("/..")) {
            return "not an absolute file path";
        }
        String base = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (!base.contains("gc")) return "not a GC log file name";
        return null;
    }

    /** Current size and time of the given files (missing ones are left out), plus whether gzip works. */
    static Discovery stat(Shell sh, String node, List<String> paths) {
        String out = sh.exec(listCommand(paths.stream().map(NodeShell::quote).toList()), LIST_TIMEOUT, 256 * 1024);
        return new Discovery(node, null, List.of(), null, paths, parseListing(out), out.contains(GZIP_MARK), null);
    }

    public record Fetched(List<GcReport.SourceFile> files, long bytes) {}

    /**
     * Fetches {@code paths} into the parser, oldest first. The newest files win when the total exceeds
     * {@code maxBytes}: the file that crosses the cap is read from its end (the newest part), older
     * ones are skipped.
     */
    public static Fetched fetch(Shell sh, String node, List<String> paths, long maxBytes, GcLogParser parser,
                                JobContext ctx) throws IOException {
        Discovery d = stat(sh, node, paths);
        if (d.files().isEmpty()) throw new IllegalStateException("None of the selected files exists on " + node);
        List<RemoteFile> newestFirst = d.files();
        List<long[]> plan = new ArrayList<>(); // per file: bytes to read
        long budget = maxBytes;
        List<RemoteFile> chosen = new ArrayList<>();
        for (RemoteFile f : newestFirst) {
            if (budget <= 0) {
                ctx.log("Skipped " + f.path() + ": over the " + mb(maxBytes) + " cap");
                continue;
            }
            boolean gz = f.path().endsWith(".gz");
            if (gz && f.sizeBytes() > budget) {
                ctx.log("Skipped " + f.path() + ": compressed file larger than the remaining cap");
                continue;
            }
            long take = Math.min(f.sizeBytes(), budget);
            budget -= take;
            chosen.add(f);
            plan.add(new long[] {take});
        }
        long total = plan.stream().mapToLong(p -> p[0]).sum();
        List<GcReport.SourceFile> got = new ArrayList<>();
        long done = 0;
        for (int i = chosen.size() - 1; i >= 0; i--) { // oldest first
            ctx.checkCancelled();
            RemoteFile f = chosen.get(i);
            long take = plan.get(i)[0];
            boolean tail = take < f.sizeBytes();
            ctx.progress(total == 0 ? null : (double) done / total, "Reading " + f.path());
            readOne(sh, f, take, tail, d.compressed(), parser);
            parser.endOfFile();
            done += take;
            got.add(new GcReport.SourceFile(f.path(), f.sizeBytes(), tail));
            ctx.log("Read " + f.path() + " (" + mb(take) + (tail ? ", last part only" : "") + ")");
        }
        return new Fetched(got, done);
    }

    private static void readOne(Shell sh, RemoteFile f, long take, boolean tail, boolean compressed, GcLogParser parser)
            throws IOException {
        String q = NodeShell.quote(f.path());
        boolean gz = f.path().endsWith(".gz");
        Duration timeout = Duration.ofSeconds(30 + take / (2L * 1024 * 1024));
        String src = tail ? "tail -c " + take + " " + q : "cat " + q;
        if (compressed) {
            String cmd = "set -o pipefail 2>/dev/null; " + (gz ? "base64 < " + q : src + " | gzip -c | base64");
            int limit = (int) Math.min(Integer.MAX_VALUE - 1024L, (long) (take * 1.45) + (1 << 20));
            byte[] bytes = null;
            for (int attempt = 0; bytes == null; attempt++) {
                String out = sh.exec(cmd, timeout, limit);
                if (out.length() >= limit - 1) throw new IOException("Transfer of " + f.path() + " was cut off");
                byte[] b = Base64.getMimeDecoder().decode(out);
                if (b.length == 0) throw new IOException("Could not read " + f.path());
                if (completeGzip(b)) bytes = b;
                else if (attempt >= 1) throw new IOException("Transfer of " + f.path() + " was incomplete twice; try again");
            }
            // parsed only once the whole stream is known to be complete, so a retry cannot duplicate events
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes), 1 << 16)) {
                parser.feed(in);
            }
        } else {
            if (gz) throw new IOException(f.path() + " is compressed and the node has no base64 to transfer it");
            int limit = (int) Math.min(Integer.MAX_VALUE - 1024L, take + (1 << 20));
            String out = sh.exec(src, timeout, limit);
            parser.feed(new ByteArrayInputStream(out.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** True when the gzip data decompresses to its end (a cut-off transfer does not). */
    static boolean completeGzip(byte[] b) {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(b), 1 << 16)) {
            byte[] buf = new byte[1 << 16];
            while (in.read(buf) >= 0) {
                // drain
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024);
    }
}
