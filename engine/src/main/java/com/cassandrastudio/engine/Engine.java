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
import com.cassandrastudio.engine.metrics.DriverTopology;
import com.cassandrastudio.engine.metrics.MonitoringService;
import com.cassandrastudio.engine.schema.SchemaService;
import com.cassandrastudio.engine.secrets.SecretStore;
import com.cassandrastudio.engine.security.RoleService;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.store.ScriptRepository;

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
    public final MonitoringService monitoring;

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
        this.monitoring = new MonitoringService(db, connections, jmx, new DriverTopology(sessions, clusters));
    }

    /** Drops everything held open for a connection: monitoring, JMX/SSH tunnels, the driver session. */
    public void disconnect(String connectionId) {
        monitoring.stop(connectionId);
        jmx.closeConnection(connectionId);
        sessions.disconnect(connectionId);
    }

    @Override
    public void close() {
        monitoring.close();
        jmx.close();
        sessions.close();
        db.close();
    }
}
