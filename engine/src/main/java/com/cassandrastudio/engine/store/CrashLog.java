package com.cassandrastudio.engine.store;

import com.cassandrastudio.engine.util.Masking;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Local crash and diagnostics log (NFR-OBS, desktop): uncaught engine errors, unexpected API errors
 * and errors reported by the UI go to {@code <data dir>/logs/crash.log}. Nothing is ever sent
 * anywhere. Secrets are scrubbed before writing; the file rolls over at {@value #MAX_BYTES} bytes
 * (one previous file kept).
 */
public final class CrashLog {
    static final long MAX_BYTES = 1024 * 1024;
    static final int RECENT = 20;
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(\\b(?:password|passphrase|passwd|secret|token|authorization)\\b[\"']?\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;&\"'}]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+");

    /** One error as shown in "Copy diagnostics": no stack trace, scrubbed. */
    public record Recent(String at, String source, String message) {}

    private static volatile CrashLog installed;

    private final Path file;
    private final Deque<Recent> recent = new ArrayDeque<>();

    CrashLog(Path dataDir) {
        this.file = dataDir.resolve("logs").resolve("crash.log");
    }

    /** Called once at startup: routes uncaught exceptions of every thread here. */
    public static CrashLog install(Path dataDir) {
        CrashLog log = new CrashLog(dataDir);
        installed = log;
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            log.record("engine", "Uncaught in thread " + t.getName(), e);
            if (previous != null) previous.uncaughtException(t, e);
            else e.printStackTrace(System.err);
        });
        return log;
    }

    /** The installed log, or null (tests and embedded use). */
    public static CrashLog get() {
        return installed;
    }

    /** Records on the installed log; a no-op when none is installed. */
    public static void report(String source, String context, Throwable error) {
        CrashLog log = installed;
        if (log != null) log.record(source, context, error);
    }

    public Path file() {
        return file;
    }

    public void record(String source, String context, Throwable error) {
        StringWriter sw = new StringWriter();
        if (error != null) error.printStackTrace(new PrintWriter(sw));
        String summary = context + (error == null ? "" : ": " + error);
        record(source, summary, sw.toString());
    }

    /** {@code detail}: a stack trace or other multi-line context; scrubbed like the message. */
    public synchronized void record(String source, String message, String detail) {
        String at = Instant.now().toString();
        String msg = scrub(message == null ? "" : message);
        if (msg.length() > 2_000) msg = msg.substring(0, 2_000) + "...";
        String det = detail == null ? "" : scrub(detail);
        if (det.length() > 20_000) det = det.substring(0, 20_000) + "\n...";
        recent.addLast(new Recent(at, source, msg));
        while (recent.size() > RECENT) recent.removeFirst();
        StringBuilder entry = new StringBuilder().append(at).append(" [").append(source).append("] ").append(msg).append('\n');
        if (!det.isBlank()) entry.append(det.stripTrailing().indent(4));
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file) && Files.size(file) > MAX_BYTES) {
                Files.move(file, file.resolveSibling("crash.log.1"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(file, entry, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // the log is best effort: never fail the caller because the disk is full or read-only
        }
    }

    /** The last {@value #RECENT} errors of this run, oldest first. */
    public synchronized List<Recent> recent() {
        return List.copyOf(recent);
    }

    /** Hides passwords, tokens and secrets in free text (statements, URLs, JSON, key=value). */
    public static String scrub(String text) {
        if (text == null) return null;
        String s = Masking.mask(text);
        s = BEARER.matcher(s).replaceAll("$1*****");
        return SECRET.matcher(s).replaceAll("$1*****");
    }
}
