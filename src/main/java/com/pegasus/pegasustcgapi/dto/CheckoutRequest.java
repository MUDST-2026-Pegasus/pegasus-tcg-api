package com.pegasus.pegasustcgapi.dto;

import jakarta.validation.constraints.Size;

/**
 * Optional checkout payload providing shipping details and buyer notes.
 */
public record CheckoutRequest(
        Long shippingAddressId,

        @Size(max = 500, message = "Buyer note must not exceed 500 characters")
        String buyerNote) {
}
