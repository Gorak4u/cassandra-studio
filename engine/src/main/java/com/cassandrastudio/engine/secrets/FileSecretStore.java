package com.cassandrastudio.engine.secrets;

import com.cassandrastudio.engine.util.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.TreeMap;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Fallback secret store: AES-256-GCM encrypted values in {@code secrets.json},
 * key in {@code secret.key} readable only by the owner. Weaker than an OS
 * keychain (anyone who can read both files gets the secrets), so it is only
 * used when no keychain works; the UI says which store is active.
 */
final class FileSecretStore implements SecretStore {
    private static final int IV_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path keyFile;
    private final Path storeFile;
    private final SecretKeySpec key;

    FileSecretStore(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
            this.keyFile = dataDir.resolve("secret.key");
            this.storeFile = dataDir.resolve("secrets.json");
            this.key = new SecretKeySpec(loadOrCreateKey(), "AES");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot initialise secret store in " + dataDir, e);
        }
    }

    private byte[] loadOrCreateKey() throws IOException {
        if (Files.exists(keyFile)) return Base64.getDecoder().decode(Files.readString(keyFile).trim());
        byte[] k = new byte[32];
        RANDOM.nextBytes(k);
        Files.writeString(keyFile, Base64.getEncoder().encodeToString(k));
        ownerOnly(keyFile);
        return k;
    }

    private static void ownerOnly(Path p) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows: the file lives in the user's profile, which is already per-user
        }
    }

    private TreeMap<String, String> load() {
        try {
            if (!Files.exists(storeFile)) return new TreeMap<>();
            return Json.MAPPER.readValue(Files.readString(storeFile), new TypeReference<TreeMap<String, String>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + storeFile, e);
        }
    }

    private void save(TreeMap<String, String> map) {
        try {
            Path tmp = storeFile.resolveSibling(storeFile.getFileName() + ".tmp");
            Files.writeString(tmp, Json.write(map));
            ownerOnly(tmp);
            Files.move(tmp, storeFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + storeFile, e);
        }
    }

    @Override
    public synchronized void put(String name, String secret) {
        TreeMap<String, String> map = load();
        map.put(name, encrypt(secret));
        save(map);
    }

    @Override
    public synchronized Optional<String> get(String name) {
        String v = load().get(name);
        return v == null ? Optional.empty() : Optional.of(decrypt(v));
    }

    @Override
    public synchronized void delete(String name) {
        TreeMap<String, String> map = load();
        if (map.remove(name) != null) save(map);
    }

    @Override
    public String description() {
        return "encrypted file (" + storeFile + ")";
    }

    private String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private String decrypt(String enc) {
        try {
            byte[] in = Base64.getDecoder().decode(enc);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, in, 0, IV_BYTES));
            return new String(c.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Secret could not be decrypted (key file changed?)", e);
        }
    }
}
