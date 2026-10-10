package com.cassandrastudio.engine.alerts;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Where every source publishes its alerts and every notifier listens. Only changes are passed on:
 * publishing the same severity for the same connection, source and key again is a no-op, so sources
 * can publish their current state on every check.
 */
public final class AlertHub {
    private final List<Consumer<StudioAlert>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, StudioAlert.Severity> last = new ConcurrentHashMap<>();

    public void subscribe(Consumer<StudioAlert> listener) {
        listeners.add(listener);
    }

    /** Passes the alert to every listener when its severity changed (a first GREEN is not news). */
    public void publish(StudioAlert alert) {
        String id = alert.connectionId() + "|" + alert.source() + "|" + alert.key();
        StudioAlert.Severity before = last.put(id, alert.severity());
        if (before == alert.severity()) return;
        if (before == null && alert.severity() == StudioAlert.Severity.GREEN) return;
        for (Consumer<StudioAlert> l : listeners) {
            try {
                l.accept(alert);
            } catch (RuntimeException e) {
                System.err.println("Alert listener failed: " + e);
            }
        }
    }

    /** Forgets a connection's alert state (deleted or edited connection). */
    public void forget(String connectionId) {
        last.keySet().removeIf(k -> k.startsWith(connectionId + "|"));
    }
}
