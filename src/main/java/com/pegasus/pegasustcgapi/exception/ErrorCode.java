package com.pegasus.pegasustcgapi.exception;

import org.springframework.http.HttpStatus;


public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request payload failed validation"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Request could not be read"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method is not supported here"),

    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Email or password is incorrect"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication is required"),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "Token is invalid, expired or already used"),

    ACCOUNT_LOCKED(HttpStatus.FORBIDDEN, "Account is temporarily locked"),
    ACCOUNT_SUSPENDED(HttpStatus.FORBIDDEN, "Account is suspended"),
    ACCOUNT_DEACTIVATED(HttpStatus.FORBIDDEN, "Account is deactivated"),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "Email address is not verified"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Not allowed to perform this action"),
    SELLER_NOT_VERIFIED(HttpStatus.FORBIDDEN, "Seller profile is not verified"),

    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found"),
    ROLE_NOT_FOUND(HttpStatus.NOT_FOUND, "Role not found"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "No such endpoint"),
    ADDRESS_NOT_FOUND(HttpStatus.NOT_FOUND, "Address not found"),
    SELLER_NOT_FOUND(HttpStatus.NOT_FOUND, "Seller profile not found"),
    VERIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Verification request not found"),
    SHIPPING_OPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "Shipping option not found"),
    PAYOUT_ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "Payout account not found"),
    SETTING_NOT_FOUND(HttpStatus.NOT_FOUND, "Setting not found"),
    GAME_NOT_FOUND(HttpStatus.NOT_FOUND, "Game not found"),
    ATTRIBUTE_NOT_FOUND(HttpStatus.NOT_FOUND, "Game attribute not found"),
    CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "Category not found"),
    CARD_SET_NOT_FOUND(HttpStatus.NOT_FOUND, "Card set not found"),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "Product not found"),
    VARIANT_NOT_FOUND(HttpStatus.NOT_FOUND, "Variant not found"),
    IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "Image not found"),

    EMAIL_ALREADY_USED(HttpStatus.CONFLICT, "Email address is already registered"),
    USERNAME_ALREADY_USED(HttpStatus.CONFLICT, "Username is already taken"),
    SELLER_ALREADY_VERIFIED(HttpStatus.CONFLICT, "Seller profile is already verified"),
    VERIFICATION_IN_REVIEW(HttpStatus.CONFLICT, "A verification request is already under review"),
    VERIFICATION_ALREADY_DECIDED(HttpStatus.CONFLICT, "This verification request was already decided"),
    BANK_ACCOUNT_ALREADY_USED(HttpStatus.CONFLICT, "This bank account is already registered to another seller"),
    GAME_CODE_ALREADY_USED(HttpStatus.CONFLICT, "Another game already uses this code"),
    ATTRIBUTE_KEY_ALREADY_USED(HttpStatus.CONFLICT, "This game already has an attribute with that key"),
    CATEGORY_CODE_ALREADY_USED(HttpStatus.CONFLICT, "Another category already uses this code"),
    CARD_SET_CODE_ALREADY_USED(HttpStatus.CONFLICT, "This game already has a set with that code"),
    SKU_ALREADY_USED(HttpStatus.CONFLICT, "Another variant already uses this SKU"),
    VARIANT_ALREADY_EXISTS(HttpStatus.CONFLICT, "This product already has a variant with that identity"),

    INVALID_ATTRIBUTE_DEFINITION(HttpStatus.BAD_REQUEST, "Attribute definition is not usable"),
    INVALID_PRODUCT_ATTRIBUTES(HttpStatus.BAD_REQUEST, "Product attributes do not match this game"),

    UNSUPPORTED_FILE_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "File type is not allowed for this upload"),
    FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "File is larger than this upload allows"),
    FILE_NOT_FOUND(HttpStatus.NOT_FOUND, "File was never uploaded"),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error"),
    STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Object storage is unavailable");

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
