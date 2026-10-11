package com.cassandrastudio.engine.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerConfigTest {
    @TempDir
    Path dir;

    @Test
    void refusesNonLoopbackWithoutTls() {
        assertThatThrownBy(() -> ServerConfig.of(Map.of("server.host", "0.0.0.0")).validate())
                .hasMessageContaining("without TLS").hasMessageContaining("--insecure-http");
        ServerConfig.of(Map.of("server.host", "0.0.0.0", "server.insecureHttp", "true")).validate();
        ServerConfig.of(Map.of("server.host", "127.0.0.1")).validate();
        ServerConfig.of(Map.of("server.host", "localhost")).validate();
        ServerConfig.of(Map.of("server.host", "::1")).validate();
    }

    @Test
    void tlsNeedsAReadableKeystoreAndPassword() {
        assertThatThrownBy(() -> ServerConfig.of(Map.of("server.host", "0.0.0.0", "tls.keystore", dir.resolve("none.p12").toString()))
                .validate()).hasMessageContaining("not readable").hasMessageContaining("tls.keystorePassword");
    }

    @Test
    void fileEnvironmentAndSecretFiles() throws Exception {
        Files.writeString(dir.resolve("pw"), "s3cret\n");
        Path conf = dir.resolve("server.conf");
        Files.writeString(conf, """
                server.port = 9443
                store.url = jdbc:postgresql://db:5432/studio
                store.passwordFile = pw
                server.dataDir = data
                """);
        ServerConfig c = ServerConfig.load(conf, Map.of("STUDIO_SERVER_PORT", "9444", "STUDIO_STORE_USER", "studio"));
        assertThat(c.port()).isEqualTo(9444);
        assertThat(c.get("store.user")).isEqualTo("studio");
        assertThat(c.secret("store.password")).isEqualTo("s3cret");
        assertThat(c.dataDir()).isEqualTo(dir.resolve("data"));
        assertThat(c.storeUrl()).startsWith("jdbc:postgresql:");
        assertThat(ServerConfig.of(Map.of("server.dataDir", dir.toString())).storeUrl()).isEqualTo("jdbc:sqlite:" + dir.resolve("studio.db"));
    }

    @Test
    void typosAndBadValuesAreReported() throws Exception {
        Path conf = dir.resolve("server.conf");
        Files.writeString(conf, "server.prot = 1\n");
        assertThatThrownBy(() -> ServerConfig.load(conf, Map.of())).hasMessageContaining("server.prot");
        assertThatThrownBy(() -> ServerConfig.of(Map.of("audit.syslog.protocol", "smtp")).validate()).hasMessageContaining("udp or tcp");
        assertThatThrownBy(() -> ServerConfig.of(Map.of("store.url", "jdbc:mysql://x")).validate()).hasMessageContaining("store.url");
        assertThatThrownBy(() -> ServerConfig.of(Map.of("ha.leaseSeconds", "1")).validate()).hasMessageContaining("leaseSeconds");
        assertThatThrownBy(() -> ServerConfig.of(Map.of("secrets.provider", "vault")).validate()).hasMessageContaining("vault.address");
    }
}
