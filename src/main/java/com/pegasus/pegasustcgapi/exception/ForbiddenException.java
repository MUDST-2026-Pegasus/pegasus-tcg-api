package com.pegasus.pegasustcgapi.exception;

/** Thrown when the caller is authenticated but not allowed. */
public class ForbiddenException extends ApiException {

    public ForbiddenException(ErrorCode errorCode) {
        super(errorCode);
    }

    public ForbiddenException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
