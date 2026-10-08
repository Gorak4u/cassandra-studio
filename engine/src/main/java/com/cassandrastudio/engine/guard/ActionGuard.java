package com.cassandrastudio.engine.guard;

import com.cassandrastudio.engine.audit.AuditLog;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.util.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one gate every change goes through (NFR-SAFE): CQL writes, DDL, role
 * changes, nodetool operations, scripts and jobs.
 *
 * <ul>
 *   <li>Read-only connection: refused (CON-12).</li>
 *   <li>Not confirmed: refused with 428 and what the UI must show and ask.</li>
 *   <li>PROD: the confirmation must be the connection's name, typed.</li>
 * </ul>
 * Refusals are audited as BLOCKED so attempts are visible too.
 */
public final class ActionGuard {

    /** What the caller wants to do. {@code preview} is the exact CQL / nodetool equivalent shown to the user. */
    public record Action(String category, String summary, List<String> preview, List<String> warnings,
                         boolean destructive, String node) {}

    /** What the user sent back: either nothing, {@code confirmed=true}, or the typed name for PROD. */
    public record Confirmation(boolean confirmed, String typedName) {
        public static final Confirmation NONE = new Confirmation(false, null);
    }

    private final AuditLog audit;

    public ActionGuard(AuditLog audit) {
        this.audit = audit;
    }

    public void check(ConnectionConfig conn, Action action, Confirmation confirmation) {
        Confirmation c = confirmation == null ? Confirmation.NONE : confirmation;
        if (conn.readOnly()) {
            audit.record(conn, action.node(), action.category(), action.summary(), String.join("\n", action.preview()),
                    AuditLog.Outcome.BLOCKED, "connection is read-only");
            throw new ApiException(403, "read_only",
                    "'" + conn.name() + "' is a read-only connection; " + action.summary() + " is not allowed.");
        }
        boolean ok = conn.isProd()
                ? c.typedName() != null && c.typedName().trim().equals(conn.name().trim())
                : c.confirmed() || c.typedName() != null && c.typedName().trim().equals(conn.name().trim());
        if (!ok) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("connectionName", conn.name());
            details.put("environment", conn.environment().name());
            details.put("summary", action.summary());
            details.put("preview", action.preview());
            details.put("warnings", action.warnings());
            details.put("destructive", action.destructive());
            details.put("requireTypedName", conn.isProd());
            if (c.typedName() != null) {
                audit.record(conn, action.node(), action.category(), action.summary(), String.join("\n", action.preview()),
                        AuditLog.Outcome.BLOCKED, "confirmation name did not match");
            }
            throw new ApiException(428, "confirmation_required",
                    conn.isProd()
                            ? "PRODUCTION: type the connection name '" + conn.name() + "' to confirm."
                            : "Confirm: " + action.summary(),
                    details);
        }
    }
}
