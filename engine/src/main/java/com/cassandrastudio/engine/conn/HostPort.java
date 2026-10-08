package com.cassandrastudio.engine.conn;

import com.cassandrastudio.engine.util.ApiException;

/** "host", "host:port", "[ipv6]:port" or a bare IPv6 address. */
public record HostPort(String host, int port) {

    public static HostPort parse(String text, int defaultPort) {
        if (text == null || text.isBlank()) throw ApiException.badRequest("Empty host");
        String s = text.trim();
        String host;
        String port = null;
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            if (end < 0) throw ApiException.badRequest("Bad address '" + s + "'");
            host = s.substring(1, end);
            if (s.length() > end + 1) {
                if (s.charAt(end + 1) != ':') throw ApiException.badRequest("Bad address '" + s + "'");
                port = s.substring(end + 2);
            }
        } else if (s.indexOf(':') != s.lastIndexOf(':')) {
            host = s; // bare IPv6
        } else if (s.contains(":")) {
            host = s.substring(0, s.indexOf(':'));
            port = s.substring(s.indexOf(':') + 1);
        } else {
            host = s;
        }
        if (host.isBlank() || host.chars().anyMatch(Character::isWhitespace)) {
            throw ApiException.badRequest("Bad host in '" + s + "'");
        }
        int p = defaultPort;
        if (port != null) {
            try {
                p = Integer.parseInt(port);
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("Bad port in '" + s + "'");
            }
            if (p < 1 || p > 65535) throw ApiException.badRequest("Port out of range in '" + s + "'");
        }
        return new HostPort(host, p);
    }
}
