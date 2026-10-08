package com.cassandrastudio.engine.store;

import com.cassandrastudio.engine.util.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Saved CQL scripts in folders (CQL-8). Shared by all connections. */
public final class ScriptRepository {
    private static final int MAX_CONTENT = 1_000_000;

    public record Script(String id, String folder, String name, String content, String updatedAt) {}

    private final Database db;

    public ScriptRepository(Database db) {
        this.db = db;
    }

    /** Without content, for the list view. */
    public List<Script> list() {
        List<Script> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT id, folder, name, updated_at FROM saved_scripts ORDER BY folder, name")) {
            out.add(new Script((String) r.get("id"), (String) r.get("folder"), (String) r.get("name"), null,
                    (String) r.get("updated_at")));
        }
        return out;
    }

    public Script get(String id) {
        List<Map<String, Object>> rows = db.query("SELECT * FROM saved_scripts WHERE id = ?", id);
        if (rows.isEmpty()) throw ApiException.notFound("Script " + id);
        Map<String, Object> r = rows.get(0);
        return new Script((String) r.get("id"), (String) r.get("folder"), (String) r.get("name"),
                (String) r.get("content"), (String) r.get("updated_at"));
    }

    public Script save(String id, String folder, String name, String content) {
        if (name == null || name.isBlank()) throw ApiException.badRequest("Script name is required");
        if (content == null) content = "";
        if (content.length() > MAX_CONTENT) throw ApiException.badRequest("Script is larger than 1 MB");
        String f = folder == null ? "" : folder.strip().replaceAll("^/+|/+$", "");
        String now = Instant.now().toString();
        if (id == null) {
            String newId = UUID.randomUUID().toString();
            db.update("INSERT INTO saved_scripts(id, folder, name, content, updated_at) VALUES (?,?,?,?,?)",
                    newId, f, name.strip(), content, now);
            return get(newId);
        }
        if (db.update("UPDATE saved_scripts SET folder = ?, name = ?, content = ?, updated_at = ? WHERE id = ?",
                f, name.strip(), content, now, id) == 0) {
            throw ApiException.notFound("Script " + id);
        }
        return get(id);
    }

    public void delete(String id) {
        if (db.update("DELETE FROM saved_scripts WHERE id = ?", id) == 0) throw ApiException.notFound("Script " + id);
    }
}
