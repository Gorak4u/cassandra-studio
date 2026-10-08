package com.cassandrastudio.engine.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSecretStoreTest {
    @TempDir
    Path dir;

    @Test
    void roundTripAndPersistence() throws Exception {
        SecretStore s = SecretStores.create(dir, false);
        s.put("conn/1/password", "p@ss'word");
        assertThat(s.get("conn/1/password")).contains("p@ss'word");
        assertThat(Files.readString(dir.resolve("secrets.json"))).doesNotContain("p@ss");

        SecretStore reopened = SecretStores.create(dir, false);
        assertThat(reopened.get("conn/1/password")).contains("p@ss'word");
        reopened.delete("conn/1/password");
        assertThat(reopened.get("conn/1/password")).isEmpty();
    }
}
