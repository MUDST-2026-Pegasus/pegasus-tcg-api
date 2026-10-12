package com.pegasus.pegasustcgapi.dto;

import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * Optional checkout payload providing shipping details and buyer notes.
 */
public record CheckoutRequest(
        Long shippingAddressId,

        Map<Long, Long> shippingOptionBySeller,

        @Size(max = 500, message = "Buyer note must not exceed 500 characters")
        String buyerNote,

        List<Long> cartItemIds) {

    public CheckoutRequest(Long shippingAddressId, String buyerNote) {
        this(shippingAddressId, null, buyerNote, null);
    }

    public CheckoutRequest(Long shippingAddressId, Map<Long, Long> shippingOptionBySeller, String buyerNote) {
        this(shippingAddressId, shippingOptionBySeller, buyerNote, null);
    }
}
