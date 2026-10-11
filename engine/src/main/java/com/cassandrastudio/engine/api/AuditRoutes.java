package com.cassandrastudio.engine.api;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.audit.AuditChain;
import io.javalin.config.RoutesConfig;

/** Tamper-evident audit log (SRV-6, docs/api/server.md): checks the hash chain over every entry. */
public final class AuditRoutes {
    private AuditRoutes() {}

    public static void register(RoutesConfig app, Engine engine) {
        app.get("/api/audit/verify", ctx -> ctx.json(AuditChain.verify(engine.db)));
    }
}
