package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.MarketKey;
import com.pegasus.pegasustcgapi.model.PricingMode;
import com.pegasus.pegasustcgapi.repository.ListingRepository.Details;
import com.pegasus.pegasustcgapi.repository.ListingRepository.Pricing;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * A new listing: which card, in what condition, at what price.
 *
 * <p>It starts as a DRAFT with no cards. Put cards on it with
 * {@code POST /sellers/me/listings/{id}/units}, then publish it with
 * {@code PATCH /sellers/me/listings/{id}/status}.
 *
 * <p>Asking for a second listing of a card this seller already lists in the same
 * condition is allowed; the answer names the first one and asks about merging.
 *
 * @param price                  for AUTO_MEDIAN, the price until the nightly job first moves it
 * @param autoPriceOffsetPercent AUTO_MEDIAN: −5 prices five percent under the market median
 * @param imageKeys              from {@code POST /uploads/presign} with purpose
 *                               LISTING_IMAGE, after uploading; the first is the primary photo
 */
public record ListingRequest(

        @NotNull @Positive
        Long catalogVariantId,

        CardCondition condition,

        @Size(max = 20)
        String gradingCompany,

        @DecimalMin("0.5") @DecimalMax("10.0") @Digits(integer = 2, fraction = 1)
        BigDecimal gradeValue,

        @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2)
        BigDecimal price,

        PricingMode pricingMode,

        @DecimalMin("-99.99") @DecimalMax("999.99") @Digits(integer = 3, fraction = 2)
        BigDecimal autoPriceOffsetPercent,

        @DecimalMin("0.01") @Digits(integer = 12, fraction = 2)
        BigDecimal autoPriceFloor,

        @DecimalMin("0.01") @Digits(integer = 12, fraction = 2)
        BigDecimal autoPriceCeiling,

        @Size(max = 60)
        String lotLabel,

        @Size(max = 500)
        String publicNote,

        @Size(max = 10)
        List<@NotBlank @Size(max = 500) String> imageKeys) {

    public MarketKey market() {
        return new MarketKey(catalogVariantId, condition == null ? CardCondition.NM : condition);
    }

    public Details details() {
        return new Details(blankToNull(gradingCompany), gradeValue, blankToNull(lotLabel), blankToNull(publicNote));
    }

    public Pricing pricing() {
        return new Pricing(pricingMode == null ? PricingMode.MANUAL : pricingMode, price,
                autoPriceOffsetPercent, autoPriceFloor, autoPriceCeiling);
    }

    public List<String> photos() {
        return imageKeys == null ? List.of() : imageKeys.stream().map(String::trim).toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
