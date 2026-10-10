package com.cassandrastudio.engine.audit;

import java.util.function.Supplier;

/**
 * Who is acting on the current thread, for the audit log. The desktop engine has one user (the OS
 * account given to {@link AuditLog}); Studio Server sets the signed-in user per request, jobs keep the
 * user who started them, and schedules run as "schedule: name".
 */
public final class Actor {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private Actor() {}

    /** The acting user on this thread, or null when none was set (then the engine's own user applies). */
    public static String current() {
        return CURRENT.get();
    }

    /** Runs {@code work} as {@code actor} (null keeps the engine's own user), restoring the previous one after. */
    public static <T> T as(String actor, Supplier<T> work) {
        String before = CURRENT.get();
        CURRENT.set(actor);
        try {
            return work.get();
        } finally {
            if (before == null) CURRENT.remove();
            else CURRENT.set(before);
        }
    }

    public static void as(String actor, Runnable work) {
        as(actor, () -> {
            work.run();
            return null;
        });
    }
}
