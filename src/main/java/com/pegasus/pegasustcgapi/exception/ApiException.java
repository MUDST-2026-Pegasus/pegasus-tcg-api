package com.pegasus.pegasustcgapi.exception;

import java.util.Map;

/**
 * Base for failures the API reports deliberately. Carrying an {@link ErrorCode}
 * means the HTTP status is decided once, at the code, instead of at each throw site.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, Object> details;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, Map.of());
    }

    public ApiException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = Map.copyOf(details);
    }

    public Map<String, Object> details() { return details; }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
