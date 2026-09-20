package com.pegasus.pegasustcgapi.dto;

import java.time.OffsetDateTime;

/**
 * Audit record of a seller order status transition.
 */
public record OrderStatusHistoryResponse(
        long id,
        long sellerOrderId,
        String fromStatus,
        String toStatus,
        Long changedBy,
        String note,
        OffsetDateTime createdAt
) {
}
