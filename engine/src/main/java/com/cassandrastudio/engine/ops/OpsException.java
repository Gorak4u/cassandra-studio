package com.cassandrastudio.engine.ops;

/** An operation failed on a node; the message is user-readable (the node's own error text). */
public final class OpsException extends RuntimeException {
    public OpsException(String message) {
        super(message);
    }

    public OpsException(String message, Throwable cause) {
        super(message, cause);
    }
}
