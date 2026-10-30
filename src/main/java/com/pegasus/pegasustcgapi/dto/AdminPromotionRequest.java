package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.Promotion.DiscountType;
import com.pegasus.pegasustcgapi.model.Promotion.ScopeType;
import com.pegasus.pegasustcgapi.model.Promotion.Visibility;
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
 * A Pegasus discount code as the admin form sends it, for create and for update
 * (the whole form each time). Once the code has been used, an update may only
 * change what {@code AdminPromotionService} lists as editable; anything else must
 * come back unchanged.
 *
 * @param discountValue percent for PERCENT, baht for FIXED; ignored for FREE_SHIPPING
 * @param scopeIds      game ids for GAME, category ids for CATEGORY, empty for ALL
 * @param homeSort      position on the home page; null puts a newly featured code last
 */
public record AdminPromotionRequest(
        @NotBlank @Pattern(regexp = "^\\s*[A-Za-z0-9]{4,20}\\s*$") String code,
        @NotBlank @Size(max = 120) String name,
        @Size(max = 500) String description,
        @NotNull DiscountType discountType,
        @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal discountValue,
        @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal maxDiscount,
        @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal minSpend,
        ScopeType scopeType,
        List<Long> scopeIds,
        Visibility visibility,
        boolean newBuyerOnly,
        @Min(1) Integer usageLimitTotal,
        @Min(1) Integer usageLimitPerUser,
        @NotNull OffsetDateTime startsAt,
        @NotNull OffsetDateTime endsAt,
        boolean claimable,
        boolean claimRequired,
        @Min(1) Integer claimLimit,
        boolean featuredOnHome,
        @Min(0) Integer homeSort) {
}
