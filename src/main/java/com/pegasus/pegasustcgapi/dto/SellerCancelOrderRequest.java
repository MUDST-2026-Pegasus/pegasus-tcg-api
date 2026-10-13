package com.pegasus.pegasustcgapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Why the seller is calling an order off. Required, unlike the buyer's: the buyer
 * did not choose this and is owed an explanation.
 *
 * <p>The cap matches {@code seller_order.cancel_reason} and {@code refund.reason},
 * both {@code varchar(255)}.
 */
public record SellerCancelOrderRequest(
        @Schema(description = "Shown to the buyer", example = "The card was damaged while packing")
        @NotBlank(message = "reason is required")
        @Size(max = 255, message = "must be at most 255 characters")
        String reason
) {
}
