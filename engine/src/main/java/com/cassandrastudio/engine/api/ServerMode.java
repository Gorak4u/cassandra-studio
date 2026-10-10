package com.cassandrastudio.engine.api;

import io.javalin.config.JavalinConfig;
import io.javalin.config.RoutesConfig;

/**
 * What changes when the engine runs as Studio Server (SRV-1): named API tokens instead of the desktop's
 * per-launch token, every request run as the token's user ({@code audit.Actor}), TLS, and a few extra
 * routes (health, server status). The REST API is otherwise the desktop's, unchanged.
 */
public interface ServerMode {
    /** The user a bearer token belongs to, or null when the token is unknown or revoked. */
    String authenticate(String token);

    /** Paths under /api reachable without a token (load-balancer health checks). */
    boolean isPublic(String path);

    /** Adds connectors (TLS), CORS and the like before the server starts. */
    void configure(JavalinConfig cfg);

    /** Server-only routes. */
    void routes(RoutesConfig app);
}
