package com.pegasus.pegasustcgapi.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

@DisplayName("ErrorCode")
class ErrorCodeTest {

    @Test
    @DisplayName("verifies cart and order error codes and HTTP statuses")
    void cartAndOrderErrorCodesMatchExpectedHttpStatus() {
        // 404
        assertThat(ErrorCode.CART_ITEM_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.ORDER_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);

        // 409
        assertThat(ErrorCode.CART_EMPTY.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.LISTING_NOT_PURCHASABLE.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.CART_PRICE_CHANGED.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.CANNOT_BUY_OWN_LISTING.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.ORDER_STATUS_TRANSITION.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.CANCEL_WINDOW_CLOSED.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.IDEMPOTENCY_KEY_CONFLICT.status()).isEqualTo(HttpStatus.CONFLICT);

        // 400
        assertThat(ErrorCode.IDEMPOTENCY_KEY_REQUIRED.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("verifies finance error codes and HTTP statuses")
    void financeErrorCodesMatchExpectedHttpStatus() {
        // 404
        assertThat(ErrorCode.PAYMENT_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.PAYOUT_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.REFUND_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.RETURN_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.REVIEW_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.COMMISSION_RULE_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);

        // 400
        assertThat(ErrorCode.PAYMENT_METHOD_NOT_SUPPORTED.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.COMMISSION_RULE_INVALID.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.PAYOUT_BELOW_MINIMUM.status()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 409
        assertThat(ErrorCode.ORDER_NOT_PAYABLE.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.PAYMENT_STATE.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.PAYOUT_STATE.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.RETURN_STATE.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.PAYMENT_AMOUNT_MISMATCH.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.PAYMENT_UNDER_REVIEW.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.SLIP_ALREADY_USED.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.INSUFFICIENT_BALANCE.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.REFUND_EXCEEDS_REFUNDABLE.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.RETURN_WINDOW_CLOSED.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.RETURN_ALREADY_OPEN.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.REVIEW_ORDER_NOT_COMPLETED.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.REVIEW_ALREADY_EXISTS.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.REVIEW_ALREADY_REPLIED.status()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("verifies promotion error codes and HTTP statuses")
    void promotionErrorCodesMatchExpectedHttpStatus() {
        // 404
        assertThat(ErrorCode.PROMO_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);

        // 400 — the code is fine as data but cannot go on this order
        assertThat(ErrorCode.PROMO_NOT_STARTED.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.PROMO_EXPIRED.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.PROMO_PAUSED.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.PROMO_USER_LIMIT_REACHED.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.PROMO_MIN_SPEND_NOT_MET.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.PROMO_NOT_APPLICABLE.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.PROMO_NEW_BUYER_ONLY.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.PROMO_WRONG_SELLER.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.PROMO_ALREADY_APPLIED.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.PROMO_NOT_CLAIMED.status()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 409 — shared state moved underneath the request
        assertThat(ErrorCode.PROMO_USED_UP.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.PROMO_CLAIM_FULL.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.PROMO_CHANGED.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.PROMO_CODE_TAKEN.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.PROMO_IN_USE.status()).isEqualTo(HttpStatus.CONFLICT);
    }
}
