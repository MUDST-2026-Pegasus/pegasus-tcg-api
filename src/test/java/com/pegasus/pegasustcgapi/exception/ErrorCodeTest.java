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
}
