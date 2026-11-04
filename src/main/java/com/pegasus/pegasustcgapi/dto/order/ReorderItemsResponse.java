package com.pegasus.pegasustcgapi.dto.order;

import java.math.BigDecimal;
import java.util.List;

public record ReorderItemsResponse(
    Long orderId,
    String orderNumber,
    List<ReorderItemDto> items
) {
    public record ReorderItemDto(
        Long orderItemId,
        Long catalogVariantId,
        String productSlug,
        String productName,
        String variantLabel,
        String rarity,
        String imageUrl,
        String originalCondition,
        BigDecimal originalPrice,
        Integer originalQuantity,
        String originalSellerName
    ) {}
}
