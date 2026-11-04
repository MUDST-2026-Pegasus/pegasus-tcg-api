package com.pegasus.pegasustcgapi.dto.sale;

import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.DiscountType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.ScopeType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A shop's own sale as the seller form (SAL-S2) sends it, for create and for
 * update (the whole form each time) [TCG-678]. The shop pays the discount.
 *
 * <ul>
 *   <li>{@code SHOP} — every listing of the shop; {@code discountType} PERCENT or FIXED_OFF.</li>
 *   <li>{@code CATEGORY} — the shop's listings in {@code scopeIds} (catalogue category ids).</li>
 *   <li>{@code LISTINGS} — the listings in {@code items}. Either every item names its own
 *       {@code salePrice} (the sale is then FIXED_PRICE and {@code discountType} is left out or
 *       FIXED_PRICE), or no item does and {@code discountType + discountValue} applies to each.</li>
 * </ul>
 *
 * @param discountValue      percent (1–90) for PERCENT, baht per card for FIXED_OFF; unused for FIXED_PRICE
 * @param maxDiscountPerUnit cap in baht per card, PERCENT only
 * @param conditions         card conditions covered; empty or null covers all
 * @param minUnitPrice       listings priced below this stay out; null means no floor
 */
public record SellerSaleRequest(
        @NotBlank @Size(max = 120) String name,
        @NotNull ScopeType scopeType,
        List<@NotNull Long> scopeIds,
        DiscountType discountType,
        @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal discountValue,
        @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal maxDiscountPerUnit,
        List<@NotNull CardCondition> conditions,
        @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal minUnitPrice,
        @Size(max = 500) List<@NotNull @Valid Item> items,
        @NotNull OffsetDateTime startsAt,
        @NotNull OffsetDateTime endsAt) {

    /** @param salePrice the listing's price under the sale; null when the sale-wide discount applies */
    public record Item(
            @NotNull Long listingId,
            @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal salePrice) {
    }
}
