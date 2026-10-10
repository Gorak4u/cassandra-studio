package com.cassandrastudio.engine.net;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * A throw-away CA and a server certificate it signed, made with the JDK's keytool (like test-env/make-certs.sh):
 * ca.pem (the bundle to trust), server.p12 (key + chain; SAN localhost, updates.example.test, 127.0.0.1),
 * and other-ca.pem, an unrelated CA.
 */
public final class TestCerts {
    public static final String PASSWORD = "changeit";
    public final Path dir;
    public final Path caPem;
    public final Path otherCaPem;
    public final Path serverP12;

    public TestCerts(Path dir) throws Exception {
        this.dir = dir;
        caPem = dir.resolve("ca.pem");
        otherCaPem = dir.resolve("other-ca.pem");
        serverP12 = dir.resolve("server.p12");
        Path ca = dir.resolve("ca.p12");
        Path other = dir.resolve("other.p12");
        genCa(ca, "CN=Studio Test CA");
        genCa(other, "CN=Unrelated CA");
        keytool("-exportcert", "-rfc", "-alias", "ca", "-keystore", ca, "-storepass", PASSWORD, "-file", caPem);
        keytool("-exportcert", "-rfc", "-alias", "ca", "-keystore", other, "-storepass", PASSWORD, "-file", otherCaPem);
        keytool("-genkeypair", "-alias", "server", "-keyalg", "EC", "-dname", "CN=server", "-validity", "30",
                "-keystore", serverP12, "-storetype", "PKCS12", "-storepass", PASSWORD);
        Path csr = dir.resolve("server.csr");
        Path signed = dir.resolve("server.pem");
        keytool("-certreq", "-alias", "server", "-keystore", serverP12, "-storepass", PASSWORD, "-file", csr);
        keytool("-gencert", "-alias", "ca", "-keystore", ca, "-storepass", PASSWORD, "-infile", csr, "-outfile", signed,
                "-rfc", "-validity", "30", "-ext", "SAN=dns:localhost,dns:updates.example.test,ip:127.0.0.1");
        keytool("-importcert", "-alias", "ca", "-keystore", serverP12, "-storepass", PASSWORD, "-file", caPem, "-noprompt");
        keytool("-importcert", "-alias", "server", "-keystore", serverP12, "-storepass", PASSWORD, "-file", signed, "-noprompt");
    }

    private static void genCa(Path store, String dn) throws Exception {
        keytool("-genkeypair", "-alias", "ca", "-keyalg", "EC", "-dname", dn, "-validity", "30", "-ext", "bc:c",
                "-keystore", store, "-storetype", "PKCS12", "-storepass", PASSWORD);
    }

    /** A server-side SSLContext presenting the CA-signed server certificate. */
    public SSLContext serverContext() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(serverP12)) {
            ks.load(in, PASSWORD.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASSWORD.toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    private static void keytool(Object... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        for (Object a : args) cmd.add(a.toString());
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) throw new IllegalStateException("keytool failed: " + out);
    }
}
