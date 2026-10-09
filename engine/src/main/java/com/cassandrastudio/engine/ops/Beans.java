package com.cassandrastudio.engine.ops;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.JMException;
import javax.management.MBeanException;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.RuntimeMBeanException;

/**
 * JMX access for operations over one {@link MBeanServerConnection}. Reads are null-safe (a
 * missing MBean or attribute gives null, NFR-COMPAT); operations are matched against the node's
 * MBeanInfo so the right signature is used on 3.11, 4.x and 5.0, and a node that lacks an
 * operation fails with a clear message. A broken connection escapes as {@link UncheckedIOException}.
 */
final class Beans {
    static final String STORAGE_SERVICE = "org.apache.cassandra.db:type=StorageService";
    static final String STORAGE_PROXY = "org.apache.cassandra.db:type=StorageProxy";
    static final String COMPACTION_MANAGER = "org.apache.cassandra.db:type=CompactionManager";
    static final String SNITCH_INFO = "org.apache.cassandra.db:type=EndpointSnitchInfo";
    static final String DYNAMIC_SNITCH = "org.apache.cassandra.db:type=DynamicEndpointSnitch";
    static final String FAILURE_DETECTOR = "org.apache.cassandra.net:type=FailureDetector";
    static final String MESSAGING = "org.apache.cassandra.net:type=MessagingService";
    static final String STREAM_MANAGER = "org.apache.cassandra.net:type=StreamManager";
    static final String METRICS = "org.apache.cassandra.metrics";

    static final String STR = String.class.getName();
    static final String STRS = String[].class.getName();
    static final String MAP = Map.class.getName();

    private final MBeanServerConnection conn;
    private final Map<String, List<String[]>> sigs = new HashMap<>();

    Beans(MBeanServerConnection conn) {
        this.conn = conn;
    }

    MBeanServerConnection connection() {
        return conn;
    }

    static ObjectName name(String n) {
        try {
            return new ObjectName(n);
        } catch (MalformedObjectNameException e) {
            throw new IllegalArgumentException("Bad ObjectName " + n, e);
        }
    }

    static String metric(String type, String name) {
        return METRICS + ":type=" + type + ",name=" + name;
    }

    // ---- reads --------------------------------------------------------------------------

    Map<String, Object> attrs(ObjectName bean, Collection<String> names) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            AttributeList list = conn.getAttributes(bean, names.toArray(String[]::new));
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

    Map<String, Object> attrs(String bean, String... names) {
        return attrs(name(bean), List.of(names));
    }

    Object attr(String bean, String attr) {
        return attrs(name(bean), List.of(attr)).get(attr);
    }

    /** The first of the attributes the bean has (e.g. a *WithPort variant first, then the old name). */
    Object firstAttr(String bean, String... names) {
        Map<String, Object> all = attrs(name(bean), List.of(names));
        for (String n : names) {
            if (all.get(n) != null) return all.get(n);
        }
        return null;
    }

    /** One attribute from the first of the beans that has it (e.g. type=Table, then type=ColumnFamily). */
    Object firstAttrOf(List<String> beans, String attr) {
        for (String bean : beans) {
            Object v = attr(bean, attr);
            if (v != null) return v;
        }
        return null;
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

    boolean exists(String bean) {
        try {
            return conn.isRegistered(name(bean));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- operations -----------------------------------------------------------------------

    /** Signatures (parameter type names) of every overload of an operation; empty when missing. */
    List<String[]> signatures(String bean, String op) {
        if (!sigs.containsKey(bean)) {
            List<String[]> none = List.of();
            try {
                for (MBeanOperationInfo oi : conn.getMBeanInfo(name(bean)).getOperations()) {
                    String[] types = Arrays.stream(oi.getSignature()).map(MBeanParameterInfo::getType).toArray(String[]::new);
                    sigs.computeIfAbsent(bean + "#" + oi.getName(), k -> new ArrayList<>()).add(types);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (JMException | RuntimeException e) {
                // bean missing: no operations
            }
            sigs.put(bean, none);
        }
        return sigs.getOrDefault(bean + "#" + op, List.of());
    }

    boolean has(String bean, String op, String... signature) {
        for (String[] s : signatures(bean, op)) {
            if (Arrays.equals(s, signature)) return true;
        }
        return false;
    }

    /** One way to call an operation: its exact signature and the arguments. */
    record Call(String[] signature, Object[] args) {
        static Call of(Object... pairs) {
            String[] sig = new String[pairs.length / 2];
            Object[] args = new Object[pairs.length / 2];
            for (int i = 0; i < pairs.length; i += 2) {
                sig[i / 2] = (String) pairs[i];
                args[i / 2] = pairs[i + 1];
            }
            return new Call(sig, args);
        }
    }

    /**
     * Invokes the first variant the node supports (version detection by MBeanInfo). Throws
     * {@link OpsException} with the node's own error message when the operation fails, or a
     * "not available on this version" message when no variant exists.
     */
    Object invoke(String bean, String op, Call... variants) {
        for (Call c : variants) {
            if (has(bean, op, c.signature())) return invokeExact(bean, op, c);
        }
        throw new OpsException(op + " is not available on this Cassandra version");
    }

    Object invokeExact(String bean, String op, Call c) {
        try {
            return conn.invoke(name(bean), op, c.args(), c.signature());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (MBeanException | RuntimeMBeanException | ReflectionException e) {
            throw new OpsException(message(e), e);
        } catch (JMException | RuntimeException e) {
            throw new OpsException(op + " failed: " + message(e), e);
        }
    }

    /** Invokes an optional read operation with String arguments; null when missing or failing. */
    Object tryInvoke(String bean, String op, String... args) {
        String[] sig = new String[args.length];
        Arrays.fill(sig, STR);
        try {
            return conn.invoke(name(bean), op, args, sig);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (JMException | RuntimeException e) {
            return null;
        }
    }

    /** The most specific message in a JMX exception chain (the node's own error text). */
    static String message(Throwable e) {
        String best = null;
        for (Throwable t = e; t != null && t.getCause() != t; t = t.getCause()) {
            if (t.getMessage() != null && !t.getMessage().isBlank()) best = t.getMessage();
            if (t.getCause() == null) break;
        }
        if (best == null) return e.getClass().getSimpleName();
        // RMI wraps remote exceptions as "...; nested exception is: ..." - keep the innermost part.
        int nested = best.lastIndexOf("nested exception is:");
        return nested >= 0 ? best.substring(nested + "nested exception is:".length()).trim() : best;
    }

    // ---- conversions -----------------------------------------------------------------------

    static Long asLong(Object v) {
        if (v instanceof Number n) return Double.isNaN(n.doubleValue()) ? null : n.longValue();
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

    static String str(Object v) {
        return v == null ? null : v.toString();
    }

    /** "/10.0.0.1", "host/10.0.0.1:7000", "10.0.0.1:7000", "[::1]:7000" or an InetAddress → bare IP. */
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
        if (colon > 0 && colon == s.lastIndexOf(':')) s = s.substring(0, colon);
        return s;
    }

    /** A Map attribute with its keys reduced to bare addresses. */
    static Map<String, Object> byAddress(Object map) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (map instanceof Map<?, ?> m) m.forEach((k, v) -> out.put(bareAddress(k), v));
        return out;
    }

    static List<String> addresses(Object list) {
        List<String> out = new ArrayList<>();
        if (list instanceof Collection<?> c) c.forEach(x -> out.add(bareAddress(x)));
        return out;
    }
}
