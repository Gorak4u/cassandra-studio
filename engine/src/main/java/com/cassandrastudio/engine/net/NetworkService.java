package com.cassandrastudio.engine.net;

import com.cassandrastudio.engine.secrets.SecretStore;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.ApiException;
import com.cassandrastudio.engine.util.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stores the global {@link NetworkSettings} (settings table, key {@code net.settings}; proxy password in the
 * secret store, key {@code net/proxyPassword}) and applies them to {@link Net} at start and on every save.
 */
public final class NetworkService {
    private static final Logger LOG = LoggerFactory.getLogger(NetworkService.class);
    static final String SETTINGS_KEY = "net.settings";
    public static final String PROXY_PASSWORD_KEY = "net/proxyPassword";

    private final Database db;
    private final SecretStore secrets;
    private final UpdateChecker updates;

    /** What the settings page shows: the settings, whether a password is stored, and what is in effect. */
    public record View(NetworkSettings settings, boolean proxyPasswordSet, String systemProxy, CaBundleInfo caBundle,
                       boolean osTrustAvailable, String warning) {}

    public record CaBundleInfo(String path, int certificates, List<String> subjects) {}

    public NetworkService(Database db, SecretStore secrets, UpdateChecker updates) {
        this.db = db;
        this.secrets = secrets;
        this.updates = updates;
    }

    /** Loads and applies the stored settings; a broken CA bundle path is logged and skipped, not fatal. */
    public View start() {
        NetworkSettings s = load();
        String pw = secrets.get(PROXY_PASSWORD_KEY).orElse(null);
        try {
            Net.apply(s, pw);
            return view(null);
        } catch (IllegalArgumentException e) {
            LOG.warn("Network settings: {}; starting without the extra CA bundle", e.getMessage());
            Net.apply(new NetworkSettings(s.offline(), s.checkForUpdates(), s.proxyMode(), s.proxyHost(), s.proxyPort(),
                    s.proxyUsername(), s.noProxy(), s.proxyNodeHttp(), null, s.trustOsStore()), pw);
            return view(e.getMessage());
        }
    }

    public NetworkSettings load() {
        List<Map<String, Object>> rows = db.query("SELECT value FROM settings WHERE key=?", SETTINGS_KEY);
        if (rows.isEmpty()) return NetworkSettings.DEFAULTS;
        try {
            return Json.read((String) rows.get(0).get("value"), NetworkSettings.class);
        } catch (IllegalArgumentException e) {
            LOG.warn("Ignoring unreadable network settings: {}", e.getMessage());
            return NetworkSettings.DEFAULTS;
        }
    }

    public View view() {
        return view(null);
    }

    private View view(String warning) {
        NetworkSettings s = Net.settings();
        CaTrust t = Net.trust();
        CaBundleInfo ca = null;
        if (t.bundlePath() != null) {
            List<String> subjects = new ArrayList<>();
            for (X509Certificate c : t.bundle()) {
                if (subjects.size() < 20) subjects.add(c.getSubjectX500Principal().getName());
            }
            ca = new CaBundleInfo(t.bundlePath(), t.bundle().size(), subjects);
        }
        return new View(s, secrets.get(PROXY_PASSWORD_KEY).isPresent(), Net.resolver().describeSystem(), ca,
                osTrustAvailable(), warning);
    }

    /**
     * Validates, stores and applies. {@code proxyPassword}: null = unchanged, "" = remove, else the new password.
     * 400 with every problem listed when invalid (including an unreadable CA bundle).
     */
    public View save(NetworkSettings s, String proxyPassword) {
        List<String> problems = new ArrayList<>(s.problems());
        if (s.caBundlePath() != null) {
            try {
                CaTrust.readPem(Path.of(s.caBundlePath()));
            } catch (IllegalArgumentException e) { // incl. InvalidPathException
                problems.add(e.getMessage());
            }
        }
        if (!problems.isEmpty()) throw new ApiException(400, "bad_request", String.join("; ", problems));
        String pw = proxyPassword == null ? secrets.get(PROXY_PASSWORD_KEY).orElse(null)
                : proxyPassword.isEmpty() ? null : proxyPassword;
        try {
            Net.apply(s, pw); // first: a failure leaves the stored settings untouched
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        db.update("INSERT OR REPLACE INTO settings(key, value) VALUES (?, ?)", SETTINGS_KEY, Json.write(s));
        if (proxyPassword != null) {
            if (proxyPassword.isEmpty()) secrets.delete(PROXY_PASSWORD_KEY);
            else secrets.put(PROXY_PASSWORD_KEY, proxyPassword);
        }
        updates.invalidate();
        return view(null);
    }

    public UpdateChecker.UpdateStatus updates(boolean force) {
        return updates.check(Net.settings(), force);
    }

    static boolean osTrustAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win") || os.contains("mac")) return true;
        return CaTrust.LINUX_BUNDLES.stream().anyMatch(p -> Files.isReadable(Path.of(p)));
    }
}
