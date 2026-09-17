package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.PricingMode;
import com.pegasus.pegasustcgapi.service.ListingService.PriceChange;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * How a listing is priced from now on. One price covers every card on the
 * listing, so this moves all of them at once.
 *
 * @param price required for MANUAL; for AUTO_MEDIAN, optional — absent keeps the
 *              current price until the nightly job moves it
 */
public record ListingPriceRequest(

        @NotNull
        PricingMode pricingMode,

        @DecimalMin("0.01") @Digits(integer = 12, fraction = 2)
        BigDecimal price,

        @DecimalMin("-99.99") @DecimalMax("999.99") @Digits(integer = 3, fraction = 2)
        BigDecimal autoPriceOffsetPercent,

        @DecimalMin("0.01") @Digits(integer = 12, fraction = 2)
        BigDecimal autoPriceFloor,

        @DecimalMin("0.01") @Digits(integer = 12, fraction = 2)
        BigDecimal autoPriceCeiling) {

    public PriceChange toChange() {
        return new PriceChange(pricingMode, price, autoPriceOffsetPercent, autoPriceFloor, autoPriceCeiling);
    }
}
