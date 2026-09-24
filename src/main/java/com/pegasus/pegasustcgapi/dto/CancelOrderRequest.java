package com.pegasus.pegasustcgapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Request payload for buyer order cancellation.
 *
 * <p>The size cap matches {@code seller_order.cancel_reason varchar(255)}: without it
 * a longer reason only fails once the UPDATE reaches Postgres, as a 500 rather than
 * a 400 naming the field.
 */
public record CancelOrderRequest(
        @Schema(description = "Why the buyer is cancelling", example = "Ordered the wrong printing")
        @Size(max = 255, message = "must be at most 255 characters")
        String reason
) {
}
