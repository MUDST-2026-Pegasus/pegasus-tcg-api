package com.pegasus.pegasustcgapi.exception;

/** Thrown when the resource is missing. */
public class NotFoundException extends ApiException {

    public NotFoundException(ErrorCode errorCode) {
        super(errorCode);
    }

    public NotFoundException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
