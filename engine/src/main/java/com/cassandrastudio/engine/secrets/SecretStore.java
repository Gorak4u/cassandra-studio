package com.cassandrastudio.engine.secrets;

import java.util.Optional;

/**
 * Where passwords and key passphrases live (CON-8). Never in the connection
 * config itself, so a config export can never carry a secret.
 */
public interface SecretStore {
    void put(String key, String secret);

    Optional<String> get(String key);

    void delete(String key);

    /** Short name shown in the UI, e.g. "macOS Keychain" or "encrypted file". */
    String description();
}
