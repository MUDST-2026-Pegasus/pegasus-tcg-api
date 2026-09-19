package com.pegasus.pegasustcgapi.exception;

/**
 * Thrown when a request is missing required headers or parameters.
 */
public class BadRequestException extends ApiException {

    public BadRequestException(ErrorCode errorCode) {
        super(errorCode);
    }

    public BadRequestException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
