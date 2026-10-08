package com.cassandrastudio.engine.it;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One real single-node Cassandra per version under test, shared by all
 * integration tests in the JVM. Versions come from -PcassandraVersions
 * (default 4.1); CI runs 3.11 (Java 8), 4.1 (Java 11) and 5.0 (Java 17),
 * the same mix as the estate.
 */
public final class CassandraClusters {
    private static final Map<String, CassandraContainer> RUNNING = new ConcurrentHashMap<>();

    private CassandraClusters() {}

    public static List<String> versions() {
        return Arrays.stream(System.getProperty("cassandra.versions", "4.1").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @SuppressWarnings("resource") // stopped by Testcontainers' Ryuk when the JVM exits
    public static synchronized CassandraContainer get(String version) {
        return RUNNING.computeIfAbsent(version, v -> {
            CassandraContainer c = new CassandraContainer(DockerImageName.parse("cassandra:" + v))
                    .withEnv("MAX_HEAP_SIZE", "768M")
                    .withEnv("HEAP_NEWSIZE", "128M")
                    .withEnv("CASSANDRA_DC", "dc1")
                    .withEnv("CASSANDRA_ENDPOINT_SNITCH", "GossipingPropertyFileSnitch")
                    .withStartupTimeout(Duration.ofMinutes(4));
            c.start();
            return c;
        });
    }

    public static String contactPoint(CassandraContainer c) {
        return c.getHost() + ":" + c.getMappedPort(9042);
    }
}
