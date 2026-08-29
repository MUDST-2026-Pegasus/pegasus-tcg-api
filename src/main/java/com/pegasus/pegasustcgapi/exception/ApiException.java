package com.pegasus.pegasustcgapi.exception;

/**
 * Base for failures the API reports deliberately. Carrying an {@link ErrorCode}
 * means the HTTP status is decided once, at the code, instead of at each throw site.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
