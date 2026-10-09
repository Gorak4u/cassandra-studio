package com.cassandrastudio.engine;

import com.cassandrastudio.engine.audit.AuditLog;
import com.cassandrastudio.engine.conn.ConnectionRepository;
import com.cassandrastudio.engine.cql.ClusterService;
import com.cassandrastudio.engine.cql.QueryService;
import com.cassandrastudio.engine.cql.RowEditService;
import com.cassandrastudio.engine.cql.SessionManager;
import com.cassandrastudio.engine.guard.ActionGuard;
import com.cassandrastudio.engine.jmx.DefaultJmxAccess;
import com.cassandrastudio.engine.jmx.JmxAccess;
import com.cassandrastudio.engine.jobs.JobService;
import com.cassandrastudio.engine.metrics.DriverTopology;
import com.cassandrastudio.engine.metrics.MonitoringService;
import com.cassandrastudio.engine.metrics.Topology;
import com.cassandrastudio.engine.model.ConnectionConfig.SecretKeys;
import com.cassandrastudio.engine.schema.SchemaService;
import com.cassandrastudio.engine.secrets.SecretStore;
import com.cassandrastudio.engine.security.RoleService;
import com.cassandrastudio.engine.ssh.NodeShell;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.store.ScriptRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All engine services, wired once. The HTTP layer and tests both use this. */
public final class Engine implements AutoCloseable {
    public final Database db;
    public final SecretStore secrets;
    public final ConnectionRepository connections;
    public final AuditLog audit;
    public final ActionGuard guard;
    public final SessionManager sessions;
    public final QueryService queries;
    public final ClusterService clusters;
    public final SchemaService schema;
    public final RoleService roles;
    public final RowEditService rowEdits;
    public final ScriptRepository scripts;
    public final JmxAccess jmx;
    /** JMX for operations (repair, compaction, snapshots ...): no read timeout, as those calls block. */
    public final JmxAccess opsJmx;
    public final Topology topology;
    public final MonitoringService monitoring;
    /** Phase 3: long-running tasks with progress and cancel (docs/api/jobs.md). */
    public final JobService jobs;
    /** Phase 3: shell commands on nodes over SSH. */
    public final NodeShell shell;
    private final List<AutoCloseable> closeables = new ArrayList<>();
    private final List<java.util.function.Consumer<String>> disconnectHooks = new ArrayList<>();

    public Engine(Database db, SecretStore secrets, String actor) {
        this.db = db;
        this.secrets = secrets;
        this.connections = new ConnectionRepository(db, secrets);
        this.audit = new AuditLog(db, actor);
        this.guard = new ActionGuard(audit);
        this.sessions = new SessionManager(connections);
        this.queries = new QueryService(connections, sessions, guard, audit, db);
        this.clusters = new ClusterService(sessions);
        this.schema = new SchemaService(sessions);
        this.roles = new RoleService(sessions);
        this.rowEdits = new RowEditService(sessions);
        this.scripts = new ScriptRepository(db);
        this.jmx = new DefaultJmxAccess();
        this.opsJmx = new DefaultJmxAccess(DefaultJmxAccess.DEFAULT_CONNECT_TIMEOUT, Duration.ZERO);
        this.topology = new DriverTopology(sessions, clusters);
        this.monitoring = new MonitoringService(db, connections, jmx, topology);
        this.jobs = new JobService(connections, audit);
        this.shell = new NodeShell();
    }

    /** Every stored secret of a connection (CQL, JMX, SSH, truststore), by {@link SecretKeys} name. */
    public Map<String, String> secretsFor(String connectionId) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : SecretKeys.ALL) connections.secret(connectionId, k).ifPresent(v -> m.put(k, v));
        return m;
    }

    /** A feature module's resource to close with the engine (closed before the shared services). */
    public synchronized void onClose(AutoCloseable c) {
        closeables.add(c);
    }

    /** Called with the connection id on disconnect, edit and delete, before the shared services drop it. */
    public synchronized void onDisconnect(java.util.function.Consumer<String> hook) {
        disconnectHooks.add(hook);
    }

    /** Drops everything held open for a connection: monitoring, JMX/SSH tunnels, the driver session. */
    public void disconnect(String connectionId) {
        List<java.util.function.Consumer<String>> hooks;
        synchronized (this) {
            hooks = List.copyOf(disconnectHooks);
        }
        hooks.forEach(h -> h.accept(connectionId));
        monitoring.stop(connectionId);
        jmx.closeConnection(connectionId);
        opsJmx.closeConnection(connectionId);
        shell.closeConnection(connectionId);
        sessions.disconnect(connectionId);
    }

    @Override
    public void close() {
        List<AutoCloseable> modules;
        synchronized (this) {
            modules = List.copyOf(closeables);
        }
        for (AutoCloseable c : modules.reversed()) {
            try {
                c.close();
            } catch (Exception e) {
                // keep closing the rest
            }
        }
        jobs.close();
        monitoring.close();
        jmx.close();
        opsJmx.close();
        shell.close();
        sessions.close();
        db.close();
    }
}
