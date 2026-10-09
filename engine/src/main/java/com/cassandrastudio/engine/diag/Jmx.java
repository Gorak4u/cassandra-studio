package com.cassandrastudio.engine.diag;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.management.Attribute;
import javax.management.InstanceNotFoundException;
import javax.management.JMException;
import javax.management.MBeanException;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.RuntimeMBeanException;

/**
 * Small JMX helpers for the diagnostics package. Reads are null-safe (a missing MBean or attribute
 * gives null); {@link #call} reports a missing operation as {@link Missing} so callers can fall
 * back to another signature (3.11 vs 4.0+). A broken connection escapes as {@link UncheckedIOException}.
 */
final class Jmx {
    static final String THREADING = "java.lang:type=Threading";
    static final String OS = "java.lang:type=OperatingSystem";
    static final String RUNTIME = "java.lang:type=Runtime";

    private Jmx() {}

    /** The operation (or its signature) does not exist on this node. */
    static final class Missing extends RuntimeException {
        Missing(String message) {
            super(message);
        }
    }

    static ObjectName name(String s) {
        try {
            return new ObjectName(s);
        } catch (MalformedObjectNameException e) {
            throw new IllegalArgumentException("Bad ObjectName " + s, e);
        }
    }

    static Object attribute(MBeanServerConnection c, String bean, String attr) {
        return attributes(c, bean, attr).get(attr);
    }

    static Map<String, Object> attributes(MBeanServerConnection c, String bean, String... attrs) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            for (Attribute a : c.getAttributes(name(bean), attrs).asList()) {
                if (a.getValue() != null) out.put(a.getName(), a.getValue());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (JMException | RuntimeException e) {
            // missing bean or attribute
        }
        return out;
    }

    static Set<ObjectName> query(MBeanServerConnection c, String pattern) {
        try {
            return new TreeSet<>(c.queryNames(name(pattern), null));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Invokes an operation. Throws {@link Missing} when the bean or this signature does not exist,
     * {@link IllegalStateException} with the node's message when the operation itself failed.
     */
    static Object call(MBeanServerConnection c, String bean, String op, Object[] args, String[] sig) {
        try {
            return c.invoke(name(bean), op, args, sig);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InstanceNotFoundException e) {
            throw new Missing("MBean " + bean + " not found");
        } catch (ReflectionException e) {
            throw new Missing("Operation " + op + " not available: " + rootMessage(e));
        } catch (MBeanException | RuntimeMBeanException e) {
            throw new IllegalStateException(rootMessage(e));
        } catch (RuntimeException e) {
            // e.g. IllegalArgumentException for an unknown sampler name, or an unmarshalling error
            String m = rootMessage(e);
            if (m.contains("No such operation") || m.contains("NoSuchMethod")) throw new Missing(op + " not available");
            throw new IllegalStateException(m);
        }
    }

    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        if (m == null || m.isBlank()) m = t.getClass().getSimpleName();
        return m;
    }

    static Long asLong(Object v) {
        return v instanceof Number n ? Long.valueOf(n.longValue()) : null;
    }

    static Double asDouble(Object v) {
        if (!(v instanceof Number n)) return null;
        double d = n.doubleValue();
        return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
    }

    static List<String> stringSig(int n) {
        return java.util.Collections.nCopies(n, String.class.getName());
    }
}
