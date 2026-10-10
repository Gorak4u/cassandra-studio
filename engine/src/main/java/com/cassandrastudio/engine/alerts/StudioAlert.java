package com.cassandrastudio.engine.alerts;

/**
 * An alert raised by any part of Studio (health rules, repair coverage, backup age, jobs), for
 * notifications and routing (ALR-4, ALR-5). {@code key} identifies the condition within its source
 * (e.g. "node-down:10.0.0.1", "repair-coverage:shop.orders") so a later GREEN clears it.
 */
public record StudioAlert(String connectionId, String connectionName, String environment, String source, String key,
                          Severity severity, String title, String detail, long atMs) {

    public enum Severity { GREEN, YELLOW, RED }
}
