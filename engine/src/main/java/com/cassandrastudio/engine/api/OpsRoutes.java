package com.cassandrastudio.engine.api;

import com.cassandrastudio.engine.Engine;
import io.javalin.config.RoutesConfig;

/**
 * Phase 3 Track 1, operations (OPS-1..4). Routes live under /api/clusters/{id}/ops/...; the feature's services
 * live in package com.cassandrastudio.engine.ops and are created here from the shared
 * engine services (jobs, shell, opsJmx, topology, guard, audit). Register resources to close
 * with {@link Engine#onClose} and per-connection cleanup with {@link Engine#onDisconnect}.
 */
public final class OpsRoutes {
    private OpsRoutes() {}

    public static void register(RoutesConfig app, Engine engine) {
        // filled in by the track
    }
}
