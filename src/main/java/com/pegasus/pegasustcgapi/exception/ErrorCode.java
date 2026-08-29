package com.pegasus.pegasustcgapi.exception;

import org.springframework.http.HttpStatus;

/**
 * Every failure the API reports on purpose. Clients branch on {@link #name()},
 * so the codes are part of the contract — rename with care.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request payload failed validation"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Request could not be read"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method is not supported here"),

    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Username or password is incorrect"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication is required"),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "Token is invalid, expired or already used"),

    ACCOUNT_LOCKED(HttpStatus.FORBIDDEN, "Account is temporarily locked"),
    ACCOUNT_SUSPENDED(HttpStatus.FORBIDDEN, "Account is suspended"),
    ACCOUNT_DEACTIVATED(HttpStatus.FORBIDDEN, "Account is deactivated"),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "Email address is not verified"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Not allowed to perform this action"),

    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found"),
    ROLE_NOT_FOUND(HttpStatus.NOT_FOUND, "Role not found"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "No such endpoint"),

    EMAIL_ALREADY_USED(HttpStatus.CONFLICT, "Email address is already registered"),
    USERNAME_ALREADY_USED(HttpStatus.CONFLICT, "Username is already taken"),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
