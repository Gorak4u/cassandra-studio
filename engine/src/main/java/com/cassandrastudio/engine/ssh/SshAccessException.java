package com.cassandrastudio.engine.ssh;

/** SSH could not be used; the message is user-readable and never contains a secret. */
public final class SshAccessException extends RuntimeException {
    public SshAccessException(String message) {
        super(message);
    }

    public SshAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
