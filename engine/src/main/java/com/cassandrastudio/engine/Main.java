package com.cassandrastudio.engine;

import com.cassandrastudio.engine.api.EngineServer;
import com.cassandrastudio.engine.secrets.SecretStore;
import com.cassandrastudio.engine.secrets.SecretStores;
import com.cassandrastudio.engine.store.CrashLog;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.Json;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Starts the engine.
 *
 * <pre>
 *   --host 127.0.0.1        bind address (desktop mode never changes this)
 *   --port 0                0 = pick a free port
 *   --token TOKEN           bearer token; default: env STUDIO_TOKEN, else random
 *   --data-dir DIR          Studio's database and fallback secret store
 *   --ui-dir DIR            serve the built UI from here
 *   --exit-on-stdin-close   stop when the parent (Electron) goes away
 *   --no-keyring            use the encrypted-file secret store
 *   --dev-cors              allow the Vite dev server (localhost:5173)
 * </pre>
 *
 * Prints one line {@code STUDIO_ENGINE_READY {"port":..,"token":..}} once listening.
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        Map<String, String> a = parse(args);
        String host = a.getOrDefault("host", "127.0.0.1");
        int port = Integer.parseInt(a.getOrDefault("port", "0"));
        String token = a.getOrDefault("token", System.getenv().getOrDefault("STUDIO_TOKEN", randomToken()));
        Path dataDir = Path.of(a.getOrDefault("data-dir", defaultDataDir().toString()));
        Path uiDir = a.containsKey("ui-dir") ? Path.of(a.get("ui-dir")) : null;

        CrashLog.install(dataDir);
        Database db;
        try {
            db = Database.open(dataDir);
        } catch (IllegalStateException e) {
            // A clean one-line reason for the desktop shell's error dialog, not a stack trace.
            CrashLog.report("engine", "Studio database could not be opened", e);
            System.err.println("STUDIO_ENGINE_ERROR " + e.getMessage());
            System.exit(2);
            return;
        }
        SecretStore secrets = SecretStores.create(dataDir, !a.containsKey("no-keyring"));
        Engine engine = new Engine(db, secrets, System.getProperty("user.name", "unknown"));
        EngineServer server = new EngineServer(engine, new EngineServer.Options(host, port, token, uiDir, a.containsKey("dev-cors")));

        engine.schedules.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            engine.close();
        }, "studio-shutdown"));

        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("port", server.port());
        ready.put("token", token);
        ready.put("version", Version.VERSION);
        ready.put("startupMs", java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime());
        System.out.println("STUDIO_ENGINE_READY " + Json.write(ready));
        System.out.flush();

        if (a.containsKey("exit-on-stdin-close")) {
            Thread t = new Thread(() -> {
                try {
                    while (System.in.read() != -1) {
                        // drain; EOF means the parent process is gone
                    }
                } catch (java.io.IOException ignored) {
                    // treat as closed
                }
                System.exit(0);
            }, "stdin-watch");
            t.setDaemon(true);
            t.start();
        }
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("Unexpected argument " + args[i]);
            String key = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) m.put(key, args[++i]);
            else m.put(key, "true");
        }
        return m;
    }

    static Path defaultDataDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return Path.of(appData != null ? appData : home, "CassandraStudio");
        }
        if (os.contains("mac")) return Path.of(home, "Library", "Application Support", "CassandraStudio");
        String xdg = System.getenv("XDG_DATA_HOME");
        return xdg != null && !xdg.isBlank() ? Path.of(xdg, "cassandra-studio") : Path.of(home, ".local", "share", "cassandra-studio");
    }

    private static String randomToken() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
