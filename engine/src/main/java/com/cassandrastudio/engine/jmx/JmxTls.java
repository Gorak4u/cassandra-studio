package com.cassandrastudio.engine.jmx;

import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.SecretKeys;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

/**
 * TLS for JMX when the connection sets "JMX over SSL": the connection's truststore and keystore
 * (JKS, PKCS12, or PEM certificates for trust), the same files as for CQL. Like the JDK's own
 * JMX client, no host name check (stubs often name 127.0.0.1 or an internal address).
 */
final class JmxTls {
    private JmxTls() {}

    static SSLSocketFactory socketFactory(ConnectionConfig.Tls tls, Map<String, String> secrets) {
        try {
            KeyStore ts = null;
            if (tls.truststorePath() != null && !tls.truststorePath().isBlank()) {
                ts = load(tls.truststorePath(), tls.truststoreType(), secrets.get(SecretKeys.TRUSTSTORE_PASSWORD));
            }
            // The connection's truststore plus the global CA bundle / OS store (Settings > Network, NFR-NET).
            TrustManager[] trust = com.cassandrastudio.engine.net.Net.trustManagers(ts);
            KeyManagerFactory kmf = null;
            if (tls.keystorePath() != null && !tls.keystorePath().isBlank()) {
                String pw = secrets.get(SecretKeys.KEYSTORE_PASSWORD);
                KeyStore ks = load(tls.keystorePath(), tls.keystoreType(), pw);
                kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, pw == null ? new char[0] : pw.toCharArray());
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf == null ? null : kmf.getKeyManagers(), trust, null);
            return ctx.getSocketFactory();
        } catch (Exception e) {
            // Exception texts from KeyStore/JSSE name files and formats, never passwords.
            throw new IllegalArgumentException("JMX TLS setup failed: " + e.getMessage(), e);
        }
    }

    private static KeyStore load(String path, String type, String password) throws Exception {
        Path p = Path.of(path);
        if (!Files.isReadable(p)) throw new IllegalArgumentException("cannot read " + path);
        String lower = path.toLowerCase();
        String t = type != null && !type.isBlank() ? type.toUpperCase()
                : lower.endsWith(".pem") || lower.endsWith(".crt") || lower.endsWith(".cer") ? "PEM"
                : lower.endsWith(".p12") || lower.endsWith(".pfx") ? "PKCS12" : "JKS";
        if (t.equals("PEM")) {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            try (InputStream in = Files.newInputStream(p)) {
                int i = 0;
                for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                    ks.setCertificateEntry("cert-" + i++, c);
                }
            }
            return ks;
        }
        KeyStore ks = KeyStore.getInstance(t);
        try (InputStream in = Files.newInputStream(p)) {
            ks.load(in, password == null ? null : password.toCharArray());
        }
        return ks;
    }
}
