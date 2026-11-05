package com.pegasus.pegasustcgapi.dto.sale;

import com.pegasus.pegasustcgapi.model.sale.PriceSale.SaleType;
import com.pegasus.pegasustcgapi.repository.sale.ListingSalePriceRepository.Shown;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;

/**
 * What a card costs right now, the same on every page that shows it [TCG-679]:
 * the listing page, the market and search, a storefront and the cart. Who pays
 * for the discount is deliberately not here; a buyer has no need to know.
 *
 * @param listPrice       the seller's asking price, struck through while a sale runs
 * @param salePrice       what one card sells for now; equal to {@code listPrice} with no sale
 * @param discountPercent {@code listPrice - salePrice} as a whole percent of {@code listPrice}; 0 with no sale
 * @param sale            the sale behind the price; null with no sale
 */
@Schema(description = "The price a buyer pays now, with the sale behind it if any")
public record SalePricing(
        BigDecimal listPrice,
        BigDecimal salePrice,
        int discountPercent,
        @Schema(nullable = true) Sale sale) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * @param slug          links to {@code /sale/{slug}}
     * @param endsAt        for the countdown
     * @param quotaLeft     flash sale: cards left at this price on this listing; null with no quota
     * @param perBuyerLimit cards one buyer may buy at this price; null with no limit
     */
    public record Sale(
            long id,
            String slug,
            String name,
            SaleType type,
            OffsetDateTime endsAt,
            @Schema(nullable = true) Integer quotaLeft,
            @Schema(nullable = true) Integer perBuyerLimit) {
    }

    public static SalePricing none(BigDecimal listPrice) {
        return new SalePricing(listPrice, listPrice, 0, null);
    }

    /**
     * @param shown the listing's sale row, or null. A row priced at or above the
     *              asking price (stale between refreshes) is no sale.
     */
    public static SalePricing of(BigDecimal listPrice, Shown shown) {
        if (shown == null || listPrice == null || shown.price().salePrice().compareTo(listPrice) >= 0) {
            return none(listPrice);
        }
        BigDecimal salePrice = shown.price().salePrice();
        int percent = listPrice.subtract(salePrice).multiply(HUNDRED)
                .divide(listPrice, 0, RoundingMode.HALF_UP).intValueExact();
        return new SalePricing(listPrice, salePrice, percent, new Sale(
                shown.price().saleId(), shown.slug(), shown.name(), shown.saleType(),
                shown.price().endsAt(), shown.price().quotaLeft(), shown.perBuyerLimit()));
    }

    public boolean onSale() {
        return sale != null;
    }
}
