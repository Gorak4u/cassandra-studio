package com.cassandrastudio.engine.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrashLogTest {
    @TempDir
    Path dir;

    @Test
    void writesScrubbedEntriesAndKeepsRecentOnes() throws Exception {
        CrashLog log = new CrashLog(dir);
        log.record("api", "POST /api/x", new IllegalStateException("login failed password=hunter2 token: abc123"));
        log.record("ui", "TypeError: x is undefined", "at App (App.tsx:1)\nAuthorization: Bearer deadbeef");
        log.record("engine", "CREATE ROLE r WITH PASSWORD = 'pw1'", (String) null);
        String text = Files.readString(log.file());
        assertThat(log.file()).isEqualTo(dir.resolve("logs").resolve("crash.log"));
        assertThat(text).contains("[api] POST /api/x: java.lang.IllegalStateException")
                .contains("    at ").contains("[ui] TypeError").contains("App.tsx:1")
                .doesNotContain("hunter2").doesNotContain("abc123").doesNotContain("deadbeef").doesNotContain("pw1");
        assertThat(log.recent()).extracting(CrashLog.Recent::source).containsExactly("api", "ui", "engine");
        for (int i = 0; i < CrashLog.RECENT + 5; i++) log.record("ui", "e" + i, (String) null);
        assertThat(log.recent()).hasSize(CrashLog.RECENT).last().satisfies(r -> assertThat(r.message()).isEqualTo("e24"));
    }

    @Test
    void rollsOverWhenLarge() throws Exception {
        CrashLog log = new CrashLog(dir);
        String big = "x".repeat(15_000);
        for (int i = 0; i < 80; i++) log.record("ui", "m", big);
        assertThat(dir.resolve("logs").resolve("crash.log.1")).exists();
        assertThat(Files.size(log.file())).isLessThanOrEqualTo(CrashLog.MAX_BYTES + 30_000);
    }

    @Test
    void scrubsCommonSecretShapes() {
        assertThat(CrashLog.scrub("{\"password\":\"p\",\"user\":\"u\"}")).isEqualTo("{\"password\":*****,\"user\":\"u\"}");
        assertThat(CrashLog.scrub("url?token=abc&x=1")).isEqualTo("url?token=*****&x=1");
        assertThat(CrashLog.scrub("nothing secret")).isEqualTo("nothing secret");
    }
}
