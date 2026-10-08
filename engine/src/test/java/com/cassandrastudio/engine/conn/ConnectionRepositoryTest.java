package com.cassandrastudio.engine.conn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.Environment;
import com.cassandrastudio.engine.model.Folder;
import com.cassandrastudio.engine.secrets.SecretStore;
import com.cassandrastudio.engine.secrets.SecretStores;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.ApiException;
import com.cassandrastudio.engine.util.Json;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConnectionRepositoryTest {
    private SecretStore secrets;
    private ConnectionRepository repo;

    static ConnectionConfig conn(String name, String folder) {
        return new ConnectionConfig(null, folder, name, Environment.PROD, null, false, List.of("10.0.0.1", "10.0.0.2:9142"),
                "dc_east", "cassandra", null, null, null, null, null, null, null, List.of("core"), null, null);
    }

    @BeforeEach
    void setUp() {
        secrets = SecretStores.inMemory();
        repo = new ConnectionRepository(Database.inMemory(), secrets);
    }

    @Test
    void createReadUpdateDelete() {
        ConnectionConfig c = repo.save(conn("core-prod", null), Map.of("password", "pw"));
        assertThat(c.id()).isNotBlank();
        assertThat(c.secretsSet()).containsEntry("password", true).containsEntry("jmxPassword", false);
        assertThat(c.defaultConsistency()).isEqualTo("LOCAL_ONE");
        assertThat(c.jmx().method()).isEqualTo(ConnectionConfig.JmxMethod.SSH_TUNNEL);

        repo.save(c.withName("core-prod-renamed"), null);
        assertThat(repo.get(c.id()).name()).isEqualTo("core-prod-renamed");
        assertThat(repo.secret(c.id(), "password")).contains("pw");

        repo.delete(c.id());
        assertThat(repo.list()).isEmpty();
        assertThat(secrets.get("conn/" + c.id() + "/password")).isEmpty();
    }

    @Test
    void secretUpdateRules() {
        ConnectionConfig c = repo.save(conn("a", null), Map.of("password", "one"));
        Map<String, String> unchanged = new HashMap<>();
        unchanged.put("password", null);
        repo.save(c, unchanged);
        assertThat(repo.secret(c.id(), "password")).contains("one");
        repo.save(c, Map.of("password", ""));
        assertThat(repo.secret(c.id(), "password")).isEmpty();
        assertThatThrownBy(() -> repo.save(c, Map.of("bogus", "x"))).isInstanceOf(ApiException.class);
    }

    @Test
    void validation() {
        assertThatThrownBy(() -> repo.save(conn("", null), null)).hasMessageContaining("Name");
        ConnectionConfig noPoints = new ConnectionConfig(null, null, "x", null, null, false, List.of(), null, null, null,
                null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> repo.save(noPoints, null)).hasMessageContaining("contact point");
        assertThatThrownBy(() -> repo.save(conn("x", "no-such-folder"), null)).isInstanceOf(ApiException.class);
    }

    @Test
    void foldersNestAndCannotLoop() {
        Folder acme = repo.createFolder(null, "acme");
        Folder prod = repo.createFolder(acme.id(), "prod");
        assertThatThrownBy(() -> repo.updateFolder(acme.id(), prod.id(), null, null)).hasMessageContaining("into itself");
        ConnectionConfig c = repo.save(conn("core", prod.id()), null);
        repo.deleteFolder(acme.id());
        assertThat(repo.folders()).isEmpty();
        assertThat(repo.get(c.id()).folderId()).isNull(); // moved to root, not deleted
    }

    @Test
    void cloneCopiesSecrets() {
        ConnectionConfig c = repo.save(conn("a", null), Map.of("password", "pw"));
        ConnectionConfig copy = repo.cloneConnection(c.id());
        assertThat(copy.id()).isNotEqualTo(c.id());
        assertThat(copy.name()).isEqualTo("a (copy)");
        assertThat(repo.secret(copy.id(), "password")).contains("pw");
    }

    @Test
    void exportNeverContainsSecretsAndImportRecreates() {
        Folder acme = repo.createFolder(null, "acme");
        Folder prod = repo.createFolder(acme.id(), "prod");
        repo.save(conn("core", prod.id()), Map.of("password", "TOPSECRET", "sshPassphrase", "ALSOSECRET"));
        String exported = Json.write(repo.export());
        assertThat(exported).doesNotContain("TOPSECRET").doesNotContain("ALSOSECRET").doesNotContain("secretsSet");

        ConnectionRepository other = new ConnectionRepository(Database.inMemory(), SecretStores.inMemory());
        var result = other.importFile(Json.read(exported, ConnectionRepository.ImportFile.class));
        assertThat(result.folders()).isEqualTo(2);
        assertThat(result.connections()).isEqualTo(1);
        ConnectionConfig imported = other.list().get(0);
        Folder importedProd = other.folders().stream().filter(f -> f.name().equals("prod")).findFirst().orElseThrow();
        assertThat(imported.folderId()).isEqualTo(importedProd.id());
        assertThat(importedProd.parentId()).isNotNull();
        assertThat(imported.secretsSet()).containsEntry("password", false);
    }

    @Test
    void importRejectsForeignAndNewerFiles() {
        assertThatThrownBy(() -> repo.importFile(new ConnectionRepository.ImportFile("other", 1, List.of(), List.of())))
                .hasMessageContaining("Not a Cassandra Studio");
        assertThatThrownBy(() -> repo.importFile(new ConnectionRepository.ImportFile("cassandra-studio-connections", 99, List.of(), List.of())))
                .hasMessageContaining("newer");
    }
}
