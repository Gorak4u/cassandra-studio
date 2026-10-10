package com.cassandrastudio.engine.secrets;

import com.cassandrastudio.engine.net.CaTrust;
import com.cassandrastudio.engine.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;

/**
 * HashiCorp Vault KV version 2 as Studio Server's secret store (SRV-3), token authentication. Each Studio
 * secret is one Vault secret at {@code <mount>/data/<path>/<name>} holding {@code {"value": ...}};
 * deleting removes all its versions (metadata delete). Nothing is cached: Vault stays the only copy.
 */
public final class VaultSecretStore implements SecretStore {
    private final URI address;
    private final String token;
    private final String mount;
    private final String path;
    private final String namespace;
    private final HttpClient http;

    public VaultSecretStore(String address, String token, String mount, String path, String namespace, String caFile) {
        if (address == null || address.isBlank()) throw new IllegalArgumentException("vault.address is required");
        if (token == null || token.isBlank()) throw new IllegalArgumentException("A Vault token is required (vault.token or vault.tokenFile)");
        this.address = URI.create(address.endsWith("/") ? address.substring(0, address.length() - 1) : address);
        this.token = token;
        this.mount = trim(mount == null ? "secret" : mount);
        this.path = trim(path == null ? "cassandra-studio" : path);
        this.namespace = namespace;
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        if (caFile != null) {
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, CaTrust.load(caFile, false).trustManagers(null), null);
                b.sslContext(ctx);
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalArgumentException("Cannot use vault.caFile: " + e.getMessage(), e);
            }
        }
        this.http = b.build();
    }

    private static String trim(String s) {
        return s.replaceAll("^/+|/+$", "");
    }

    /** Checks the address and token (token self-lookup); throws with Vault's reason when they do not work. */
    public void check() {
        HttpResponse<String> r = send(request("/v1/auth/token/lookup-self").GET());
        if (r.statusCode() != 200) throw new IllegalStateException("Vault refused the token: HTTP " + r.statusCode() + " " + errors(r));
    }

    private String secretPath(String kind, String name) {
        String enc = Arrays.stream(name.split("/")).map(p -> URLEncoder.encode(p, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
        return "/v1/" + mount + "/" + kind + "/" + path + "/" + enc;
    }

    @Override
    public void put(String name, String secret) {
        String body = Json.write(Map.of("data", Map.of("value", secret)));
        HttpResponse<String> r = send(request(secretPath("data", name)).POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json"));
        if (r.statusCode() / 100 != 2) throw new IllegalStateException("Vault write failed: HTTP " + r.statusCode() + " " + errors(r));
    }

    @Override
    public Optional<String> get(String name) {
        HttpResponse<String> r = send(request(secretPath("data", name)).GET());
        if (r.statusCode() == 404) return Optional.empty();
        if (r.statusCode() != 200) throw new IllegalStateException("Vault read failed: HTTP " + r.statusCode() + " " + errors(r));
        try {
            JsonNode v = Json.MAPPER.readTree(r.body()).path("data").path("data").path("value");
            return v.isMissingNode() || v.isNull() ? Optional.empty() : Optional.of(v.asText());
        } catch (IOException e) {
            throw new IllegalStateException("Vault answered with invalid JSON", e);
        }
    }

    @Override
    public void delete(String name) {
        HttpResponse<String> r = send(request(secretPath("metadata", name)).DELETE());
        if (r.statusCode() / 100 != 2 && r.statusCode() != 404) {
            throw new IllegalStateException("Vault delete failed: HTTP " + r.statusCode() + " " + errors(r));
        }
    }

    @Override
    public String description() {
        return "HashiCorp Vault KV v2 (" + address + ", " + mount + "/" + path + ")";
    }

    private HttpRequest.Builder request(String p) {
        HttpRequest.Builder b = HttpRequest.newBuilder(address.resolve(p)).timeout(Duration.ofSeconds(15)).header("X-Vault-Token", token);
        if (namespace != null && !namespace.isBlank()) b.header("X-Vault-Namespace", namespace);
        return b;
    }

    private HttpResponse<String> send(HttpRequest.Builder b) {
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Vault at " + address + " is not reachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling Vault", e);
        }
    }

    private static String errors(HttpResponse<String> r) {
        try {
            JsonNode e = Json.MAPPER.readTree(r.body()).path("errors");
            return e.isArray() ? e.toString() : "";
        } catch (IOException | RuntimeException ex) {
            return "";
        }
    }
}
