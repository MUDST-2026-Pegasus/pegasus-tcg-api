package com.pegasus.pegasustcgapi.exception;

/** Thrown when the caller is not authenticated. */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException(ErrorCode errorCode) {
        super(errorCode);
    }

    public UnauthorizedException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
