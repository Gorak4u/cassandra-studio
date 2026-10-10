package com.cassandrastudio.engine.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cassandrastudio.engine.store.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TokenStoreTest {
    private final Database db = Database.inMemory();
    private final TokenStore tokens = new TokenStore(db);

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    void tokenIdentifiesItsUserAndOnlyTheHashIsStored() {
        TokenStore.Created alice = tokens.add("alice", "laptop");
        TokenStore.Created bob = tokens.add(" bob ", null);
        assertThat(alice.secret()).startsWith(TokenStore.PREFIX).hasSizeGreaterThan(40);
        assertThat(tokens.authenticate(alice.secret())).isEqualTo("alice");
        assertThat(tokens.authenticate(bob.secret())).isEqualTo("bob");
        assertThat(tokens.authenticate(alice.secret() + "x")).isNull();
        assertThat(tokens.authenticate("nope")).isNull();
        assertThat(tokens.authenticate(null)).isNull();
        String stored = db.query("SELECT * FROM server_tokens").toString();
        assertThat(stored).doesNotContain(alice.secret()).contains(TokenStore.hash(alice.secret()));
        assertThat(tokens.list()).extracting(TokenStore.Token::user).containsExactly("alice", "bob");
        assertThat(tokens.list().get(0).lastUsedAt()).isNotNull();
    }

    @Test
    void revokeByIdOrUser() {
        TokenStore.Created a1 = tokens.add("alice", null);
        TokenStore.Created a2 = tokens.add("alice", null);
        TokenStore.Created b = tokens.add("bob", null);
        assertThat(tokens.revoke(a1.token().id())).isEqualTo(1);
        assertThat(tokens.authenticate(a1.secret())).isNull();
        assertThat(tokens.authenticate(a2.secret())).isEqualTo("alice");
        assertThat(tokens.revoke("alice")).isEqualTo(1);
        assertThat(tokens.authenticate(a2.secret())).isNull();
        assertThat(tokens.authenticate(b.secret())).isEqualTo("bob");
        assertThat(tokens.revoke("nobody")).isZero();
        assertThat(tokens.list()).filteredOn(TokenStore.Token::active).extracting(TokenStore.Token::user).containsExactly("bob");
    }

    @Test
    void revocationElsewhereIsSeenAfterTheCache() throws Exception {
        TokenStore.Created a = tokens.add("alice", null);
        assertThat(tokens.authenticate(a.secret())).isEqualTo("alice");
        // another instance (or the CLI) revokes it directly in the shared store
        new TokenStore(db).revoke(a.token().id());
        Thread.sleep(TokenStore.CACHE_MS + 100);
        assertThat(tokens.authenticate(a.secret())).isNull();
    }

    @Test
    void userNamesAreChecked() {
        assertThatThrownBy(() -> tokens.add(" ", null)).hasMessageContaining("required");
        assertThatThrownBy(() -> tokens.add("schedule: nightly", null)).hasMessageContaining("reserved");
        assertThatThrownBy(() -> tokens.add("a\nb", null)).hasMessageContaining("Invalid");
    }
}
