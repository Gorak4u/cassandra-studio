package com.cassandrastudio.engine.api;

import com.cassandrastudio.engine.Engine;
import io.javalin.config.RoutesConfig;

/**
 * Phase 3 Track 2, JVM and partition diagnostics (JVM-1/2, PRF-1/2). Routes live under /api/clusters/{id}/diag/...; the feature's services
 * live in package com.cassandrastudio.engine.diag and are created here from the shared
 * engine services (jobs, shell, opsJmx, topology, guard, audit). Register resources to close
 * with {@link Engine#onClose} and per-connection cleanup with {@link Engine#onDisconnect}.
 */
public final class DiagRoutes {
    private DiagRoutes() {}

    public static void register(RoutesConfig app, Engine engine) {
        // filled in by the track
    }
}
