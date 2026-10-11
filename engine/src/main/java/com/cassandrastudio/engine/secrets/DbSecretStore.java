package com.cassandrastudio.engine.secrets;

import com.cassandrastudio.engine.store.Database;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Studio Server's encrypted secret store (SRV-3): connection passwords and passphrases encrypted with
 * AES-256-GCM in the shared store ({@code server_secrets}), so every instance of an HA pair sees them.
 * The key comes from a file or an environment variable and is never written to the database; each value
 * is bound to its name (GCM associated data), so values cannot be swapped between rows. A check value
 * makes an instance with a different key refuse to start instead of failing on first use.
 */
public final class DbSecretStore implements SecretStore {
    static final String CHECK = "__key_check__";
    private static final String CHECK_PLAIN = "cassandra-studio";
    private static final int IV_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Database db;
    private final SecretKeySpec key;

    /** @param key 32 bytes (AES-256) */
    public DbSecretStore(Database db, byte[] key) {
        if (key == null || key.length != 32) throw new IllegalArgumentException("The secrets key must be 32 bytes (base64 of 32 random bytes)");
        this.db = db;
        this.key = new SecretKeySpec(key, "AES");
        checkKey();
    }

    /** A new random key, base64 (for {@code studio-server secret-key}). */
    public static String newKey() {
        byte[] k = new byte[32];
        RANDOM.nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    /** Decodes a base64 key as written by {@link #newKey}. */
    public static byte[] decodeKey(String base64) {
        try {
            byte[] k = Base64.getDecoder().decode(base64.strip());
            if (k.length != 32) throw new IllegalArgumentException("The secrets key must be 32 bytes, got " + k.length);
            return k;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The secrets key is not valid base64 of 32 bytes: " + e.getMessage(), e);
        }
    }

    private void checkKey() {
        List<Map<String, Object>> rows = db.query("SELECT value FROM server_secrets WHERE name = ?", CHECK);
        if (rows.isEmpty()) {
            write(CHECK, CHECK_PLAIN);
            return;
        }
        try {
            if (!CHECK_PLAIN.equals(decrypt(CHECK, (String) rows.get(0).get("value")))) throw new IllegalStateException();
        } catch (IllegalStateException e) {
            throw new IllegalStateException("The secrets key does not match the one this store was written with."
                    + " Every Studio Server instance needs the same key (secrets.keyFile or STUDIO_SECRETS_KEY).");
        }
    }

    @Override
    public void put(String name, String secret) {
        if (CHECK.equals(name)) throw new IllegalArgumentException("Reserved secret name");
        write(name, secret);
    }

    private void write(String name, String secret) {
        db.update("INSERT INTO server_secrets(name, value, updated_at) VALUES (?,?,?)"
                        + " ON CONFLICT(name) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at",
                name, encrypt(name, secret), Instant.now().toString());
    }

    @Override
    public Optional<String> get(String name) {
        if (CHECK.equals(name)) return Optional.empty();
        List<Map<String, Object>> rows = db.query("SELECT value FROM server_secrets WHERE name = ?", name);
        return rows.isEmpty() ? Optional.empty() : Optional.of(decrypt(name, (String) rows.get(0).get("value")));
    }

    @Override
    public void delete(String name) {
        if (!CHECK.equals(name)) db.update("DELETE FROM server_secrets WHERE name = ?", name);
    }

    @Override
    public String description() {
        return "Studio Server encrypted store (AES-256-GCM, " + db.description() + ")";
    }

    private String encrypt(String name, String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            c.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return "v1:" + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private String decrypt(String name, String enc) {
        try {
            if (enc == null || !enc.startsWith("v1:")) throw new IllegalStateException("Unknown secret format for " + name);
            byte[] in = Base64.getDecoder().decode(enc.substring(3));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, in, 0, IV_BYTES));
            c.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new IllegalStateException("Secret " + name + " could not be decrypted (wrong key, or the value was changed)", e);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Secret " + name + " could not be decrypted: " + e.getMessage(), e);
        }
    }
}
