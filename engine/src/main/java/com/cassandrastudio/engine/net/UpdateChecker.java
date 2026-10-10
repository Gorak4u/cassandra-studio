package com.cassandrastudio.engine.net;

import com.cassandrastudio.engine.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;

/**
 * Update check (NFR-UPD): asks GitHub for the latest release of Cassandra Studio over HTTPS (proxy and CA
 * settings apply, 5 s timeout) and compares it with the running version. Only ever runs when the UI asks
 * and the setting allows it; never downloads anything: the result is a link to the release page.
 * The request carries no identifier: a fixed User-Agent and nothing else.
 */
public final class UpdateChecker {
    public static final URI GITHUB_LATEST = URI.create("https://api.github.com/repos/Gorak4u/cassandra-studio/releases/latest");
    public static final String RELEASES_PAGE = "https://github.com/Gorak4u/cassandra-studio/releases";
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    static final Duration CACHE = Duration.ofHours(6);
    static final int MAX_NOTES = 8000;

    /**
     * state: "ok" (checked; see updateAvailable), "disabled" (setting off), "offline" (offline mode),
     * "error" (could not check; see message). latest/url/notes are null unless a release was found.
     */
    public record UpdateStatus(String state, String current, String latest, String name, String url, String notes,
                               String publishedAt, boolean updateAvailable, String checkedAt, String message) {}

    private final URI endpoint;
    private final String current;
    private final Function<Duration, HttpClient> clients;
    private final Clock clock;
    private UpdateStatus cached;
    private Instant cachedAt;

    public UpdateChecker(String current) {
        this(GITHUB_LATEST, current, Net::internetClient, Clock.systemUTC());
    }

    UpdateChecker(URI endpoint, String current, Function<Duration, HttpClient> clients, Clock clock) {
        this.endpoint = endpoint;
        this.current = current;
        this.clients = clients;
        this.clock = clock;
    }

    /** The cached result when fresh (6 h), else a new check. {@code force} always checks. */
    public synchronized UpdateStatus check(NetworkSettings settings, boolean force) {
        if (settings.offline()) return status("offline", "Offline mode is on: Studio makes no calls outside your clusters.");
        if (!settings.checkForUpdates()) return status("disabled", "Update check is turned off in Settings.");
        Instant now = clock.instant();
        if (!force && cached != null && cachedAt.plus(CACHE).isAfter(now)) return cached;
        UpdateStatus s = fetch(now);
        if (!"error".equals(s.state())) {
            cached = s;
            cachedAt = now;
        }
        return s;
    }

    /** Drops the cached result (after a settings change). */
    public synchronized void invalidate() {
        cached = null;
    }

    private UpdateStatus status(String state, String message) {
        return new UpdateStatus(state, current, null, null, null, null, null, false, null, message);
    }

    private UpdateStatus fetch(Instant now) {
        String checkedAt = now.toString();
        HttpResponse<String> r;
        try {
            HttpRequest req = HttpRequest.newBuilder(endpoint)
                    .timeout(TIMEOUT)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "CassandraStudio-update-check")
                    .GET().build();
            r = clients.apply(TIMEOUT).send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            return error(checkedAt, "No answer from " + endpoint.getHost() + " within " + TIMEOUT.toSeconds()
                    + " s (behind a proxy? set it in Settings > Network)");
        } catch (javax.net.ssl.SSLHandshakeException e) {
            return error(checkedAt, "TLS check of " + endpoint.getHost() + " failed: " + rootMessage(e)
                    + " (a TLS-inspecting proxy needs its CA in Settings > Network > CA bundle)");
        } catch (IOException e) {
            return error(checkedAt, "Cannot reach " + endpoint.getHost() + ": " + rootMessage(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error(checkedAt, "Interrupted");
        }
        if (r.statusCode() == 404) {
            return new UpdateStatus("ok", current, null, null, RELEASES_PAGE, null, null, false, checkedAt,
                    "No release has been published yet.");
        }
        if (r.statusCode() == 407) return error(checkedAt, "The proxy asks for authentication: set the proxy user and password");
        if (r.statusCode() == 403 || r.statusCode() == 429) {
            return error(checkedAt, "GitHub refused the request (HTTP " + r.statusCode() + ", rate limit?). Try again later.");
        }
        if (r.statusCode() != 200) return error(checkedAt, "Unexpected answer from GitHub: HTTP " + r.statusCode());
        return parse(r.body(), checkedAt);
    }

    UpdateStatus parse(String body, String checkedAt) {
        JsonNode n;
        try {
            n = Json.MAPPER.readTree(body);
        } catch (IOException e) {
            return error(checkedAt, "GitHub's answer is not JSON");
        }
        String tag = n.path("tag_name").asText(null);
        SemVer latest = SemVer.parse(tag);
        if (latest == null) return error(checkedAt, "Latest release tag '" + tag + "' is not a version");
        SemVer cur = SemVer.parse(current);
        String url = n.path("html_url").asText("");
        // Only ever link to this project's own release pages.
        if (!url.startsWith(RELEASES_PAGE + "/")) url = RELEASES_PAGE;
        String notes = n.path("body").asText(null);
        if (notes != null && notes.length() > MAX_NOTES) notes = notes.substring(0, MAX_NOTES) + "\n…";
        boolean newer = cur != null && latest.compareTo(cur) > 0;
        String message = cur == null ? "This is a development build (" + current + "): version not compared."
                : newer ? "Version " + latest + " is available." : "You have the latest version.";
        return new UpdateStatus("ok", current, latest.toString(), n.path("name").asText(null), url, notes,
                n.path("published_at").asText(null), newer, checkedAt, message);
    }

    private UpdateStatus error(String checkedAt, String message) {
        return new UpdateStatus("error", current, null, null, RELEASES_PAGE, null, null, false, checkedAt, message);
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage();
        return m == null || m.isBlank() ? c.getClass().getSimpleName() : m;
    }
}
