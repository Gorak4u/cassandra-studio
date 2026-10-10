package com.cassandrastudio.engine.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Extra trust (NFR-NET): a global PEM bundle of corporate CA certificates and, optionally, the operating
 * system's trust store, added to what a TLS client already trusts (a connection's own truststore, or the
 * JDK's CA list when it has none). A certificate chain is accepted when any of the sources accepts it.
 */
public final class CaTrust {
    /** PEM bundles of the OS CA certificates on Linux distributions (first readable one wins). */
    static final List<String> LINUX_BUNDLES = List.of(
            "/etc/ssl/certs/ca-certificates.crt",                // Debian, Ubuntu
            "/etc/pki/tls/certs/ca-bundle.crt",                  // RHEL, Rocky, Fedora
            "/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem",
            "/etc/ssl/ca-bundle.pem",                            // SUSE
            "/etc/ssl/cert.pem");                                // Alpine

    private final List<X509Certificate> bundle;
    private final String bundlePath;
    private final boolean os;
    private final List<X509ExtendedTrustManager> extras;

    private CaTrust(String bundlePath, List<X509Certificate> bundle, boolean os, List<X509ExtendedTrustManager> extras) {
        this.bundlePath = bundlePath;
        this.bundle = bundle;
        this.os = os;
        this.extras = extras;
    }

    public static final CaTrust NONE = new CaTrust(null, List.of(), false, List.of());

    /** Loads the bundle (when set) and the OS store (when asked); fails with a clear message on a bad file. */
    public static CaTrust load(String bundlePath, boolean trustOs) {
        List<X509ExtendedTrustManager> extras = new ArrayList<>();
        List<X509Certificate> certs = List.of();
        try {
            if (bundlePath != null) {
                certs = readPem(Path.of(bundlePath));
                extras.add(trustManager(keyStore(certs)));
            }
            if (trustOs) {
                KeyStore ks = osStore();
                if (ks != null) extras.add(trustManager(ks));
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Cannot use the CA certificates: " + e.getMessage(), e);
        }
        return new CaTrust(bundlePath, certs, trustOs, List.copyOf(extras));
    }

    public boolean isEmpty() {
        return extras.isEmpty();
    }

    public String bundlePath() {
        return bundlePath;
    }

    public List<X509Certificate> bundle() {
        return bundle;
    }

    public boolean trustsOs() {
        return os;
    }

    /**
     * Trust managers for an SSLContext: {@code primary} (a connection's truststore; null = the JDK's default
     * CAs) plus the extras. Returns null when there are no extras and no primary, so callers keep the JDK default.
     */
    public TrustManager[] trustManagers(KeyStore primary) {
        try {
            if (extras.isEmpty()) return primary == null ? null : new TrustManager[] {trustManager(primary)};
            List<X509ExtendedTrustManager> all = new ArrayList<>();
            all.add(trustManager(primary)); // null: the JDK's cacerts
            all.addAll(extras);
            return new TrustManager[] {new AnyOf(all)};
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("TLS trust setup failed: " + e.getMessage(), e);
        }
    }

    /** X.509 certificates from a PEM (or DER) file; at least one, or an IllegalArgumentException naming the file. */
    public static List<X509Certificate> readPem(Path file) {
        if (!Files.isReadable(file)) throw new IllegalArgumentException("Cannot read CA bundle " + file);
        try (InputStream in = Files.newInputStream(file)) {
            List<X509Certificate> out = new ArrayList<>();
            Collection<? extends Certificate> certs = CertificateFactory.getInstance("X.509").generateCertificates(in);
            for (Certificate c : certs) {
                if (c instanceof X509Certificate x) out.add(x);
            }
            if (out.isEmpty()) throw new IllegalArgumentException("No certificates found in " + file + " (expected PEM)");
            return List.copyOf(out);
        } catch (IOException | CertificateException e) {
            throw new IllegalArgumentException("Cannot read CA bundle " + file + ": " + e.getMessage(), e);
        }
    }

    static KeyStore keyStore(List<X509Certificate> certs) throws GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        try {
            ks.load(null, null);
        } catch (IOException e) {
            throw new GeneralSecurityException(e);
        }
        int i = 0;
        for (X509Certificate c : certs) ks.setCertificateEntry("ca-" + i++, c);
        return ks;
    }

    private static X509ExtendedTrustManager trustManager(KeyStore ks) throws GeneralSecurityException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509ExtendedTrustManager x) return x;
        }
        throw new GeneralSecurityException("no X.509 trust manager");
    }

    /** The OS trust store: Windows-ROOT, the macOS keychain, or a Linux PEM bundle; null when none is found. */
    static KeyStore osStore() throws GeneralSecurityException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) return loaded(KeyStore.getInstance("Windows-ROOT"));
            if (os.contains("mac")) return loaded(KeyStore.getInstance("KeychainStore"));
        } catch (IOException e) {
            throw new GeneralSecurityException("cannot open the OS trust store: " + e.getMessage(), e);
        }
        for (String p : LINUX_BUNDLES) {
            Path f = Path.of(p);
            if (Files.isReadable(f)) return keyStore(readPem(f));
        }
        return null;
    }

    private static KeyStore loaded(KeyStore ks) throws GeneralSecurityException, IOException {
        ks.load(null, null);
        return ks;
    }

    /** Accepts a chain when any delegate does; reports the first delegate's failure otherwise. */
    static final class AnyOf extends X509ExtendedTrustManager {
        private final List<X509ExtendedTrustManager> delegates;

        AnyOf(List<X509ExtendedTrustManager> delegates) {
            this.delegates = List.copyOf(delegates);
        }

        @FunctionalInterface
        private interface Check {
            void run(X509ExtendedTrustManager tm) throws CertificateException;
        }

        private void any(Check check) throws CertificateException {
            CertificateException first = null;
            for (X509ExtendedTrustManager tm : delegates) {
                try {
                    check.run(tm);
                    return;
                } catch (CertificateException e) {
                    if (first == null) first = e;
                }
            }
            throw first != null ? first : new CertificateException("no trust source");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            any(tm -> tm.checkClientTrusted(chain, authType, socket));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            any(tm -> tm.checkServerTrusted(chain, authType, socket));
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            any(tm -> tm.checkClientTrusted(chain, authType, engine));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            any(tm -> tm.checkServerTrusted(chain, authType, engine));
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            any(tm -> tm.checkClientTrusted(chain, authType));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            any(tm -> tm.checkServerTrusted(chain, authType));
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> all = new ArrayList<>();
            for (X509TrustManager tm : delegates) all.addAll(List.of(tm.getAcceptedIssuers()));
            return all.toArray(X509Certificate[]::new);
        }
    }
}
