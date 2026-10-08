package com.cassandrastudio.engine.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RoleServiceTest {
    @Test
    void createAndAlterRole() {
        assertThat(RoleService.createRole(new RoleService.RoleSpec("app_user", "it's", true, false, true)))
                .isEqualTo("CREATE ROLE IF NOT EXISTS app_user WITH PASSWORD = 'it''s' AND LOGIN = true AND SUPERUSER = false;");
        assertThat(RoleService.alterRole(new RoleService.RoleSpec("App", null, false, null, null)))
                .isEqualTo("ALTER ROLE \"App\" WITH LOGIN = false;");
        assertThatThrownBy(() -> RoleService.alterRole(new RoleService.RoleSpec("a", null, null, null, null)))
                .hasMessageContaining("Nothing");
    }

    @Test
    void permissions() {
        assertThat(RoleService.grantPermission("select", "TABLE", "shop.orders", "reader", false))
                .isEqualTo("GRANT SELECT ON TABLE shop.orders TO reader;");
        assertThat(RoleService.grantPermission("all", "KEYSPACE", "shop", "admin", true))
                .isEqualTo("REVOKE ALL PERMISSIONS ON KEYSPACE shop FROM admin;");
        assertThatThrownBy(() -> RoleService.grantPermission("FLY", "KEYSPACE", "shop", "a", false))
                .hasMessageContaining("Unknown permission");
    }

    @Test
    void roleMembership() {
        assertThat(RoleService.grantRole("reader", "alice")).isEqualTo("GRANT reader TO alice;");
        assertThat(RoleService.revokeRole("reader", "alice")).isEqualTo("REVOKE reader FROM alice;");
    }
}
