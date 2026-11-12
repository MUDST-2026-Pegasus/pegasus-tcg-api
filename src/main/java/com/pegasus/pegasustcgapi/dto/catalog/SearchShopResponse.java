package com.pegasus.pegasustcgapi.dto.catalog;

import java.math.BigDecimal;
import java.util.List;

/** A shop on the search page's shop tab (TCG-775): what it sells among the results. */
public record SearchShopResponse(
        String username,
        String displayName,
        String avatarUrl,
        BigDecimal ratingAvg,
        int ratingCount,
        int matchCount,
        BigDecimal lowestPrice,
        List<Preview> previews) {

    public record Preview(long productId, String imageUrl) {
    }
}
