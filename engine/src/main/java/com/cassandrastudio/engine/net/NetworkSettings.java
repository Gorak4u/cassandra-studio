package com.cassandrastudio.engine.net;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Global network settings (NFR-NET, NFR-UPD), stored in the settings table under {@code net.settings}.
 * The proxy password is not here: it lives in the secret store ({@link NetworkService#PROXY_PASSWORD_KEY}).
 *
 * <ul>
 *   <li>{@code offline}: air-gapped mode. Nothing leaves the machine except traffic to the user's clusters.</li>
 *   <li>{@code checkForUpdates}: look up the latest release on GitHub when the UI starts (default on).</li>
 *   <li>{@code proxyMode}: how Studio's own HTTP(S) calls (the update check) reach the internet.</li>
 *   <li>{@code proxyNodeHttp}: also send HTTP to cluster nodes (jmx_exporter) through the proxy. Off by
 *       default: node addresses are internal and normally must not go through a corporate proxy.</li>
 *   <li>{@code caBundlePath}: PEM file of extra CA certificates trusted for CQL TLS, JMX TLS and HTTPS,
 *       in addition to each connection's own truststore.</li>
 *   <li>{@code trustOsStore}: also trust the operating system's CA certificates.</li>
 * </ul>
 */
public record NetworkSettings(
        boolean offline,
        Boolean checkForUpdates,
        ProxyMode proxyMode,
        String proxyHost,
        Integer proxyPort,
        String proxyUsername,
        List<String> noProxy,
        boolean proxyNodeHttp,
        String caBundlePath,
        boolean trustOsStore) {

    /** NONE: always direct. SYSTEM: HTTPS_PROXY / HTTP_PROXY / NO_PROXY or the Java proxy properties. MANUAL: below. */
    public enum ProxyMode { NONE, SYSTEM, MANUAL }

    public static final NetworkSettings DEFAULTS = new NetworkSettings(false, true, ProxyMode.SYSTEM, null, null, null,
            List.of(), false, null, false);

    public NetworkSettings {
        checkForUpdates = checkForUpdates == null || checkForUpdates;
        proxyMode = proxyMode == null ? ProxyMode.SYSTEM : proxyMode;
        proxyHost = blankToNull(proxyHost);
        proxyUsername = blankToNull(proxyUsername);
        caBundlePath = blankToNull(caBundlePath);
        List<String> np = new ArrayList<>();
        if (noProxy != null) {
            for (String e : noProxy) {
                if (e == null) continue;
                for (String part : e.split("[,\\s]+")) {
                    if (!part.isBlank()) np.add(part.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        noProxy = List.copyOf(np);
    }

    /** Whether the update check may run: enabled and not in offline mode. */
    public boolean updateCheckAllowed() {
        return checkForUpdates && !offline;
    }

    /** 400-style messages for invalid input; empty when valid. Does not touch the file system. */
    public List<String> problems() {
        List<String> p = new ArrayList<>();
        if (proxyMode == ProxyMode.MANUAL) {
            if (proxyHost == null) p.add("Proxy host is required for a manual proxy");
            else if (!proxyHost.matches("[A-Za-z0-9._:\\-\\[\\]]+")) p.add("Proxy host '" + proxyHost + "' is not a host name or IP address");
            if (proxyPort == null || proxyPort < 1 || proxyPort > 65535) p.add("Proxy port must be 1-65535");
        }
        for (String e : noProxy) {
            if (!e.matches("[a-z0-9.*_:/\\-\\[\\]]+")) p.add("No-proxy entry '" + e + "' is not a host, domain, IP or CIDR");
        }
        return p;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
