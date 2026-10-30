package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.Promotion.DiscountType;
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

/**
 * A shop's own discount code as the S2 form sends it, for create and update (the
 * whole form each time). A shop code covers every item in the shop, is PERCENT or
 * FIXED, and the shop pays for it.
 *
 * @param claimable      buyers may collect it from the store page; needs PUBLIC
 * @param featuredOnHome Pegasus only; sending true is refused
 * @param claimRequired  Pegasus only; sending true is refused
 *
 * <p>Flags left out read as false.
 */
public record SellerPromotionRequest(
        @NotBlank @Pattern(regexp = "^\\s*[A-Za-z0-9]{4,20}\\s*$") String code,
        @NotBlank @Size(max = 120) String name,
        @Size(max = 500) String description,
        @NotNull DiscountType discountType,
        @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal discountValue,
        @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal maxDiscount,
        @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal minSpend,
        Visibility visibility,
        Boolean newBuyerOnly,
        @Min(1) Integer usageLimitTotal,
        @Min(1) Integer usageLimitPerUser,
        @NotNull OffsetDateTime startsAt,
        @NotNull OffsetDateTime endsAt,
        Boolean claimable,
        @Min(1) Integer claimLimit,
        Boolean featuredOnHome,
        Boolean claimRequired) {

    public boolean newBuyerOnlyOrFalse() {
        return Boolean.TRUE.equals(newBuyerOnly);
    }

    public boolean claimableOrFalse() {
        return Boolean.TRUE.equals(claimable);
    }
}
