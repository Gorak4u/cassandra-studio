package com.cassandrastudio.engine.api;

import com.cassandrastudio.engine.Engine;
import io.javalin.config.RoutesConfig;

/**
 * Phase 3 Track 5, backups (BAK-1..3). Routes live under /api/clusters/{id}/backup/...; the feature's services
 * live in package com.cassandrastudio.engine.backup and are created here from the shared
 * engine services (jobs, shell, opsJmx, topology, guard, audit). Register resources to close
 * with {@link Engine#onClose} and per-connection cleanup with {@link Engine#onDisconnect}.
 */
public final class BackupRoutes {
    private BackupRoutes() {}

    public static void register(RoutesConfig app, Engine engine) {
        // filled in by the track
    }
}
