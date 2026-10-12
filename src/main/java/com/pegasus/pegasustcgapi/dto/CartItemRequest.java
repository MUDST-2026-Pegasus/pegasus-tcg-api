package com.pegasus.pegasustcgapi.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Payload to add or update an item in a cart.
 */
public record CartItemRequest(
        @NotNull(message = "Listing ID is required")
        @Positive(message = "Listing ID must be positive")
        Long listingId,

        @Min(value = 1, message = "Quantity must be at least 1")
        Integer quantity) {

    public int resolvedQuantity() {
        return quantity == null ? 1 : quantity;
    }
}
