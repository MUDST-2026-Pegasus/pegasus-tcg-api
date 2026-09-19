package com.pegasus.pegasustcgapi.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Full cart representation with all items and financial summary.
 */
public record CartResponse(
        long id,
        Long userId,
        String sessionKey,
        String currency,
        List<CartItemResponse> items,
        int totalQuantity,
        BigDecimal itemsSubtotal) {
}
