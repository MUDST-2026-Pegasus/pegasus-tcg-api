package com.pegasus.pegasustcgapi.dto.home;

import java.math.BigDecimal;
import java.util.List;

/** Public storefront information for the automatically ranked home section. */
public record RecommendedShopResponse(
        String username,
        String displayName,
        String avatarUrl,
        BigDecimal ratingAvg,
        int ratingCount,
        int activeListingCount,
        List<Preview> previews) {

    public record Preview(long listingId, String imageUrl) {}
}
