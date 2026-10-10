package com.cassandrastudio.engine.net;

import com.cassandrastudio.engine.model.ConnectionConfig.ProxyType;
import com.cassandrastudio.engine.util.ApiException;
import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

/**
 * The process-wide network policy (NFR-NET, NFR-SEC): the applied {@link NetworkSettings}, the proxy for
 * Studio's own HTTP(S) calls, and the extra CA trust. Set by {@link NetworkService} at start and on every
 * save; read by every engine HTTP client and TLS setup (CQL, JMX, HTTPS). Until then: direct, no extra trust.
 */
public final class Net {
    private record State(NetworkSettings settings, String proxyPassword, CaTrust trust) {}

    private static volatile State state = new State(NetworkSettings.DEFAULTS, null, CaTrust.NONE);

    static {
        // The JDK refuses Basic proxy authentication for HTTPS tunnels unless this is cleared; corporate
        // proxies commonly use Basic. Only affects requests that go through a proxy that asks for it.
        if (System.getProperty("jdk.http.auth.tunneling.disabledSchemes") == null) {
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        }
    }

    private Net() {}

    /** Applies settings (loads the CA bundle first, so a bad file leaves the previous state in place). */
    public static void apply(NetworkSettings settings, String proxyPassword) {
        CaTrust trust = CaTrust.load(settings.caBundlePath(), settings.trustOsStore());
        state = new State(settings, proxyPassword, trust);
    }

    /** Back to defaults (tests). */
    public static void reset() {
        state = new State(NetworkSettings.DEFAULTS, null, CaTrust.NONE);
    }

    public static NetworkSettings settings() {
        return state.settings();
    }

    public static CaTrust trust() {
        return state.trust();
    }

    public static ProxyResolver resolver() {
        State s = state;
        return ProxyResolver.live(s.settings(), s.proxyPassword());
    }

    /**
     * Trust managers for a cluster TLS context (CQL, JMX): the connection's truststore (null = JDK default)
     * plus the global CA bundle and OS store. Null when nothing is configured (keep the JDK default).
     */
    public static TrustManager[] trustManagers(KeyStore connectionTruststore) {
        return state.trust().trustManagers(connectionTruststore);
    }

    /** Throws 409 "offline" when offline mode is on: for any call that would leave for the internet. */
    public static void requireOnline(String what) {
        if (state.settings().offline()) {
            throw new ApiException(409, "offline", what + " is off: offline mode is on (Settings > Network)");
        }
    }

    /**
     * An HTTP client for calls to the internet (not to clusters): offline mode refuses, the proxy settings
     * apply, and HTTPS trusts the JDK's CAs plus the global CA bundle (and OS store when enabled).
     */
    public static HttpClient internetClient(Duration connectTimeout) {
        requireOnline("Internet access");
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, state.trust().trustManagers(null), null);
            return HttpClient.newBuilder()
                    .connectTimeout(connectTimeout)
                    .proxy(INTERNET)
                    .authenticator(AUTH)
                    .sslContext(ctx)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TLS setup failed: " + e.getMessage(), e);
        }
    }

    /** Proxy selection for HTTP to cluster nodes (jmx_exporter): direct unless the user opted in. */
    public static ProxySelector nodeHttpProxy() {
        return NODES;
    }

    /** Answers proxy authentication challenges with the configured proxy user and password. */
    public static Authenticator authenticator() {
        return AUTH;
    }

    private static final ProxySelector INTERNET = new Selector(false);
    private static final ProxySelector NODES = new Selector(true);

    /** Reads the current state on every request, so cached HTTP clients follow settings changes. */
    private static final class Selector extends ProxySelector {
        private final boolean nodes;

        Selector(boolean nodes) {
            this.nodes = nodes;
        }

        @Override
        public List<Proxy> select(URI uri) {
            if (nodes && !state.settings().proxyNodeHttp()) return List.of(Proxy.NO_PROXY);
            Optional<ProxyEndpoint> p = resolver().forUri(uri);
            if (p.isEmpty() || p.get().type() != ProxyType.HTTP) return List.of(Proxy.NO_PROXY);
            return List.of(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(p.get().host(), p.get().port())));
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            // nothing to fall back to: the error reaches the caller
        }
    }

    private static final Authenticator AUTH = new Authenticator() {
        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            if (getRequestorType() != RequestorType.PROXY) return null;
            URI uri = getRequestingURL() == null ? null : URI.create(getRequestingURL().toString());
            if (uri == null) return null;
            Optional<ProxyEndpoint> p = resolver().forUri(uri);
            if (p.isEmpty() || !p.get().hasCredentials() || (getRequestingHost() != null && !p.get().host().equalsIgnoreCase(getRequestingHost()))) return null;
            String pw = p.get().password();
            return new PasswordAuthentication(p.get().username(), pw == null ? new char[0] : pw.toCharArray());
        }
    };
}
