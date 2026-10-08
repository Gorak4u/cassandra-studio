package com.cassandrastudio.engine.secrets;

import com.github.javakeyring.Keyring;
import com.github.javakeyring.PasswordAccessException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SecretStores {
    private static final Logger LOG = LoggerFactory.getLogger(SecretStores.class);
    static final String SERVICE = "cassandra-studio";

    private SecretStores() {}

    /**
     * The OS keychain when one is usable (Windows Credential Manager, macOS
     * Keychain, Linux Secret Service); otherwise an AES-GCM encrypted file in
     * the data directory, e.g. on a headless Linux server with no keyring.
     */
    public static SecretStore create(Path dataDir, boolean preferKeyring) {
        if (preferKeyring) {
            try {
                Keyring keyring = Keyring.create();
                // A round trip proves the backend really works; on Linux the
                // library loads even when no Secret Service is running.
                String probe = "probe-" + System.nanoTime();
                keyring.setPassword(SERVICE, probe, "ok");
                keyring.deletePassword(SERVICE, probe);
                return new KeyringSecretStore(keyring);
            } catch (Exception | LinkageError e) {
                LOG.warn("OS keychain unavailable ({}); using encrypted file store", e.toString());
            }
        }
        return new FileSecretStore(dataDir);
    }

    public static SecretStore inMemory() {
        Map<String, String> map = new ConcurrentHashMap<>();
        return new SecretStore() {
            public void put(String key, String secret) { map.put(key, secret); }
            public Optional<String> get(String key) { return Optional.ofNullable(map.get(key)); }
            public void delete(String key) { map.remove(key); }
            public String description() { return "in-memory"; }
        };
    }

    static final class KeyringSecretStore implements SecretStore {
        private final Keyring keyring;

        KeyringSecretStore(Keyring keyring) {
            this.keyring = keyring;
        }

        @Override
        public void put(String key, String secret) {
            try {
                keyring.setPassword(SERVICE, key, secret);
            } catch (PasswordAccessException e) {
                throw new IllegalStateException("Cannot write to OS keychain: " + e.getMessage(), e);
            }
        }

        @Override
        public Optional<String> get(String key) {
            try {
                return Optional.ofNullable(keyring.getPassword(SERVICE, key));
            } catch (PasswordAccessException e) {
                return Optional.empty();
            }
        }

        @Override
        public void delete(String key) {
            try {
                keyring.deletePassword(SERVICE, key);
            } catch (PasswordAccessException ignored) {
                // already absent
            }
        }

        @Override
        public String description() {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) return "Windows Credential Manager";
            if (os.contains("mac")) return "macOS Keychain";
            return "Secret Service keyring";
        }
    }
}
