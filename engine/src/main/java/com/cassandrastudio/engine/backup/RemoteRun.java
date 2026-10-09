package com.cassandrastudio.engine.backup;

import com.cassandrastudio.engine.ssh.NodeShell;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a long command on a node detached from the SSH channel (setsid + nohup, output to a file
 * under /tmp/cassandra-studio-&lt;uid&gt;/), then polls its output and exit status. A dropped SSH
 * session does not kill the backup, the output arrives while it runs (progress), and the run
 * can be stopped by signalling its process group.
 */
final class RemoteRun {
    private RemoteRun() {}

    /** Runs a command on a node over SSH; throws with a readable message on failure. */
    @FunctionalInterface
    interface NodeExec {
        String exec(String host, String command, Duration timeout);
    }

    static final int CHUNK = 60_000;
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern STATE = Pattern.compile("^STATE rc=(\\d*) alive=([01]) n=(\\d+)$");

    /** Where the run's files live on the node: a per-user directory and the run's own prefix. */
    static String file(String runId) {
        return "\"/tmp/cassandra-studio-$(id -u)/" + runId + "\"";
    }

    static String startCommand(String runId, String command) {
        String inner = "echo $$ > \"$0.pid\"; " + command + "; echo $? > \"$0.rc.tmp\" && mv \"$0.rc.tmp\" \"$0.rc\"";
        // Only the last command is backgrounded, with every stream redirected, so the SSH channel
        // closes at once while the run carries on.
        return "d=\"/tmp/cassandra-studio-$(id -u)\"; mkdir -p \"$d\" && chmod 700 \"$d\" || exit 1; f=" + file(runId)
                + "; rm -f \"$f\".*; if command -v setsid >/dev/null 2>&1; then S=setsid; else S=; fi;"
                + " $S nohup bash -c " + NodeShell.quote(inner) + " \"$f\" > \"$f.log\" 2>&1 < /dev/null &"
                + " sleep 0.2; echo STARTED";
    }

    static String pollCommand(String runId, long offset) {
        return "f=" + file(runId) + "; rc=$(cat \"$f.rc\" 2>/dev/null || true); pid=$(cat \"$f.pid\" 2>/dev/null || true);"
                + " if [ -n \"$pid\" ] && [ -d \"/proc/$pid\" ]; then a=1; else a=0; fi;"
                + " size=$(wc -c < \"$f.log\" 2>/dev/null || echo 0); n=$((size - " + offset + "));"
                + " [ \"$n\" -lt 0 ] && n=0; [ \"$n\" -gt " + CHUNK + " ] && n=" + CHUNK + ";"
                + " echo \"STATE rc=$rc alive=$a n=$n\";"
                + " [ \"$n\" -gt 0 ] && tail -c +" + (offset + 1) + " \"$f.log\" | head -c \"$n\"; true";
    }

    /** SIGTERM to the run's process group (the script, sudo and their children). */
    static String killCommand(String runId, String prefix) {
        // The shell builtin (dash) rejects "--"; /bin/kill under sudo needs it before a negative pid.
        String group = prefix.isEmpty() ? "kill -TERM -\"$pid\"" : prefix + "kill -s TERM -- -\"$pid\"";
        return "f=" + file(runId) + "; pid=$(cat \"$f.pid\" 2>/dev/null || true);"
                + " [ -n \"$pid\" ] && { " + group + " 2>/dev/null || " + prefix
                + "kill -TERM \"$pid\" 2>/dev/null; }; echo KILLED";
    }

    static String cleanupCommand(String runId) {
        return "f=" + file(runId) + "; rm -f \"$f.log\" \"$f.pid\" \"$f.rc\" \"$f.rc.tmp\"";
    }

    record Poll(Integer exitCode, boolean alive, int bytes, String chunk) {}

    static Poll parsePoll(String out) {
        int nl = out.indexOf('\n');
        String head = nl < 0 ? out.strip() : out.substring(0, nl).strip();
        Matcher m = STATE.matcher(head);
        if (!m.matches()) throw new IllegalStateException("unexpected reply while following the run: " + head);
        Integer rc = m.group(1).isEmpty() ? null : Integer.parseInt(m.group(1));
        int n = Integer.parseInt(m.group(3));
        return new Poll(rc, "1".equals(m.group(2)), n, nl < 0 ? "" : out.substring(nl + 1));
    }

    /**
     * Starts {@code command} on {@code host} and follows it to the end. Lines go to {@code onLine}
     * as they arrive. Returns the exit code; throws {@link CancellationException} when
     * {@code cancelled} turns true (after signalling the run), or IllegalStateException on a timeout
     * or when the run disappears without an exit status.
     */
    static int run(NodeExec ex, String host, String runId, String command, String killPrefix, Duration timeout,
                   Duration pollEvery, Consumer<String> onLine, BooleanSupplier cancelled) throws InterruptedException {
        ex.exec(host, startCommand(runId, command), CALL_TIMEOUT);
        long deadline = System.nanoTime() + timeout.toNanos();
        long offset = 0;
        StringBuilder partial = new StringBuilder();
        int deadPolls = 0;
        int failures = 0;
        try {
            while (true) {
                if (cancelled.getAsBoolean()) {
                    kill(ex, host, runId, killPrefix);
                    throw new CancellationException("cancelled");
                }
                if (System.nanoTime() > deadline) {
                    kill(ex, host, runId, killPrefix);
                    throw new IllegalStateException("did not finish within " + timeout.toMinutes() + " min; it was stopped");
                }
                Poll p;
                try {
                    p = parsePoll(ex.exec(host, pollCommand(runId, offset), CALL_TIMEOUT));
                    failures = 0;
                } catch (RuntimeException e) {
                    if (++failures >= 5) {
                        throw new IllegalStateException("lost contact with the node while following the backup ("
                                + BackupService.withoutCommand(String.valueOf(e.getMessage())) + "); it may still be running there", e);
                    }
                    Thread.sleep(pollEvery.toMillis());
                    continue;
                }
                offset += p.bytes();
                lines(partial, p.chunk(), onLine);
                if (p.bytes() >= CHUNK) continue;
                if (p.exitCode() != null) {
                    if (!partial.isEmpty()) onLine.accept(partial.toString());
                    cleanup(ex, host, runId);
                    return p.exitCode();
                }
                if (!p.alive()) {
                    if (++deadPolls >= 3) {
                        if (!partial.isEmpty()) onLine.accept(partial.toString());
                        cleanup(ex, host, runId);
                        throw new IllegalStateException("the backup process ended without an exit status (killed?)");
                    }
                } else {
                    deadPolls = 0;
                }
                Thread.sleep(pollEvery.toMillis());
            }
        } catch (InterruptedException e) {
            kill(ex, host, runId, killPrefix);
            throw e;
        }
    }

    static void kill(NodeExec ex, String host, String runId, String prefix) {
        try {
            ex.exec(host, killCommand(runId, prefix), CALL_TIMEOUT);
        } catch (RuntimeException e) {
            // best effort: the caller reports the cancel either way
        }
    }

    private static void cleanup(NodeExec ex, String host, String runId) {
        try {
            ex.exec(host, cleanupCommand(runId), CALL_TIMEOUT);
        } catch (RuntimeException e) {
            // leftover files in the user's temp dir are harmless
        }
    }

    private static void lines(StringBuilder partial, String chunk, Consumer<String> onLine) {
        partial.append(chunk);
        int start = 0;
        for (int i = 0; i < partial.length(); i++) {
            if (partial.charAt(i) == '\n') {
                String line = partial.substring(start, i);
                if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                onLine.accept(line);
                start = i + 1;
            }
        }
        partial.delete(0, start);
    }
}
