package com.cassandrastudio.engine.ssh;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.sshd.agent.common.AbstractAgentProxy;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;

/**
 * Talks the ssh-agent protocol to the user's running agent, without native code: the
 * {@code SSH_AUTH_SOCK} Unix socket (Linux, macOS), or the Windows OpenSSH agent's named pipe.
 * PuTTY's Pageant uses a different transport and is not supported.
 */
final class AgentConnection extends AbstractAgentProxy {
    static final String WINDOWS_PIPE = "\\\\.\\pipe\\openssh-ssh-agent";
    private static final int MAX_MESSAGE = 256 * 1024;

    private final ByteChannel channel;

    private AgentConnection(ByteChannel channel) {
        super(null);
        this.channel = channel;
    }

    /** Where the agent listens on this machine, or an exception that says how to start one. */
    static String locate() throws IOException {
        String sock = System.getenv("SSH_AUTH_SOCK");
        if (sock != null && !sock.isBlank()) return sock;
        if (System.getProperty("os.name", "").toLowerCase().startsWith("windows")) {
            if (Files.exists(Path.of(WINDOWS_PIPE))) return WINDOWS_PIPE;
            throw new IOException("no ssh-agent: start the Windows 'OpenSSH Authentication Agent' service and "
                    + "ssh-add your key (Pageant is not supported), or use key file authentication");
        }
        throw new IOException("no ssh-agent: SSH_AUTH_SOCK is not set; start ssh-agent and ssh-add your key, "
                + "or use key file authentication");
    }

    static AgentConnection open(String where) throws IOException {
        if (where.startsWith("\\\\.\\pipe\\")) {
            return new AgentConnection(new RandomAccessFile(where, "rw").getChannel());
        }
        SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            ch.connect(UnixDomainSocketAddress.of(where));
        } catch (IOException e) {
            ch.close();
            throw new IOException("cannot reach ssh-agent at " + where + ": " + e.getMessage(), e);
        }
        return new AgentConnection(ch);
    }

    @Override
    protected synchronized Buffer request(Buffer buffer) throws IOException {
        ByteBuffer out = ByteBuffer.wrap(buffer.array(), buffer.rpos(), buffer.available());
        while (out.hasRemaining()) channel.write(out);
        ByteBuffer len = readFully(4);
        int n = len.getInt();
        if (n <= 0 || n > MAX_MESSAGE) throw new IOException("bad ssh-agent reply length " + n);
        return new ByteArrayBuffer(readFully(n).array());
    }

    private ByteBuffer readFully(int n) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(n);
        while (b.hasRemaining()) {
            if (channel.read(b) < 0) throw new IOException("ssh-agent closed the connection");
        }
        return b.flip();
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            channel.close();
        }
    }
}
