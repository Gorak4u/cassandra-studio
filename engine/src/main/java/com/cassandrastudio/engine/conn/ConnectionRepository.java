package com.cassandrastudio.engine.conn;

import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.SecretKeys;
import com.cassandrastudio.engine.model.Folder;
import com.cassandrastudio.engine.secrets.SecretStore;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.ApiException;
import com.cassandrastudio.engine.util.Json;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Saved folders and connections (CON-1, CON-2, CON-8, CON-9). */
public final class ConnectionRepository {
    public static final int EXPORT_FORMAT_VERSION = 1;

    private final Database db;
    private final SecretStore secrets;
    /** Which secrets each connection has; secrets only change through this class, so the cache stays exact. */
    private final Map<String, Map<String, Boolean>> secretFlags = new java.util.concurrent.ConcurrentHashMap<>();

    public ConnectionRepository(Database db, SecretStore secrets) {
        this.db = db;
        this.secrets = secrets;
    }

    // ---- folders --------------------------------------------------------

    public List<Folder> folders() {
        List<Folder> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT id, parent_id, name, position FROM folders ORDER BY position, name")) {
            out.add(new Folder((String) r.get("id"), (String) r.get("parent_id"), (String) r.get("name"),
                    ((Number) r.get("position")).intValue()));
        }
        return out;
    }

    public Folder createFolder(String parentId, String name) {
        requireName(name);
        if (parentId != null) requireFolder(parentId);
        Folder f = new Folder(UUID.randomUUID().toString(), parentId, name.trim(), nextPosition("folders"));
        db.update("INSERT INTO folders(id, parent_id, name, position) VALUES (?,?,?,?)", f.id(), f.parentId(), f.name(), f.position());
        return f;
    }

    public Folder updateFolder(String id, String parentId, String name, Integer position) {
        Folder existing = requireFolder(id);
        if (parentId != null) {
            requireFolder(parentId);
            if (isDescendant(parentId, id)) throw ApiException.badRequest("A folder cannot be moved into itself");
        }
        Folder f = new Folder(id, parentId, name == null ? existing.name() : requireName(name),
                position == null ? existing.position() : position);
        db.update("UPDATE folders SET parent_id=?, name=?, position=? WHERE id=?", f.parentId(), f.name(), f.position(), id);
        return f;
    }

    /** Deleting a folder deletes its sub-folders; connections in them move to the root. */
    public void deleteFolder(String id) {
        requireFolder(id);
        db.update("DELETE FROM folders WHERE id=?", id);
    }

    private boolean isDescendant(String candidate, String ancestor) {
        String cur = candidate;
        Map<String, String> parents = new HashMap<>();
        for (Folder f : folders()) parents.put(f.id(), f.parentId());
        while (cur != null) {
            if (cur.equals(ancestor)) return true;
            cur = parents.get(cur);
        }
        return false;
    }

    private Folder requireFolder(String id) {
        return folders().stream().filter(f -> f.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Folder " + id));
    }

    // ---- connections ----------------------------------------------------

    public List<ConnectionConfig> list() {
        List<ConnectionConfig> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT folder_id, config_json FROM connections ORDER BY position, name")) {
            out.add(fromRow(r));
        }
        return out;
    }

    public Optional<ConnectionConfig> find(String id) {
        List<Map<String, Object>> rows = db.query("SELECT folder_id, config_json FROM connections WHERE id=?", id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(fromRow(rows.get(0)));
    }

    public ConnectionConfig get(String id) {
        return find(id).orElseThrow(() -> ApiException.notFound("Connection " + id));
    }

    /**
     * Creates or updates a connection. {@code newSecrets}: null value = leave
     * unchanged, empty string = remove, anything else = store.
     */
    public ConnectionConfig save(ConnectionConfig cfg, Map<String, String> newSecrets) {
        validate(cfg);
        boolean create = cfg.id() == null || find(cfg.id()).isEmpty();
        ConnectionConfig toSave = (cfg.id() == null ? cfg.withId(UUID.randomUUID().toString()) : cfg).withSecretsSet(null);
        String json = Json.write(toSave);
        String now = Instant.now().toString();
        if (create) {
            db.update("INSERT INTO connections(id, folder_id, name, config_json, position, updated_at) VALUES (?,?,?,?,?,?)",
                    toSave.id(), toSave.folderId(), toSave.name(), json, nextPosition("connections"), now);
        } else {
            db.update("UPDATE connections SET folder_id=?, name=?, config_json=?, updated_at=? WHERE id=?",
                    toSave.folderId(), toSave.name(), json, now, toSave.id());
        }
        if (newSecrets != null) {
            secretFlags.remove(toSave.id());
            newSecrets.forEach((name, value) -> {
                if (!SecretKeys.ALL.contains(name)) throw ApiException.badRequest("Unknown secret '" + name + "'");
                if (value == null) return;
                String key = SecretKeys.storeKey(toSave.id(), name);
                if (value.isEmpty()) secrets.delete(key);
                else secrets.put(key, value);
            });
        }
        return get(toSave.id());
    }

    public ConnectionConfig cloneConnection(String id) {
        ConnectionConfig src = get(id);
        Map<String, String> copied = new HashMap<>();
        for (String s : SecretKeys.ALL) secret(id, s).ifPresent(v -> copied.put(s, v));
        return save(src.withId(null).withName(src.name() + " (copy)"), copied);
    }

    public void delete(String id) {
        get(id);
        db.update("DELETE FROM connections WHERE id=?", id);
        for (String s : SecretKeys.ALL) secrets.delete(SecretKeys.storeKey(id, s));
        secretFlags.remove(id);
    }

    public Optional<String> secret(String connectionId, String name) {
        return secrets.get(SecretKeys.storeKey(connectionId, name));
    }

    public String secretStoreDescription() {
        return secrets.description();
    }

    // ---- import / export (CON-9) ----------------------------------------

    /** Folders + connections, never secrets. */
    public Map<String, Object> export() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", "cassandra-studio-connections");
        out.put("version", EXPORT_FORMAT_VERSION);
        out.put("exportedAt", Instant.now().toString());
        out.put("folders", folders());
        out.put("connections", list().stream().map(c -> c.withSecretsSet(null)).toList());
        return out;
    }

    public record ImportFile(String format, Integer version, List<Folder> folders, List<ConnectionConfig> connections) {}

    public record ImportResult(int folders, int connections) {}

    /** Imports as NEW items (fresh ids) so an import never overwrites existing ones. */
    public ImportResult importFile(ImportFile file) {
        if (!"cassandra-studio-connections".equals(file.format())) {
            throw ApiException.badRequest("Not a Cassandra Studio connections file");
        }
        if (file.version() == null || file.version() > EXPORT_FORMAT_VERSION) {
            throw ApiException.badRequest("Connections file version " + file.version() + " is newer than this Studio supports");
        }
        Map<String, String> idMap = new HashMap<>();
        int[] counts = new int[2];
        db.transaction(() -> {
            List<Folder> pending = new ArrayList<>(file.folders() == null ? List.of() : file.folders());
            // Parents first: keep passing over the list until every folder is placed.
            boolean progress = true;
            while (!pending.isEmpty() && progress) {
                progress = false;
                for (var it = pending.iterator(); it.hasNext(); ) {
                    Folder f = it.next();
                    if (f.parentId() != null && !idMap.containsKey(f.parentId())) continue;
                    Folder created = createFolder(f.parentId() == null ? null : idMap.get(f.parentId()), f.name());
                    idMap.put(f.id(), created.id());
                    counts[0]++;
                    it.remove();
                    progress = true;
                }
            }
            for (ConnectionConfig c : file.connections() == null ? List.<ConnectionConfig>of() : file.connections()) {
                String folder = c.folderId() == null ? null : idMap.get(c.folderId());
                save(c.withId(null).withFolder(folder).withSecretsSet(null), null);
                counts[1]++;
            }
        });
        return new ImportResult(counts[0], counts[1]);
    }

    // ---- helpers ---------------------------------------------------------

    /** The folder_id column wins over the JSON: deleting a folder clears the column (ON DELETE SET NULL). */
    private ConnectionConfig fromRow(Map<String, Object> r) {
        ConnectionConfig c = Json.read((String) r.get("config_json"), ConnectionConfig.class);
        return withSecretFlags(c.withFolder((String) r.get("folder_id")));
    }

    private ConnectionConfig withSecretFlags(ConnectionConfig c) {
        return c.withSecretsSet(secretFlags.computeIfAbsent(c.id(), id -> {
            Map<String, Boolean> set = new LinkedHashMap<>();
            for (String s : SecretKeys.ALL) set.put(s, secrets.get(SecretKeys.storeKey(id, s)).isPresent());
            return java.util.Collections.unmodifiableMap(set);
        }));
    }

    private void validate(ConnectionConfig c) {
        requireName(c.name());
        if (c.contactPoints().isEmpty()) throw ApiException.badRequest("At least one contact point is required");
        for (String cp : c.contactPoints()) HostPort.parse(cp, ConnectionConfig.DEFAULT_CQL_PORT);
        if (c.folderId() != null) requireFolder(c.folderId());
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) throw ApiException.badRequest("Name is required");
        if (name.length() > 200) throw ApiException.badRequest("Name is too long");
        return name.trim();
    }

    private int nextPosition(String table) {
        Object max = db.query("SELECT COALESCE(MAX(position), -1) + 1 AS p FROM " + table).get(0).get("p");
        return ((Number) max).intValue();
    }
}
