package com.cassandrastudio.engine.ops;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses FailureDetector AllEndpointStates (what nodetool gossipinfo prints):
 * <pre>
 * /10.0.0.1:7000            (4.0+: with port; 3.11: "/10.0.0.1")
 *   generation:1791534058
 *   heartbeat:1945
 *   STATUS:32:NORMAL,-149873...   (key:version:value; 3.11 may omit the version)
 * </pre>
 */
final class Gossip {
    private Gossip() {}

    record State(String key, String version, String value) {}

    record Endpoint(String endpoint, List<State> states) {}

    static List<Endpoint> parse(String text) {
        List<Endpoint> out = new ArrayList<>();
        if (text == null) return out;
        Endpoint cur = null;
        for (String raw : text.split("\\R")) {
            if (raw.isBlank()) continue;
            boolean indented = Character.isWhitespace(raw.charAt(0));
            String line = raw.trim();
            if (!indented) {
                String ep = line.startsWith("/") ? line.substring(1) : line;
                cur = new Endpoint(ep, new ArrayList<>());
                out.add(cur);
                continue;
            }
            if (cur == null) continue;
            int c1 = line.indexOf(':');
            if (c1 < 0) {
                cur.states().add(new State(line, null, ""));
                continue;
            }
            String key = line.substring(0, c1);
            String rest = line.substring(c1 + 1);
            String version = null;
            if (!key.equals("generation") && !key.equals("heartbeat")) {
                int c2 = rest.indexOf(':');
                if (c2 > 0 && rest.substring(0, c2).chars().allMatch(Character::isDigit)) {
                    version = rest.substring(0, c2);
                    rest = rest.substring(c2 + 1);
                }
            }
            cur.states().add(new State(key, version, rest));
        }
        return out;
    }
}
