package com.cassandrastudio.engine.store;

import com.cassandrastudio.engine.Version;
import com.cassandrastudio.engine.conn.ConnectionRepository;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.SecretKeys;
import com.cassandrastudio.engine.model.Folder;
import com.cassandrastudio.engine.util.ApiException;
import com.cassandrastudio.engine.util.Json;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Backup and restore of Studio's own settings (NFR-DATA): folders, connections, saved scripts and
 * the settings table, in one JSON file. Secrets are left out unless the user gives a passphrase;
 * then they are included encrypted (PBKDF2-SHA256 + AES-256-GCM), never in clear.
 *
 * <p>Restore keeps ids, so per-connection settings keep pointing at their connection. When an
 * item with the same id already exists, {@link Conflict} decides: skip it, replace it, or keep both
 * (the restored one gets a new id and " (restored)" in its name).
 */
public final class SettingsBackup {
    public static final String FORMAT = "cassandra-studio-settings";
    public static final int FORMAT_VERSION = 1;
    static final int KDF_ITERATIONS = 210_000;
    /** Per-viewer UI layout is not restored onto another install (its tabs point at local state). */
    private static final Set<String> NOT_EXPORTED_PREFIXES = Set.of("ui.");
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum Conflict { SKIP, REPLACE, KEEP_BOTH }

    public record Script(String id, String folder, String name, String content) {}

    public record EncryptedSecrets(String kdf, int iterations, String salt, String data) {}

    public record File(String format, Integer version, String exportedAt, String studioVersion, List<Folder> folders,
                       List<ConnectionConfig> connections, List<Script> scripts, Map<String, String> settings,
                       EncryptedSecrets secrets) {}

    /** Per kind: new items and items whose id already exists here. */
    public record Counts(int added, int conflicting) {}

    public record Preview(Counts folders, Counts connections, Counts scripts, Counts settings, boolean hasSecrets) {}

    public record Result(int folders, int connections, int scripts, int settings, int skipped, int secrets,
                         List<String> notes) {}

    private final Database db;
    private final ConnectionRepository connections;

    public SettingsBackup(Database db, ConnectionRepository connections) {
        this.db = db;
        this.connections = connections;
    }

    // ---- export -------------------------------------------------------------------------

    /** Everything restorable; secrets only when {@code passphrase} is given (min 8 characters). */
    public File export(String passphrase) {
        List<Script> scripts = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT id, folder, name, content FROM saved_scripts ORDER BY folder, name")) {
            scripts.add(new Script((String) r.get("id"), (String) r.get("folder"), (String) r.get("name"),
                    (String) r.get("content")));
        }
        Map<String, String> settings = new TreeMap<>();
        for (Map<String, Object> r : db.query("SELECT key, value FROM settings")) {
            String k = (String) r.get("key");
            if (NOT_EXPORTED_PREFIXES.stream().noneMatch(k::startsWith)) settings.put(k, String.valueOf(r.get("value")));
        }
        List<ConnectionConfig> conns = connections.list().stream().map(c -> c.withSecretsSet(null)).toList();
        EncryptedSecrets enc = null;
        if (passphrase != null) {
            requirePassphrase(passphrase);
            Map<String, Map<String, String>> all = new TreeMap<>();
            for (ConnectionConfig c : conns) {
                Map<String, String> m = new TreeMap<>();
                for (String k : SecretKeys.ALL) connections.secret(c.id(), k).ifPresent(v -> m.put(k, v));
                if (!m.isEmpty()) all.put(c.id(), m);
            }
            enc = encrypt(Json.write(all), passphrase);
        }
        return new File(FORMAT, FORMAT_VERSION, Instant.now().toString(), Version.VERSION, connections.folders(), conns,
                scripts, settings, enc);
    }

    // ---- restore ------------------------------------------------------------------------

    public Preview preview(File file) {
        validate(file);
        Set<String> folderIds = ids(db.query("SELECT id FROM folders"), "id");
        Set<String> connIds = ids(db.query("SELECT id FROM connections"), "id");
        Set<String> scriptIds = ids(db.query("SELECT id FROM saved_scripts"), "id");
        Set<String> keys = ids(db.query("SELECT key FROM settings"), "key");
        return new Preview(count(list(file.folders()).stream().map(Folder::id).toList(), folderIds),
                count(list(file.connections()).stream().map(ConnectionConfig::id).toList(), connIds),
                count(list(file.scripts()).stream().map(Script::id).toList(), scriptIds),
                count(file.settings() == null ? List.of() : List.copyOf(file.settings().keySet()), keys),
                file.secrets() != null);
    }

    /** Restores in one transaction: a bad file or a wrong passphrase changes nothing. */
    public Result restore(File file, Conflict conflict, String passphrase) {
        validate(file);
        Conflict mode = conflict == null ? Conflict.SKIP : conflict;
        Map<String, Map<String, String>> secrets = Map.of();
        List<String> notes = new ArrayList<>();
        if (file.secrets() != null) {
            if (passphrase == null || passphrase.isEmpty()) {
                notes.add("The file contains encrypted passwords; they were not restored because no passphrase was given.");
            } else {
                secrets = decrypt(file.secrets(), passphrase);
            }
        }
        Map<String, Map<String, String>> secretsByOldId = secrets;
        int[] n = new int[6]; // folders, connections, scripts, settings, skipped, secrets
        db.transaction(() -> {
            // Folders: parents first; a kept-both folder gets a new id that its children follow.
            Map<String, String> folderIds = new HashMap<>();
            Set<String> existingFolders = ids(db.query("SELECT id FROM folders"), "id");
            List<Folder> pending = new ArrayList<>(list(file.folders()));
            boolean progress = true;
            while (!pending.isEmpty() && progress) {
                progress = false;
                for (var it = pending.iterator(); it.hasNext(); ) {
                    Folder f = it.next();
                    if (f.parentId() != null && !folderIds.containsKey(f.parentId())
                            && pending.stream().anyMatch(p -> p.id().equals(f.parentId()))) continue;
                    String parent = f.parentId() == null ? null : folderIds.getOrDefault(f.parentId(),
                            existingFolders.contains(f.parentId()) ? f.parentId() : null);
                    if (!existingFolders.contains(f.id())) {
                        insertFolder(f.id(), parent, f.name(), f.position());
                        folderIds.put(f.id(), f.id());
                        n[0]++;
                    } else if (mode == Conflict.REPLACE) {
                        db.update("UPDATE folders SET parent_id=?, name=?, position=? WHERE id=?", parent, f.name(),
                                f.position(), f.id());
                        folderIds.put(f.id(), f.id());
                        n[0]++;
                    } else if (mode == Conflict.KEEP_BOTH) {
                        String id = UUID.randomUUID().toString();
                        insertFolder(id, parent, f.name() + " (restored)", f.position());
                        folderIds.put(f.id(), id);
                        n[0]++;
                    } else {
                        folderIds.put(f.id(), f.id());
                        n[4]++;
                    }
                    it.remove();
                    progress = true;
                }
            }
            // Connections, with their secrets and per-connection settings following any new id.
            Map<String, String> connIds = new HashMap<>();
            for (ConnectionConfig c : list(file.connections())) {
                if (c.id() == null) throw ApiException.badRequest("A connection in the file has no id");
                String folder = c.folderId() == null ? null : folderIds.get(c.folderId());
                boolean exists = connections.find(c.id()).isPresent();
                ConnectionConfig target;
                if (!exists || mode == Conflict.REPLACE) {
                    target = c.withFolder(folder).withSecretsSet(null);
                } else if (mode == Conflict.KEEP_BOTH) {
                    target = c.withId(UUID.randomUUID().toString()).withName(c.name() + " (restored)")
                            .withFolder(folder).withSecretsSet(null);
                } else {
                    n[4]++;
                    continue;
                }
                Map<String, String> s = secretsByOldId.get(c.id());
                connections.save(target, s == null ? null : new HashMap<>(s));
                if (s != null) n[5] += s.size();
                connIds.put(c.id(), target.id());
                n[1]++;
            }
            Set<String> existingScripts = ids(db.query("SELECT id FROM saved_scripts"), "id");
            for (Script s : list(file.scripts())) {
                if (s.id() == null || s.name() == null) throw ApiException.badRequest("A script in the file has no id or name");
                String now = Instant.now().toString();
                if (!existingScripts.contains(s.id())) {
                    db.update("INSERT INTO saved_scripts(id, folder, name, content, updated_at) VALUES (?,?,?,?,?)",
                            s.id(), nz(s.folder()), s.name(), nz(s.content()), now);
                } else if (mode == Conflict.REPLACE) {
                    db.update("UPDATE saved_scripts SET folder=?, name=?, content=?, updated_at=? WHERE id=?",
                            nz(s.folder()), s.name(), nz(s.content()), now, s.id());
                } else if (mode == Conflict.KEEP_BOTH) {
                    db.update("INSERT INTO saved_scripts(id, folder, name, content, updated_at) VALUES (?,?,?,?,?)",
                            UUID.randomUUID().toString(), nz(s.folder()), s.name() + " (restored)", nz(s.content()), now);
                } else {
                    n[4]++;
                    continue;
                }
                n[2]++;
            }
            Set<String> existingKeys = ids(db.query("SELECT key FROM settings"), "key");
            if (file.settings() != null) {
                for (Map.Entry<String, String> e : file.settings().entrySet()) {
                    String key = e.getKey();
                    if (key == null || e.getValue() == null || NOT_EXPORTED_PREFIXES.stream().anyMatch(key::startsWith)) continue;
                    String connPart = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : null;
                    if (connPart != null && connIds.containsKey(connPart)) {
                        key = key.substring(0, key.lastIndexOf('/') + 1) + connIds.get(connPart);
                    } else if (connPart != null && file.connections() != null
                            && file.connections().stream().anyMatch(c -> connPart.equals(c.id()))) {
                        n[4]++; // its connection was skipped
                        continue;
                    }
                    if (existingKeys.contains(key) && mode != Conflict.REPLACE) { // a setting has no "both"
                        n[4]++;
                        continue;
                    }
                    db.update("INSERT OR REPLACE INTO settings(key, value) VALUES (?, ?)", key, e.getValue());
                    n[3]++;
                }
            }
        });
        return new Result(n[0], n[1], n[2], n[3], n[4], n[5], notes);
    }

    // ---- helpers ------------------------------------------------------------------------

    private void insertFolder(String id, String parentId, String name, int position) {
        if (name == null || name.isBlank()) throw ApiException.badRequest("A folder in the file has no name");
        db.update("INSERT INTO folders(id, parent_id, name, position) VALUES (?,?,?,?)", id, parentId, name.trim(), position);
    }

    private static void validate(File file) {
        if (file == null || !FORMAT.equals(file.format())) {
            throw ApiException.badRequest("Not a Cassandra Studio settings backup (connections-only exports use Import)");
        }
        if (file.version() == null || file.version() > FORMAT_VERSION) {
            throw ApiException.badRequest("Settings backup version " + file.version()
                    + " is newer than this Studio supports; upgrade Studio to restore it");
        }
        for (Folder f : list(file.folders())) {
            if (f == null || f.id() == null) throw ApiException.badRequest("A folder in the file has no id");
        }
    }

    private static void requirePassphrase(String passphrase) {
        if (passphrase.length() < 8) throw ApiException.badRequest("The passphrase must have at least 8 characters");
    }

    static EncryptedSecrets encrypt(String plain, String passphrase) {
        try {
            byte[] salt = new byte[16];
            byte[] iv = new byte[12];
            RANDOM.nextBytes(salt);
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, KDF_ITERATIONS), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            Base64.Encoder b64 = Base64.getEncoder();
            return new EncryptedSecrets("PBKDF2WithHmacSHA256/AES-256-GCM", KDF_ITERATIONS, b64.encodeToString(salt),
                    b64.encodeToString(out));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot encrypt secrets: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Map<String, String>> decrypt(EncryptedSecrets s, String passphrase) {
        byte[] in;
        byte[] salt;
        try {
            in = Base64.getDecoder().decode(s.data());
            salt = Base64.getDecoder().decode(s.salt());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.badRequest("The encrypted passwords in the file are damaged");
        }
        if (s.iterations() < 10_000 || s.iterations() > 10_000_000 || in.length < 13) {
            throw ApiException.badRequest("The encrypted passwords in the file are damaged");
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(passphrase, salt, s.iterations()), new GCMParameterSpec(128, in, 0, 12));
            String json = new String(c.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
            Map<String, Map<String, String>> out = new LinkedHashMap<>();
            Json.read(json, Map.class).forEach((k, v) -> {
                Map<String, String> m = new LinkedHashMap<>();
                ((Map<String, Object>) v).forEach((name, value) -> {
                    if (SecretKeys.ALL.contains(name) && value != null) m.put(name, value.toString());
                });
                out.put((String) k, m);
            });
            return out;
        } catch (javax.crypto.AEADBadTagException e) {
            throw ApiException.badRequest("Wrong passphrase: the passwords in the file could not be decrypted");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot decrypt secrets: " + e.getMessage(), e);
        }
    }

    private static SecretKeySpec key(String passphrase, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256);
        try {
            return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(), "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static Set<String> ids(List<Map<String, Object>> rows, String col) {
        Set<String> out = new HashSet<>();
        for (Map<String, Object> r : rows) out.add(String.valueOf(r.get(col)));
        return out;
    }

    private static Counts count(List<String> ids, Set<String> existing) {
        int conflicting = (int) ids.stream().filter(existing::contains).count();
        return new Counts(ids.size() - conflicting, conflicting);
    }

    private static <T> List<T> list(List<T> l) {
        return l == null ? List.of() : l;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
