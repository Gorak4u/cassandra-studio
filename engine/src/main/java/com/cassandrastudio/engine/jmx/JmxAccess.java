package com.cassandrastudio.engine.jmx;

import com.cassandrastudio.engine.model.ConnectionConfig;
import java.util.List;
import java.util.Map;
import javax.management.MBeanServerConnection;

/**
 * Phase 2 contract (track A implements, track B consumes): how the engine reaches a
 * node's JMX, or its jmx_exporter endpoint when JMX is not reachable (CON-6, CON-7).
 *
 * <ul>
 *   <li>SSH_TUNNEL (estate default): SSH to the node, forward localhost:7199. With stock
 *       LOCAL_JMX=yes the RMI server object listens on a random port, so every port the
 *       stubs advertise gets its own forward through the same SSH session.</li>
 *   <li>DIRECT: JMX/RMI to node:port, optional username/password and SSL.</li>
 *   <li>EXPORTER: no JMX; metrics only, read from http://node:exporterPort/metrics.</li>
 *   <li>SIDECAR / NONE: {@link #session} throws {@link UnsupportedOperationException}.</li>
 * </ul>
 *
 * Implementations cache one session per node, reconnect with back-off after failure
 * (NFR-RELI) and must never block the caller for longer than the configured timeout.
 */
public interface JmxAccess extends AutoCloseable {

    /**
     * An open JMX session to one node, reused across polls. Throws
     * {@link JmxUnavailableException} with a user-readable reason when the node cannot be
     * reached (SSH refused, auth failed, JMX not listening, timeout ...).
     */
    JmxSession session(ConnectionConfig cfg, Map<String, String> secrets, NodeEndpoint node);

    /** Raw jmx_exporter samples for a node (EXPORTER method), or for any method as a fallback. */
    List<ExporterSample> scrapeExporter(ConnectionConfig cfg, NodeEndpoint node);

    /** Close every session and tunnel for one connection (on disconnect or config change). */
    void closeConnection(String connectionId);

    @Override
    void close();

    interface JmxSession {
        MBeanServerConnection mbeans();

        /** How this session reaches the node, for the UI, e.g. "ssh tunnel via bastion:22". */
        String route();
    }

    /** One line of Prometheus text format, e.g. cassandra_load_bytes{node="x"} 1234. */
    record ExporterSample(String name, Map<String, String> labels, double value) {}

    final class JmxUnavailableException extends RuntimeException {
        public JmxUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
