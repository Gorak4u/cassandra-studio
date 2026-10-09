package com.cassandrastudio.engine.metrics;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.JMException;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

/**
 * Null-safe reads over one {@link MBeanServerConnection} (NFR-COMPAT): a missing MBean,
 * attribute or operation gives null / empty, never an exception. Only a broken connection
 * ({@link IOException}) escapes, as {@link UncheckedIOException}, so the caller can mark the
 * node unreachable.
 */
final class Mbeans {
    private final MBeanServerConnection conn;

    Mbeans(MBeanServerConnection conn) {
        this.conn = conn;
    }

    static ObjectName name(String name) {
        try {
            return new ObjectName(name);
        } catch (MalformedObjectNameException e) {
            throw new IllegalArgumentException("Bad ObjectName " + name, e);
        }
    }

    /** All requested attributes that exist, in one round trip. Missing bean = empty map. */
    Map<String, Object> attributes(ObjectName bean, Collection<String> attrs) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            AttributeList list = conn.getAttributes(bean, attrs.toArray(String[]::new));
            for (Attribute a : list.asList()) {
                if (a.getValue() != null) out.put(a.getName(), a.getValue());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (JMException | RuntimeException e) {
            // missing bean or attribute: graceful degradation
        }
        return out;
    }

    Map<String, Object> attributes(String bean, String... attrs) {
        return attributes(name(bean), List.of(attrs));
    }

    Object attribute(String bean, String attr) {
        return attributes(name(bean), List.of(attr)).get(attr);
    }

    Set<ObjectName> query(String pattern) {
        try {
            return new TreeSet<>(conn.queryNames(name(pattern), null));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    /** Invokes an operation taking String arguments; null when it does not exist or fails. */
    Object invoke(String bean, String operation, String... args) {
        String[] sig = new String[args.length];
        Arrays.fill(sig, String.class.getName());
        try {
            return conn.invoke(name(bean), operation, args, sig);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (JMException | RuntimeException e) {
            return null;
        }
    }

    // ---- conversions -----------------------------------------------------------

    static Long asLong(Object v) {
        if (v instanceof Number n) {
            return Double.isNaN(n.doubleValue()) ? null : n.longValue();
        }
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    static Double asDouble(Object v) {
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
        }
        return null;
    }

    static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    /** A field of a CompositeData attribute such as HeapMemoryUsage.used. */
    static Long compositeLong(Object v, String key) {
        if (v instanceof CompositeData cd && cd.containsKey(key)) {
            Long l = asLong(cd.get(key));
            return l == null || l < 0 ? null : l;
        }
        return null;
    }

    /** 0..1 load (as the OperatingSystem MBean reports it) to percent; negative = unavailable. */
    static Double loadPct(Object v) {
        Double d = asDouble(v);
        return d == null || d < 0 ? null : d * 100.0;
    }

    /**
     * Node address as Cassandra prints it ("/10.0.0.1", "host/10.0.0.1:7000", "[::1]:7000")
     * reduced to the bare IP.
     */
    static String bareAddress(Object raw) {
        if (raw == null) return null;
        String s = raw instanceof InetAddress ia ? ia.getHostAddress() : raw.toString().trim();
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            return close > 0 ? s.substring(1, close) : s;
        }
        int colon = s.indexOf(':');
        if (colon > 0 && colon == s.lastIndexOf(':')) s = s.substring(0, colon); // IPv4 with port
        return s;
    }
}
