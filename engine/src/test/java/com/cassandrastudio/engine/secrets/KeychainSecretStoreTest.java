package com.cassandrastudio.engine.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs against the real OS keychain (Windows Credential Manager, macOS Keychain).
 * Tagged so it only runs where a keychain exists: CI runs it on Windows and macOS
 * with -Pkeychain.
 */
@Tag("keychain")
class KeychainSecretStoreTest {
    @TempDir
    Path dir;

    @Test
    void usesTheOsKeychainAndRoundTrips() {
        SecretStore store = SecretStores.create(dir, true);
        assertThat(store.description()).doesNotContain("encrypted file");
        String key = "test/" + UUID.randomUUID();
        try {
            store.put(key, "p@ss'word with spaces");
            assertThat(store.get(key)).contains("p@ss'word with spaces");
            store.put(key, "changed");
            assertThat(store.get(key)).contains("changed");
        } finally {
            store.delete(key);
        }
        assertThat(store.get(key)).isEmpty();
    }
}
