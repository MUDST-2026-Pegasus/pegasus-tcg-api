package com.pegasus.pegasustcgapi.exception;

import org.springframework.http.HttpStatus;


public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request payload failed validation"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Request could not be read"),
    UNSUPPORTED_CONTENT_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Send the request body as application/json"),
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
    COLLECTION_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "Collection card not found"),
    LISTING_NOT_FOUND(HttpStatus.NOT_FOUND, "Listing not found"),
    LISTING_UNIT_NOT_FOUND(HttpStatus.NOT_FOUND, "Card not found in this seller's stock"),
    CART_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "Cart item not found"),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Order not found"),

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
    VARIANT_INACTIVE(HttpStatus.CONFLICT, "This card printing is no longer offered in the catalogue"),
    COLLECTION_ITEM_FROM_PURCHASE(HttpStatus.CONFLICT, "A purchased card keeps the printing and quantity it was sold as"),
    IMAGE_KEY_IN_USE(HttpStatus.CONFLICT, "This photo already belongs to another collector's card"),
    LISTING_PHOTO_IN_USE(HttpStatus.CONFLICT, "This photo already belongs to another seller's listing"),
    LISTING_STATUS_TRANSITION(HttpStatus.CONFLICT, "This listing cannot move to that status"),
    LISTING_CLOSED(HttpStatus.CONFLICT, "This listing is closed to changes"),
    LISTING_HAS_NO_STOCK(HttpStatus.CONFLICT, "Put at least one card on the listing before publishing it"),
    LISTING_HAS_RESERVATIONS(HttpStatus.CONFLICT, "Cards on this listing are held by an order that is still open"),
    LISTING_UNIT_MISMATCH(HttpStatus.CONFLICT, "This card is a different printing, condition or seller than the listing"),
    LISTING_UNIT_STATE(HttpStatus.CONFLICT, "The card is not in a state that allows this"),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "Not enough cards left on this listing"),
    CART_EMPTY(HttpStatus.CONFLICT, "Cart is empty"),
    LISTING_NOT_PURCHASABLE(HttpStatus.CONFLICT, "Listing is not available for purchase"),
    CART_PRICE_CHANGED(HttpStatus.CONFLICT, "One or more item prices have changed"),
    CANNOT_BUY_OWN_LISTING(HttpStatus.CONFLICT, "Cannot purchase your own listing"),
    ORDER_STATUS_TRANSITION(HttpStatus.CONFLICT, "This order cannot move to that status"),
    CANCEL_WINDOW_CLOSED(HttpStatus.CONFLICT, "Order cancellation window has closed"),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "Request with this idempotency key is already in progress or completed"),

    INVALID_ATTRIBUTE_DEFINITION(HttpStatus.BAD_REQUEST, "Attribute definition is not usable"),
    INVALID_PRODUCT_ATTRIBUTES(HttpStatus.BAD_REQUEST, "Product attributes do not match this game"),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required"),

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
