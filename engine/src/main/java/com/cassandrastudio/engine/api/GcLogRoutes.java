package com.cassandrastudio.engine.api;

import com.cassandrastudio.engine.Engine;
import io.javalin.config.RoutesConfig;

/**
 * Phase 3 Track 3, GC log analysis (GCL-1..3, GCL-4 findings). Routes live under /api/clusters/{id}/gclog/...; the feature's services
 * live in package com.cassandrastudio.engine.gclog and are created here from the shared
 * engine services (jobs, shell, opsJmx, topology, guard, audit). Register resources to close
 * with {@link Engine#onClose} and per-connection cleanup with {@link Engine#onDisconnect}.
 */
public final class GcLogRoutes {
    private GcLogRoutes() {}

    public static void register(RoutesConfig app, Engine engine) {
        // filled in by the track
    }
}
