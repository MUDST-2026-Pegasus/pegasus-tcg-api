package com.pegasus.pegasustcgapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Request payload for seller order fulfillment and shipping.
 *
 * <p>Every size cap here mirrors the {@code shipment} column it lands in, so an
 * over-long carrier or tracking number is a 400 naming the field instead of a
 * Postgres 22001 surfacing as a 500 halfway through the transaction.
 */
public record ShipOrderRequest(
        @Schema(description = "Name of the shipping carrier (e.g., Thailand Post, Flash Express, Kerry)", example = "Thailand Post")
        @NotBlank(message = "carrierName is required")
        @Size(max = 100, message = "must be at most 100 characters")
        String carrierName,

        @Schema(description = "Tracking or consignment number", example = "TH1234567890")
        @NotBlank(message = "trackingNumber is required")
        @Size(max = 64, message = "must be at most 64 characters")
        String trackingNumber,

        @Schema(description = "Carrier code identifier", example = "THAILAND_POST")
        @Size(max = 32, message = "must be at most 32 characters")
        String carrierCode,

        @Schema(description = "Presigned storage image key for proof of shipping", example = "shipments/proof_123.jpg")
        @Size(max = 500, message = "must be at most 500 characters")
        String proofImageKey,

        @Schema(description = "Estimated delivery date", example = "2026-09-25")
        LocalDate estimatedDeliveryDate
) {
}
