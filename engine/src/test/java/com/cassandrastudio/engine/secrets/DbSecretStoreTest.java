package com.cassandrastudio.engine.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cassandrastudio.engine.store.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DbSecretStoreTest {
    private final Database db = Database.inMemory();
    private final byte[] key = DbSecretStore.decodeKey(DbSecretStore.newKey());

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    void roundTripEncryptedInTheStore() {
        DbSecretStore s = new DbSecretStore(db, key);
        s.put("conn/1/cqlPassword", "hunter2");
        s.put("conn/1/cqlPassword", "hunter3");
        assertThat(s.get("conn/1/cqlPassword")).contains("hunter3");
        assertThat(s.get("conn/2/cqlPassword")).isEmpty();
        assertThat(db.query("SELECT value FROM server_secrets").toString()).doesNotContain("hunter");
        // a second instance with the same key reads it
        assertThat(new DbSecretStore(db, key).get("conn/1/cqlPassword")).contains("hunter3");
        s.delete("conn/1/cqlPassword");
        assertThat(s.get("conn/1/cqlPassword")).isEmpty();
        assertThat(s.description()).contains("AES-256-GCM");
    }

    @Test
    void wrongKeyIsRefusedAtStart() {
        new DbSecretStore(db, key).put("a", "b");
        byte[] other = DbSecretStore.decodeKey(DbSecretStore.newKey());
        assertThatThrownBy(() -> new DbSecretStore(db, other)).hasMessageContaining("does not match");
    }

    @Test
    void valuesCannotBeSwappedBetweenNames() {
        DbSecretStore s = new DbSecretStore(db, key);
        s.put("conn/1/cqlPassword", "one");
        s.put("conn/2/cqlPassword", "two");
        db.update("UPDATE server_secrets SET value = (SELECT value FROM server_secrets WHERE name = 'conn/1/cqlPassword')"
                + " WHERE name = 'conn/2/cqlPassword'");
        assertThatThrownBy(() -> s.get("conn/2/cqlPassword")).hasMessageContaining("could not be decrypted");
    }

    @Test
    void keyMustBe32Bytes() {
        assertThatThrownBy(() -> DbSecretStore.decodeKey("c2hvcnQ=")).hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> DbSecretStore.decodeKey("not base64!")).hasMessageContaining("base64");
        assertThat(new DbSecretStore(db, key).get(DbSecretStore.CHECK)).isEmpty();
    }
}
