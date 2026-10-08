package com.cassandrastudio.engine;

/** Engine version, stamped from the build's resources. */
public final class Version {
    public static final String VERSION = load();

    private Version() {}

    private static String load() {
        try (var in = Version.class.getResourceAsStream("/studio-version.txt")) {
            return in == null ? "dev" : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (java.io.IOException e) {
            return "dev";
        }
    }
}
