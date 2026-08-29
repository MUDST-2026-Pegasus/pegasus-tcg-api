package com.pegasus.pegasustcgapi.exception;

/** Thrown when the resource already exists. */
public class ConflictException extends ApiException {

    public ConflictException(ErrorCode errorCode) {
        super(errorCode);
    }

    public ConflictException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
