package com.cassandrastudio.engine.api;

import com.cassandrastudio.engine.Engine;
import io.javalin.config.RoutesConfig;

/**
 * Phase 3 Track 4, effective config and drift (CFG-1/2). Routes live under /api/clusters/{id}/config/...; the feature's services
 * live in package com.cassandrastudio.engine.config and are created here from the shared
 * engine services (jobs, shell, opsJmx, topology, guard, audit). Register resources to close
 * with {@link Engine#onClose} and per-connection cleanup with {@link Engine#onDisconnect}.
 */
public final class ConfigRoutes {
    private ConfigRoutes() {}

    public static void register(RoutesConfig app, Engine engine) {
        // filled in by the track
    }
}
