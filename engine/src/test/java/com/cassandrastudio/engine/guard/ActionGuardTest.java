package com.cassandrastudio.engine.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cassandrastudio.engine.audit.AuditLog;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.Environment;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.ApiException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ActionGuardTest {
    private AuditLog audit;
    private ActionGuard guard;
    private final ActionGuard.Action drop = new ActionGuard.Action("cql", "drop a table", List.of("DROP TABLE ks.t"),
            List.of("data loss"), true, null);

    static ConnectionConfig conn(Environment env, boolean readOnly) {
        return new ConnectionConfig("id-1", null, "core-" + env.name().toLowerCase(), env, null, readOnly, List.of("h"),
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @BeforeEach
    void setUp() {
        audit = new AuditLog(Database.inMemory(), "tester");
        guard = new ActionGuard(audit);
    }

    @Test
    void readOnlyAlwaysRefusedAndAudited() {
        assertThatThrownBy(() -> guard.check(conn(Environment.DEV, true), drop, new ActionGuard.Confirmation(true, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(403));
        assertThat(audit.search(null, null, null, 10)).singleElement()
                .satisfies(e -> assertThat(e.outcome()).isEqualTo("BLOCKED"));
    }

    @Test
    void nonProdNeedsConfirmation() {
        assertThatThrownBy(() -> guard.check(conn(Environment.DEV, false), drop, ActionGuard.Confirmation.NONE))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(428);
                    assertThat(e.details()).containsEntry("requireTypedName", false).containsKey("preview");
                });
        assertThatCode(() -> guard.check(conn(Environment.DEV, false), drop, new ActionGuard.Confirmation(true, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void prodNeedsTypedName() {
        ConnectionConfig prod = conn(Environment.PROD, false);
        assertThatThrownBy(() -> guard.check(prod, drop, new ActionGuard.Confirmation(true, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.details()).containsEntry("requireTypedName", true));
        assertThatThrownBy(() -> guard.check(prod, drop, new ActionGuard.Confirmation(true, "core-staging")))
                .isInstanceOf(ApiException.class);
        assertThat(audit.search(null, null, null, 10)).singleElement()
                .satisfies(e -> assertThat(e.error()).contains("did not match"));
        assertThatCode(() -> guard.check(prod, drop, new ActionGuard.Confirmation(false, "core-prod")))
                .doesNotThrowAnyException();
    }
}
