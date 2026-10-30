package com.pegasus.pegasustcgapi.dto;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Optional checkout payload providing shipping details and buyer notes.
 *
 * @param platformCode       one Pegasus discount code, or null
 * @param sellerCodes        one shop code per shop, keyed by seller profile id
 * @param expectedGrandTotal what the buyer was last shown; a different total is
 *                           409 {@code PROMO_CHANGED} and nothing is placed
 */
public record CheckoutRequest(
        Long shippingAddressId,

        Map<Long, Long> shippingOptionBySeller,

        @Size(max = 500, message = "Buyer note must not exceed 500 characters")
        String buyerNote,

        List<Long> cartItemIds,

        @Size(max = 20) String platformCode,

        Map<Long, @Size(max = 20) String> sellerCodes,

        BigDecimal expectedGrandTotal) {

    public CheckoutRequest(Long shippingAddressId, String buyerNote) {
        this(shippingAddressId, null, buyerNote, null);
    }

    public CheckoutRequest(Long shippingAddressId, Map<Long, Long> shippingOptionBySeller, String buyerNote) {
        this(shippingAddressId, shippingOptionBySeller, buyerNote, null);
    }

    public CheckoutRequest(Long shippingAddressId, Map<Long, Long> shippingOptionBySeller, String buyerNote,
            List<Long> cartItemIds) {
        this(shippingAddressId, shippingOptionBySeller, buyerNote, cartItemIds, null, null, null);
    }

    /** Whether the buyer asked for any discount code at all. */
    public boolean hasCodes() {
        return (platformCode != null && !platformCode.isBlank())
                || (sellerCodes != null && sellerCodes.values().stream().anyMatch(c -> c != null && !c.isBlank()));
    }
}
