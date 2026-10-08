package com.cassandrastudio.engine.security;

import com.cassandrastudio.engine.cql.Errors;
import com.cassandrastudio.engine.cql.SessionManager;
import com.cassandrastudio.engine.schema.DdlBuilder;
import com.cassandrastudio.engine.util.ApiException;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Users, roles and permissions (SEC-1, SEC-2). */
public final class RoleService {
    private static final Set<String> PERMISSIONS = Set.of("ALL", "ALTER", "AUTHORIZE", "CREATE", "DESCRIBE", "DROP",
            "EXECUTE", "MODIFY", "SELECT", "UNMASK", "SELECT_MASKED");

    private final SessionManager sessions;

    public RoleService(SessionManager sessions) {
        this.sessions = sessions;
    }

    public record Role(String name, boolean login, boolean superuser, List<String> memberOf) {}

    public record Permission(String role, String resource, String permission) {}

    public record RolesView(List<Role> roles, String rolesError, List<Permission> permissions, String permissionsError) {}

    /** Roles from system_auth.roles and all permissions; each half reports its own error (e.g. AllowAllAuthorizer). */
    public RolesView list(String connectionId) {
        CqlSession s = sessions.session(connectionId);
        List<Role> roles = new ArrayList<>();
        String rolesError = null;
        try {
            for (Row r : s.execute("SELECT role, can_login, is_superuser, member_of FROM system_auth.roles")) {
                roles.add(new Role(r.getString("role"), r.getBoolean("can_login"), r.getBoolean("is_superuser"),
                        r.isNull("member_of") ? List.of() : r.getSet("member_of", String.class).stream().sorted().toList()));
            }
            roles.sort((a, b) -> a.name().compareTo(b.name()));
        } catch (RuntimeException e) {
            rolesError = Errors.describe(e);
        }
        List<Permission> perms = new ArrayList<>();
        String permsError = null;
        try {
            for (Row r : s.execute("LIST ALL PERMISSIONS")) {
                perms.add(new Permission(r.getString("role"), r.getString("resource"), r.getString("permission")));
            }
        } catch (RuntimeException e) {
            permsError = Errors.describe(e);
        }
        return new RolesView(roles, rolesError, perms, permsError);
    }

    /** Effective permissions of one role, including those inherited from granted roles (SEC-4). */
    public List<Permission> permissionsOf(String connectionId, String role) {
        try {
            List<Permission> out = new ArrayList<>();
            for (Row r : sessions.session(connectionId).execute("LIST ALL PERMISSIONS OF " + DdlBuilder.id(role))) {
                out.add(new Permission(r.getString("role"), r.getString("resource"), r.getString("permission")));
            }
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ApiException(400, "cql_error", Errors.describe(e));
        }
    }

    // ---- CQL generation (previewed, then run through the guarded query path) ----

    public record RoleSpec(String name, String password, Boolean login, Boolean superuser, Boolean ifNotExists) {}

    public static String createRole(RoleSpec r) {
        List<String> with = new ArrayList<>();
        if (r.password() != null && !r.password().isEmpty()) with.add("PASSWORD = " + DdlBuilder.literal(r.password()));
        with.add("LOGIN = " + Boolean.TRUE.equals(r.login()));
        with.add("SUPERUSER = " + Boolean.TRUE.equals(r.superuser()));
        return "CREATE ROLE " + (Boolean.TRUE.equals(r.ifNotExists()) ? "IF NOT EXISTS " : "") + DdlBuilder.id(r.name())
                + " WITH " + String.join(" AND ", with) + ";";
    }

    public static String alterRole(RoleSpec r) {
        List<String> with = new ArrayList<>();
        if (r.password() != null && !r.password().isEmpty()) with.add("PASSWORD = " + DdlBuilder.literal(r.password()));
        if (r.login() != null) with.add("LOGIN = " + r.login());
        if (r.superuser() != null) with.add("SUPERUSER = " + r.superuser());
        if (with.isEmpty()) throw ApiException.badRequest("Nothing to change");
        return "ALTER ROLE " + DdlBuilder.id(r.name()) + " WITH " + String.join(" AND ", with) + ";";
    }

    public static String dropRole(String name) {
        return "DROP ROLE " + DdlBuilder.id(name) + ";";
    }

    public static String grantRole(String role, String to) {
        return "GRANT " + DdlBuilder.id(role) + " TO " + DdlBuilder.id(to) + ";";
    }

    public static String revokeRole(String role, String from) {
        return "REVOKE " + DdlBuilder.id(role) + " FROM " + DdlBuilder.id(from) + ";";
    }

    /**
     * resourceType: ALL_KEYSPACES, KEYSPACE, TABLE, ALL_ROLES, ROLE, ALL_FUNCTIONS, ALL_FUNCTIONS_IN_KEYSPACE, MBEANS.
     * resourceName: keyspace, "ks.table", or role name, as the type needs.
     */
    public static String grantPermission(String permission, String resourceType, String resourceName, String role, boolean revoke) {
        String p = permission.strip().toUpperCase(Locale.ROOT).replace(' ', '_');
        if (!PERMISSIONS.contains(p)) throw ApiException.badRequest("Unknown permission " + permission);
        String perm = p.equals("ALL") ? "ALL PERMISSIONS" : p;
        String resource = resource(resourceType, resourceName);
        return (revoke ? "REVOKE " : "GRANT ") + perm + " ON " + resource + (revoke ? " FROM " : " TO ") + DdlBuilder.id(role) + ";";
    }

    static String resource(String type, String name) {
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "ALL_KEYSPACES" -> "ALL KEYSPACES";
            case "KEYSPACE" -> "KEYSPACE " + DdlBuilder.id(name);
            case "TABLE" -> {
                if (name == null || !name.contains(".")) throw ApiException.badRequest("Table must be keyspace.table");
                String[] kt = name.split("\\.", 2);
                yield "TABLE " + DdlBuilder.qualified(kt[0], kt[1]);
            }
            case "ALL_ROLES" -> "ALL ROLES";
            case "ROLE" -> "ROLE " + DdlBuilder.id(name);
            case "ALL_FUNCTIONS" -> "ALL FUNCTIONS";
            case "ALL_FUNCTIONS_IN_KEYSPACE" -> "ALL FUNCTIONS IN KEYSPACE " + DdlBuilder.id(name);
            case "MBEANS" -> "ALL MBEANS";
            default -> throw ApiException.badRequest("Unknown resource type " + type);
        };
    }
}
