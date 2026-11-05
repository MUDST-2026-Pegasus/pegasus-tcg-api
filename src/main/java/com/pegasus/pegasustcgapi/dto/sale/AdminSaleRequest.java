package com.pegasus.pegasustcgapi.dto.sale;

import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.DiscountType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.SaleType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.ScopeType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A Pegasus campaign as the admin form (SAL-A2) sends it, for create and for
 * update (the whole form each time). Once the sale has orders, an update may only
 * change what {@code AdminSaleService} lists as editable; anything else must come
 * back unchanged.
 *
 * @param saleType           FLASH_SALE or CATEGORY_SALE
 * @param discountType       PERCENT or FIXED_OFF
 * @param discountValue      percent (1–90) for PERCENT, baht per card for FIXED_OFF
 * @param maxDiscountPerUnit cap in baht per card, PERCENT only
 * @param scopeType          ALL, GAME, CATEGORY or CARD_SET
 * @param scopeIds           game, category or card set ids; empty for ALL
 * @param conditions         card conditions covered; empty or null covers all
 * @param perListingQuota    flash only: cards per listing at the sale price
 * @param perBuyerLimit      flash only: cards one buyer may buy at the sale price
 * @param budgetCap          most subsidy Pegasus pays; null means no cap
 * @param homeSort           position on the home page; null puts a newly featured sale last
 * @param excludedSellerIds  shops (seller profile ids) the campaign leaves out; null or empty for none
 *
 * <p>Flags left out read as false.
 */
public record AdminSaleRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(min = 3, max = 80) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$") String slug,
        @NotNull SaleType saleType,
        @NotNull DiscountType discountType,
        @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal discountValue,
        @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal maxDiscountPerUnit,
        @NotNull ScopeType scopeType,
        List<@NotNull Long> scopeIds,
        List<@NotNull CardCondition> conditions,
        @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal minUnitPrice,
        @Min(1) Integer perListingQuota,
        @Min(1) Integer perBuyerLimit,
        @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal budgetCap,
        @Size(max = 500) String bannerImageUrl,
        Boolean featuredOnHome,
        @Min(0) Integer homeSort,
        @NotNull OffsetDateTime startsAt,
        @NotNull OffsetDateTime endsAt,
        List<@NotNull Long> excludedSellerIds) {

    public boolean featuredOnHomeOrFalse() {
        return Boolean.TRUE.equals(featuredOnHome);
    }
}
