package com.cassandrastudio.engine.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ScriptRepositoryTest {
    private final ScriptRepository repo = new ScriptRepository(Database.inMemory());

    @Test
    void crudAndOrdering() {
        var a = repo.save(null, "/ops/daily/", "check tombstones", "SELECT 1 FROM t;");
        repo.save(null, "", "adhoc", "SELECT 2 FROM t;");
        assertThat(a.folder()).isEqualTo("ops/daily");
        assertThat(repo.list()).extracting(ScriptRepository.Script::name).containsExactly("adhoc", "check tombstones");
        assertThat(repo.list()).allSatisfy(s -> assertThat(s.content()).isNull());

        var updated = repo.save(a.id(), "ops", "check tombstones", "SELECT 3 FROM t;");
        assertThat(repo.get(a.id()).content()).isEqualTo("SELECT 3 FROM t;");
        assertThat(updated.folder()).isEqualTo("ops");

        repo.delete(a.id());
        assertThatThrownBy(() -> repo.get(a.id())).hasMessageContaining("not found");
        assertThatThrownBy(() -> repo.save(null, "", " ", "x")).hasMessageContaining("name");
        assertThatThrownBy(() -> repo.save("missing", "", "n", "x")).hasMessageContaining("not found");
    }
}
