package com.cassandrastudio.engine.ssh;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnownHostsVerifierTest {
    @TempDir
    Path dir;

    private final KnownHostsVerifier verifier = new KnownHostsVerifier();

    private Path knownHosts(String... lines) throws Exception {
        return Files.writeString(dir.resolve("known_hosts"), String.join("\n", lines) + "\n");
    }

    private static String entry(PublicKey k) {
        return PublicKeyEntry.toString(k);
    }

    /** OpenSSH HashKnownHosts format: |1|salt|HMAC-SHA1(salt, name). */
    private static String hashed(String name, PublicKey k) throws Exception {
        byte[] salt = "0123456789abcdefghij".getBytes(StandardCharsets.US_ASCII);
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(salt, "HmacSHA1"));
        byte[] h = mac.doFinal(name.getBytes(StandardCharsets.UTF_8));
        Base64.Encoder b64 = Base64.getEncoder();
        return "|1|" + b64.encodeToString(salt) + "|" + b64.encodeToString(h) + " " + entry(k);
    }

    @Test
    void matchesPlainPortAndHashedEntries() throws Exception {
        PublicKey a = TestSshServer.ed25519().getPublic();
        PublicKey b = TestSshServer.ed25519().getPublic();
        Path file = knownHosts("# comment", "node1,10.0.0.1 " + entry(a), "[bastion]:2222 " + entry(b),
                hashed("[hidden]:2200", a), "garbage line");
        assertThat(verifier.rejection(new HostKeyCheck("node1", 22, true, file), a)).isNull();
        assertThat(verifier.rejection(new HostKeyCheck("10.0.0.1", 22, true, file), a)).isNull();
        assertThat(verifier.rejection(new HostKeyCheck("bastion", 2222, true, file), b)).isNull();
        assertThat(verifier.rejection(new HostKeyCheck("hidden", 2200, true, file), a)).isNull();
        // A port-22 entry does not vouch for another port.
        assertThat(verifier.rejection(new HostKeyCheck("node1", 2201, true, file), a))
                .startsWith("host key for [node1]:2201 not in known_hosts");
    }

    @Test
    void changedKeyIsRefusedWhenStrictAndWarnedOtherwise() throws Exception {
        PublicKey known = TestSshServer.ed25519().getPublic();
        PublicKey other = TestSshServer.ed25519().getPublic();
        Path file = knownHosts("node1 " + entry(known));
        assertThat(verifier.rejection(new HostKeyCheck("node1", 22, true, file), other))
                .startsWith("host key for node1 does not match " + file);
        assertThat(verifier.rejection(new HostKeyCheck("node1", 22, false, file), other)).isNull();
        assertThat(verifier.rejection(new HostKeyCheck("unknown", 22, false, file), other)).isNull();
    }

    @Test
    void revokedKeysAreAlwaysRefused() throws Exception {
        PublicKey k = TestSshServer.ed25519().getPublic();
        Path file = knownHosts("@revoked * " + entry(k), "node1 " + entry(k));
        assertThat(verifier.rejection(new HostKeyCheck("node1", 22, false, file), k)).contains("@revoked");
    }

    @Test
    void missingFileMeansNothingIsKnown() throws Exception {
        PublicKey k = TestSshServer.ed25519().getPublic();
        Path missing = dir.resolve("nope");
        assertThat(verifier.rejection(new HostKeyCheck("node1", 22, true, missing), k)).contains("not in known_hosts");
        assertThat(verifier.rejection(new HostKeyCheck("node1", 22, false, missing), k)).isNull();
    }

    @Test
    void picksUpEditsToTheFile() throws Exception {
        PublicKey k = TestSshServer.ed25519().getPublic();
        Path file = knownHosts("other " + entry(k));
        assertThat(verifier.rejection(new HostKeyCheck("node1", 22, true, file), k)).isNotNull();
        Files.writeString(file, "node1 " + entry(k) + "\n# now longer, so the size changes too\n");
        assertThat(verifier.rejection(new HostKeyCheck("node1", 22, true, file), k)).isNull();
    }
}
