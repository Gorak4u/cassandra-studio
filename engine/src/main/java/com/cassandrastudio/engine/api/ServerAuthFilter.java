package com.cassandrastudio.engine.api;

import com.cassandrastudio.engine.audit.Actor;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Studio Server: checks the API token of every /api request and runs the whole request as the token's
 * user, so the audit log, jobs and schedules record who did what (SRV-1, SRV-6).
 */
final class ServerAuthFilter implements Filter {
    /** Request attribute holding the authenticated user. */
    static final String USER = "studio.user";

    private final ServerMode mode;

    ServerAuthFilter(ServerMode mode) {
        this.mode = mode;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String path = req.getRequestURI();
        if (!path.startsWith("/api/") || "OPTIONS".equals(req.getMethod()) || mode.isPublic(path)) {
            chain.doFilter(request, response);
            return;
        }
        String auth = req.getHeader("Authorization");
        String presented = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).strip() : queryToken(req.getQueryString());
        String user = presented == null || presented.isBlank() ? null : mode.authenticate(presented);
        if (user == null) {
            res.setStatus(401);
            res.setContentType("application/json");
            res.setHeader("Cache-Control", "no-store");
            res.setHeader("WWW-Authenticate", "Bearer");
            res.getOutputStream().write("{\"error\":\"unauthorized\",\"message\":\"Missing, unknown or revoked API token\"}"
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        req.setAttribute(USER, user);
        try {
            Actor.as(user, () -> {
                try {
                    chain.doFilter(request, response);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } catch (ServletException e) {
                    throw new WrappedServletException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        } catch (WrappedServletException e) {
            throw (ServletException) e.getCause();
        }
    }

    /** {@code ?token=} (downloads and event streams that cannot set a header); only the query string, never a form body. */
    static String queryToken(String query) {
        if (query == null) return null;
        for (String part : query.split("&")) {
            if (part.startsWith("token=")) return java.net.URLDecoder.decode(part.substring(6), StandardCharsets.UTF_8);
        }
        return null;
    }

    private static final class WrappedServletException extends RuntimeException {
        WrappedServletException(ServletException e) {
            super(e);
        }
    }
}
