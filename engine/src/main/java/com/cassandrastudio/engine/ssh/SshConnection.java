package com.cassandrastudio.engine.ssh;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import org.apache.sshd.client.channel.ChannelDirectTcpip;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.channel.exception.SshChannelOpenException;
import org.apache.sshd.common.util.net.SshdSocketAddress;

/** An authenticated SSH session to one node, possibly through a jump host. Thread-safe. */
public final class SshConnection implements AutoCloseable {
    private static final SshdSocketAddress LOOPBACK_ANY_PORT = new SshdSocketAddress("127.0.0.1", 0);

    private final ClientSession session;
    private final ClientSession jump;
    private final String jumpName;

    SshConnection(ClientSession session, ClientSession jump, String jumpName) {
        this.session = session;
        this.jump = jump;
        this.jumpName = jumpName;
    }

    public boolean isOpen() {
        return session.isOpen() && (jump == null || jump.isOpen());
    }

    /** "bastion:22" when the session goes through a jump host, else null. */
    public String jumpHost() {
        return jumpName;
    }

    /**
     * Null when the node can open a TCP connection to {@code host:port} (as seen from the node),
     * else the reason, e.g. "Connection refused". Cheap: one channel open and close.
     */
    public String probe(String host, int port, Duration timeout) {
        return probe(session, host, port, timeout);
    }

    static String probe(ClientSession via, String host, int port, Duration timeout) {
        ChannelDirectTcpip ch = null;
        try {
            ch = via.createDirectTcpipChannel(LOOPBACK_ANY_PORT, new SshdSocketAddress(host, port));
            ch.open().verify(timeout);
            return null;
        } catch (IOException | RuntimeException e) {
            Throwable reason = e;
            for (Throwable t = e; t != null; t = t.getCause()) {
                reason = t;
                if (t instanceof SshChannelOpenException) break; // carries the server's own words
            }
            String m = reason.getMessage();
            return m == null || m.isBlank() ? reason.getClass().getSimpleName() : m;
        } finally {
            if (ch != null) ch.close(true);
        }
    }

    /** Forwards a free port on this machine's 127.0.0.1 to {@code host:port} as seen from the node. */
    public int forwardLocalPort(String host, int port) {
        try {
            return session.startLocalPortForwarding(LOOPBACK_ANY_PORT, new SshdSocketAddress(host, port)).getPort();
        } catch (IOException e) {
            throw new SshAccessException("cannot open an SSH port forward to " + host + ":" + port + ": "
                    + e.getMessage(), e);
        }
    }

    /** Upper bound on the output {@link #exec} keeps. */
    static final int MAX_EXEC_OUTPUT = 64 * 1024;

    /**
     * Runs {@code command} on the node and returns its standard output. Throws
     * {@link SshAccessException} on a non-zero exit, a timeout or a closed session.
     */
    public String exec(String command, Duration timeout) {
        ByteArrayOutputStream out = new LimitedOutput(MAX_EXEC_OUTPUT);
        ByteArrayOutputStream err = new LimitedOutput(4096);
        try (ChannelExec ch = session.createExecChannel(command)) {
            ch.setOut(out);
            ch.setErr(err);
            ch.open().verify(timeout);
            var events = ch.waitFor(EnumSet.of(ClientChannelEvent.CLOSED, ClientChannelEvent.EXIT_STATUS), timeout);
            if (events.contains(ClientChannelEvent.TIMEOUT)) {
                throw new SshAccessException("'" + command + "' did not finish within " + timeout.toSeconds() + " s", null);
            }
            Integer status = ch.getExitStatus();
            if (status != null && status != 0) {
                String e = err.toString(StandardCharsets.UTF_8).strip();
                throw new SshAccessException("'" + command + "' exited with " + status + (e.isEmpty() ? "" : ": " + e), null);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SshAccessException("cannot run '" + command + "' over SSH: " + e.getMessage(), e);
        }
    }

    /** Keeps the first {@code max} bytes and drops the rest. */
    private static final class LimitedOutput extends ByteArrayOutputStream {
        private final int max;

        LimitedOutput(int max) {
            this.max = max;
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            int room = max - count;
            if (room > 0) super.write(b, off, Math.min(len, room));
        }

        @Override
        public synchronized void write(int b) {
            if (count < max) super.write(b);
        }
    }

    @Override
    public void close() {
        session.close(true);
        if (jump != null) jump.close(true);
    }
}
