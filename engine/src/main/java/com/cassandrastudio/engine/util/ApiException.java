package com.cassandrastudio.engine.util;

import java.util.Map;

/** An error the API turns into an HTTP status and a JSON body the UI can act on. */
public class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final Map<String, Object> details;

    public ApiException(int status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(int status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public static ApiException notFound(String what) {
        return new ApiException(404, "not_found", what + " not found");
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, "bad_request", message);
    }

    public int status() { return status; }

    public String code() { return code; }

    public Map<String, Object> details() { return details; }
}
