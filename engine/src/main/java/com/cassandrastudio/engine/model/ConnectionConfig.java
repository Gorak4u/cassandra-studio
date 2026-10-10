package com.cassandrastudio.engine.model;

import java.util.List;
import java.util.Map;

/**
 * One saved connection = one Cassandra CLUSTER (CON-1). Holds no secrets:
 * those live in the SecretStore under {@link SecretKeys}, so exporting this
 * record can never leak a password (CON-9).
 */
public record ConnectionConfig(
        String id,
        String folderId,
        String name,
        Environment environment,
        String color,
        boolean readOnly,
        List<String> contactPoints,
        String localDatacenter,
        String username,
        Tls tls,
        String protocolVersion,
        String defaultConsistency,
        Integer requestTimeoutMs,
        Integer pageSize,
        Jmx jmx,
        Ssh ssh,
        List<String> tags,
        String notes,
        Map<String, Boolean> secretsSet) {

    public static final int DEFAULT_CQL_PORT = 9042;

    public ConnectionConfig {
        environment = environment == null ? Environment.DEV : environment;
        contactPoints = contactPoints == null ? List.of() : List.copyOf(contactPoints);
        tls = tls == null ? Tls.DISABLED : tls;
        protocolVersion = protocolVersion == null || protocolVersion.isBlank() ? "auto" : protocolVersion;
        defaultConsistency = defaultConsistency == null || defaultConsistency.isBlank() ? "LOCAL_ONE" : defaultConsistency;
        requestTimeoutMs = requestTimeoutMs == null || requestTimeoutMs <= 0 ? 12_000 : requestTimeoutMs;
        pageSize = pageSize == null || pageSize <= 0 ? 100 : pageSize;
        jmx = jmx == null ? Jmx.DEFAULT : jmx;
        ssh = ssh == null ? Ssh.DEFAULT : ssh;
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public ConnectionConfig withId(String newId) {
        return new ConnectionConfig(newId, folderId, name, environment, color, readOnly, contactPoints, localDatacenter,
                username, tls, protocolVersion, defaultConsistency, requestTimeoutMs, pageSize, jmx, ssh, tags, notes, secretsSet);
    }

    public ConnectionConfig withName(String newName) {
        return new ConnectionConfig(id, folderId, newName, environment, color, readOnly, contactPoints, localDatacenter,
                username, tls, protocolVersion, defaultConsistency, requestTimeoutMs, pageSize, jmx, ssh, tags, notes, secretsSet);
    }

    public ConnectionConfig withFolder(String newFolderId) {
        return new ConnectionConfig(id, newFolderId, name, environment, color, readOnly, contactPoints, localDatacenter,
                username, tls, protocolVersion, defaultConsistency, requestTimeoutMs, pageSize, jmx, ssh, tags, notes, secretsSet);
    }

    public ConnectionConfig withSecretsSet(Map<String, Boolean> set) {
        return new ConnectionConfig(id, folderId, name, environment, color, readOnly, contactPoints, localDatacenter,
                username, tls, protocolVersion, defaultConsistency, requestTimeoutMs, pageSize, jmx, ssh, tags, notes, set);
    }

    public boolean isProd() {
        return environment == Environment.PROD;
    }

    public enum Environment { DEV, TEST, STAGING, PROD }

    public record Tls(boolean enabled, String truststorePath, String truststoreType, String keystorePath,
                      String keystoreType, boolean hostnameVerification) {
        public static final Tls DISABLED = new Tls(false, null, null, null, null, true);
    }

    /** How JMX on the nodes is reached (CON-6). */
    public enum JmxMethod { SSH_TUNNEL, DIRECT, EXPORTER, SIDECAR, NONE }

    public record Jmx(JmxMethod method, Integer port, String username, boolean ssl, Integer exporterPort,
                      Integer sidecarPort) {
        public static final Jmx DEFAULT = new Jmx(JmxMethod.SSH_TUNNEL, 7199, null, false, 7071, 9043);

        public Jmx {
            method = method == null ? JmxMethod.SSH_TUNNEL : method;
            port = port == null ? 7199 : port;
            exporterPort = exporterPort == null ? 7071 : exporterPort;
            sidecarPort = sidecarPort == null ? 9043 : sidecarPort;
        }
    }

    public enum SshAuth { AGENT, KEY, PASSWORD }

    /**
     * SSH to nodes (CON-7): JMX tunnels, log tail, GC logs, scripts. {@code proxy} (optional, NFR-NET): reach the
     * first hop (the jump host, else the node) through an HTTP CONNECT or SOCKS5 proxy; absent in older saves.
     */
    public record Ssh(String username, Integer port, SshAuth auth, String keyPath, String jumpHost, Integer jumpPort,
                      String jumpUser, boolean strictHostKeyChecking, String knownHostsPath, SshProxy proxy) {
        public static final Ssh DEFAULT = new Ssh(null, 22, SshAuth.AGENT, null, null, 22, null, true, null);

        @com.fasterxml.jackson.annotation.JsonCreator
        public Ssh {
            port = port == null ? 22 : port;
            auth = auth == null ? SshAuth.AGENT : auth;
            jumpPort = jumpPort == null ? 22 : jumpPort;
            proxy = proxy == null || proxy.type() == null || proxy.host() == null || proxy.host().isBlank() ? null : proxy;
        }

        /** Without a proxy (the shape before NFR-NET). */
        public Ssh(String username, Integer port, SshAuth auth, String keyPath, String jumpHost, Integer jumpPort,
                   String jumpUser, boolean strictHostKeyChecking, String knownHostsPath) {
            this(username, port, auth, keyPath, jumpHost, jumpPort, jumpUser, strictHostKeyChecking, knownHostsPath, null);
        }
    }

    public enum ProxyType { HTTP, SOCKS5 }

    /** A proxy for SSH: HTTP CONNECT or SOCKS5, optional user (password in secret {@link SecretKeys#SSH_PROXY_PASSWORD}). */
    public record SshProxy(ProxyType type, String host, Integer port, String username) {
        public SshProxy {
            port = port == null ? (type == ProxyType.SOCKS5 ? 1080 : 3128) : port;
            username = username == null || username.isBlank() ? null : username;
        }
    }

    /** Names of the secrets a connection can have. */
    public static final class SecretKeys {
        public static final String PASSWORD = "password";
        public static final String JMX_PASSWORD = "jmxPassword";
        public static final String SSH_PASSWORD = "sshPassword";
        public static final String SSH_PASSPHRASE = "sshPassphrase";
        public static final String TRUSTSTORE_PASSWORD = "truststorePassword";
        public static final String KEYSTORE_PASSWORD = "keystorePassword";
        public static final String SSH_PROXY_PASSWORD = "sshProxyPassword";
        public static final List<String> ALL = List.of(PASSWORD, JMX_PASSWORD, SSH_PASSWORD, SSH_PASSPHRASE,
                TRUSTSTORE_PASSWORD, KEYSTORE_PASSWORD, SSH_PROXY_PASSWORD);

        private SecretKeys() {}

        public static String storeKey(String connectionId, String secret) {
            return "conn/" + connectionId + "/" + secret;
        }
    }
}
