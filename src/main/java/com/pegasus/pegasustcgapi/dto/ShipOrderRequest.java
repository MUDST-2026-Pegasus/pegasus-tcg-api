package com.pegasus.pegasustcgapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

/**
 * Request payload for seller order fulfillment and shipping.
 */
public record ShipOrderRequest(
        @Schema(description = "Name of the shipping carrier (e.g., Thailand Post, Flash Express, Kerry)", example = "Thailand Post")
        @NotBlank(message = "carrierName is required")
        String carrierName,

        @Schema(description = "Tracking or consignment number", example = "TH1234567890")
        @NotBlank(message = "trackingNumber is required")
        String trackingNumber,

        @Schema(description = "Carrier code identifier", example = "THAILAND_POST")
        String carrierCode,

        @Schema(description = "Presigned storage image key for proof of shipping", example = "shipments/proof_123.jpg")
        String proofImageKey,

        @Schema(description = "Estimated delivery date", example = "2026-09-25")
        LocalDate estimatedDeliveryDate
) {
}
