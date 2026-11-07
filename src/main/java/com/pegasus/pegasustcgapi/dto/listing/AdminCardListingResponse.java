package com.pegasus.pegasustcgapi.dto.listing;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.listing.ListingStatus;
import com.pegasus.pegasustcgapi.model.pricing.PricingMode;
import com.pegasus.pegasustcgapi.model.seller.SellerStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One listing of a card, as an admin needs it to judge the listing and the shop.
 *
 * @param salePrice   what a buyer pays while a sale runs; null otherwise
 * @param purchasable whether a buyer could take one now; a listing can be ACTIVE
 *                    and still not, when its shop is closed
 * @param block       null unless an admin has suspended it
 */
public record AdminCardListingResponse(
        long id,
        long catalogVariantId,
        CardCondition condition,
        String gradingCompany,
        BigDecimal gradeValue,
        BigDecimal price,
        BigDecimal salePrice,
        OffsetDateTime saleEndsAt,
        PricingMode pricingMode,
        int quantityAvailable,
        int quantityReserved,
        int quantityTotal,
        ListingStatus status,
        String lotLabel,
        String publicNote,
        OffsetDateTime publishedAt,
        OffsetDateTime updatedAt,
        boolean purchasable,
        Seller seller,
        Block block) {

    /** @param ratingAvg null until the shop has a rating */
    public record Seller(
            long sellerProfileId,
            long userId,
            String username,
            String displayName,
            String avatarUrl,
            SellerStatus status,
            boolean onVacation,
            boolean accountActive,
            BigDecimal ratingAvg,
            int ratingCount) {
    }

    /** @param fromStatus null only for a listing blocked before reasons were recorded */
    public record Block(String reason, String blockedByUsername, OffsetDateTime blockedAt, ListingStatus fromStatus) {
    }

    /**
     * The whole card, whatever the list is filtered to. Counts listings that are or
     * were on the market: not drafts, closed ones or deleted ones.
     *
     * @param availableQuantity cards a buyer could take now
     * @param availableShops    shops those cards are at
     * @param closedShopQuantity on an ACTIVE listing, but the shop is not open for business
     */
    public record Summary(
            int shopCount,
            int listingCount,
            int availableQuantity,
            int availableShops,
            int reservedQuantity,
            int pausedQuantity,
            int blockedQuantity,
            int blockedListingCount,
            int closedShopQuantity) {
    }

    public record Page(PageResponse<AdminCardListingResponse> listings, Summary summary) {
    }
}
