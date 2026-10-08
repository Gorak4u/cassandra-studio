package com.cassandrastudio.engine.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaskingTest {
    @Test
    void masksRoleAndUserPasswords() {
        assertThat(Masking.mask("CREATE ROLE bob WITH PASSWORD = 's3cr''et' AND LOGIN = true"))
                .isEqualTo("CREATE ROLE bob WITH PASSWORD = '*****' AND LOGIN = true");
        assertThat(Masking.mask("CREATE USER bob WITH PASSWORD 'pw' NOSUPERUSER"))
                .isEqualTo("CREATE USER bob WITH PASSWORD '*****' NOSUPERUSER");
        assertThat(Masking.mask("ALTER ROLE bob WITH HASHED PASSWORD = '$2a$10$abc'"))
                .isEqualTo("ALTER ROLE bob WITH HASHED PASSWORD = '*****'");
    }

    @Test
    void leavesOtherStatementsAlone() {
        assertThat(Masking.mask("SELECT password FROM users WHERE id = 1")).isEqualTo("SELECT password FROM users WHERE id = 1");
    }
}
