package com.cassandrastudio.engine.server;

import com.cassandrastudio.engine.store.Database;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Named API tokens for Studio Server (SRV-1): one or more per team member, created and revoked with
 * {@code studio-server token ...}. Only a SHA-256 hash is stored (the token is 256 random bits, so a
 * slow hash adds nothing); the token itself is shown once. The token identifies the user; there are no
 * roles (owner decision Q-1).
 */
public final class TokenStore {
    /** Tokens start with this, so secret scanners and people recognise them. */
    public static final String PREFIX = "cst_";
    /** How long a successful lookup is reused before the store is asked again (revocation delay). */
    static final long CACHE_MS = 5_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Token(String id, String user, String label, String createdAt, String lastUsedAt, String revokedAt) {
        public boolean active() {
            return revokedAt == null;
        }
    }

    /** A new token: {@code secret} is shown once and never stored. */
    public record Created(Token token, String secret) {}

    private record Cached(String user, String id, long atMs) {}

    private final Database db;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> lastUsedWritten = new ConcurrentHashMap<>();

    public TokenStore(Database db) {
        this.db = db;
    }

    public Created add(String user, String label) {
        if (user == null || user.isBlank()) throw new IllegalArgumentException("A user name is required");
        String u = user.strip();
        if (u.length() > 200 || u.chars().anyMatch(c -> c < 0x20)) throw new IllegalArgumentException("Invalid user name");
        if (u.startsWith("schedule:")) throw new IllegalArgumentException("User names starting with \"schedule:\" are reserved");
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        String id = UUID.randomUUID().toString().substring(0, 8);
        String now = Instant.now().toString();
        db.update("INSERT INTO server_tokens(id, user_name, label, token_hash, created_at) VALUES (?,?,?,?,?)",
                id, u, label, hash(secret), now);
        return new Created(new Token(id, u, label, now, null, null), secret);
    }

    public List<Token> list() {
        List<Token> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT * FROM server_tokens ORDER BY user_name, created_at")) out.add(fromRow(r));
        return out;
    }

    /**
     * Revokes the token with this id, or every active token of this user; returns how many were revoked.
     */
    public int revoke(String idOrUser) {
        String now = Instant.now().toString();
        int n = db.update("UPDATE server_tokens SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL", now, idOrUser);
        if (n == 0) n = db.update("UPDATE server_tokens SET revoked_at = ? WHERE user_name = ? AND revoked_at IS NULL", now, idOrUser);
        cache.clear();
        return n;
    }

    /** The user of an active token, or null. */
    public String authenticate(String secret) {
        if (secret == null || !secret.startsWith(PREFIX) || secret.length() > 200) return null;
        String h = hash(secret);
        long now = System.currentTimeMillis();
        Cached c = cache.get(h);
        if (c == null || now - c.atMs() > CACHE_MS) {
            List<Map<String, Object>> rows = db.query(
                    "SELECT id, user_name FROM server_tokens WHERE token_hash = ? AND revoked_at IS NULL", h);
            if (rows.isEmpty()) {
                cache.remove(h);
                return null;
            }
            c = new Cached((String) rows.get(0).get("user_name"), (String) rows.get(0).get("id"), now);
            cache.put(h, c);
        }
        touch(c.id(), now);
        return c.user();
    }

    /** Records last use at most once a minute per token. */
    private void touch(String id, long now) {
        Long last = lastUsedWritten.get(id);
        if (last != null && now - last < 60_000) return;
        lastUsedWritten.put(id, now);
        try {
            db.update("UPDATE server_tokens SET last_used_at = ? WHERE id = ?", Instant.ofEpochMilli(now).toString(), id);
        } catch (RuntimeException ignored) {
            // bookkeeping only
        }
    }

    static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Token fromRow(Map<String, Object> r) {
        return new Token((String) r.get("id"), (String) r.get("user_name"), (String) r.get("label"), (String) r.get("created_at"),
                (String) r.get("last_used_at"), (String) r.get("revoked_at"));
    }
}
