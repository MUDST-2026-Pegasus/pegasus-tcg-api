package com.pegasus.pegasustcgapi.dto.sale;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.listing.PublicListingResponse;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.DiscountType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.SaleType;
import com.pegasus.pegasustcgapi.model.sale.SaleStatus;
import com.pegasus.pegasustcgapi.repository.sale.SaleBrowseRepository.Campaign;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * What the buyer's sale pages read (SAL-B1/B2) [TCG-679]. Who pays for a sale and
 * how much budget it has left are not shown.
 */
public final class BuyerSaleViews {

    private BuyerSaleViews() {
    }

    /**
     * A sale's header.
     *
     * @param status       SCHEDULED for a flash sale coming soon, ENDED once over
     * @param shop         the shop running it; null for a Pegasus campaign
     * @param listingCount listings selling at its price right now
     */
    public record SaleHeader(
            long id,
            String slug,
            String name,
            SaleType type,
            SaleStatus status,
            DiscountType discountType,
            BigDecimal discountValue,
            @Schema(nullable = true) BigDecimal maxDiscountPerUnit,
            @Schema(nullable = true) Integer perListingQuota,
            @Schema(nullable = true) Integer perBuyerLimit,
            @Schema(nullable = true) String bannerImageUrl,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            @Schema(nullable = true) Shop shop,
            long listingCount) {

        public static SaleHeader of(Campaign c) {
            var s = c.sale();
            return new SaleHeader(s.id(), s.slug(), s.name(), s.saleType(), c.status(), s.discountType(),
                    s.discountValue(), s.maxDiscountPerUnit(), s.perListingQuota(), s.perBuyerLimit(),
                    s.bannerImageUrl(), s.startsAt(), s.effectiveEnd(),
                    c.shopUsername() == null ? null : new Shop(c.shopUsername(), c.shopDisplayName()),
                    c.listingCount());
        }
    }

    /** @param username links to {@code /u/{username}} */
    public record Shop(String username, String displayName) {
    }

    /** {@code GET /sales/{slug}}: the header and a page of its listings; an ended sale has none. */
    public record SalePage(SaleHeader sale, PageResponse<PublicListingResponse> listings) {
    }

    /**
     * The home page's sale strip.
     *
     * @param flashSale     one flash sale and up to 12 of its cards; a sale coming soon
     *                      has none yet. Null when there is no flash sale.
     * @param categorySales featured Pegasus campaigns for the banner row
     */
    public record Featured(@Schema(nullable = true) FlashSale flashSale, List<SaleHeader> categorySales) {
    }

    /**
     * @param listings cards selling at the flash price now
     * @param soldOut  cards whose flash quota is used up, shown faded after
     *                 {@code listings} so the strip stays full; together at most 12
     */
    public record FlashSale(SaleHeader sale, List<PublicListingResponse> listings, List<SoldOutCard> soldOut) {
    }

    /**
     * A flash card that has sold its quota. {@code listing.pricing} is what it sells
     * for now (its asking price); {@code flashPrice} is what it sold at during the sale.
     *
     * @param discountPercent the flash discount as a whole percent of the asking price
     */
    public record SoldOutCard(PublicListingResponse listing, BigDecimal flashPrice, int discountPercent) {
    }
}
