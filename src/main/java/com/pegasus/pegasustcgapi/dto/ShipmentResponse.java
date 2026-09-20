package com.pegasus.pegasustcgapi.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Details of a shipment associated with a seller order.
 */
public record ShipmentResponse(
        long id,
        long sellerOrderId,
        String carrierCode,
        String carrierName,
        String trackingNumber,
        String status,
        OffsetDateTime shippedAt,
        LocalDate estimatedDeliveryDate,
        OffsetDateTime deliveredAt,
        String proofImageKey,
        long createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
