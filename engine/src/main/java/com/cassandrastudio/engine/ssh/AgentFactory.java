package com.cassandrastudio.engine.ssh;

import java.io.IOException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.List;
import java.util.Map;
import org.apache.sshd.agent.SshAgent;
import org.apache.sshd.agent.SshAgentFactory;
import org.apache.sshd.agent.SshAgentKeyConstraint;
import org.apache.sshd.agent.SshAgentServer;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.AttributeRepository.AttributeKey;
import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.channel.ChannelFactory;
import org.apache.sshd.common.session.ConnectionService;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionContext;

/**
 * Gives MINA the user's ssh-agent only for sessions that chose agent authentication; other
 * sessions see an empty agent, so they authenticate with exactly the key or password configured.
 * No agent forwarding to the nodes.
 */
final class AgentFactory implements SshAgentFactory {
    /** Connection-context flag: the session authenticates through the agent at this location. */
    static final AttributeKey<String> AGENT_LOCATION = new AttributeKey<>();

    @Override
    public List<ChannelFactory> getChannelForwardingFactories(FactoryManager manager) {
        return List.of();
    }

    @Override
    public SshAgent createClient(Session session, FactoryManager manager) throws IOException {
        AttributeRepository ctx = session instanceof ClientSession cs ? cs.getConnectionContext() : null;
        String where = ctx == null ? null : ctx.getAttribute(AGENT_LOCATION);
        return where == null ? new NoAgent() : AgentConnection.open(where);
    }

    @Override
    public SshAgentServer createServer(ConnectionService service) {
        throw new UnsupportedOperationException("agent forwarding is not offered");
    }

    /** The agent of a session that does not use one. */
    private static final class NoAgent implements SshAgent {
        @Override
        public Iterable<? extends Map.Entry<PublicKey, String>> getIdentities() {
            return List.of();
        }

        @Override
        public Map.Entry<String, byte[]> sign(SessionContext session, PublicKey key, String algo, byte[] data)
                throws IOException {
            throw new IOException("no ssh-agent for this session");
        }

        @Override
        public void addIdentity(KeyPair key, String comment, SshAgentKeyConstraint... constraints) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeIdentity(PublicKey key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeAllIdentities() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {}
    }
}
