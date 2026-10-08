package com.cassandrastudio.engine.cql;

import com.datastax.oss.driver.api.core.AllNodesFailedException;
import java.util.LinkedHashSet;
import java.util.Set;

/** Turns driver exceptions into one readable line for the UI. */
public final class Errors {
    private Errors() {}

    public static String describe(Throwable e) {
        Throwable t = unwrap(e);
        if (t instanceof AllNodesFailedException anf && !anf.getAllErrors().isEmpty()) {
            Set<String> parts = new LinkedHashSet<>();
            anf.getAllErrors().forEach((node, errs) -> errs.forEach(err -> parts.add(node.getEndPoint() + ": " + message(err))));
            return "All nodes failed: " + String.join("; ", parts);
        }
        return message(t);
    }

    private static String message(Throwable t) {
        Throwable u = unwrap(t);
        String m = u.getMessage();
        return m == null || m.isBlank() ? u.getClass().getSimpleName() : m;
    }

    public static Throwable unwrap(Throwable e) {
        Throwable t = e;
        while ((t instanceof java.util.concurrent.ExecutionException || t instanceof java.util.concurrent.CompletionException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}
