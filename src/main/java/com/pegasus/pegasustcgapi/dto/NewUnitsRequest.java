package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.service.ListingUnitService.StockIn;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * New cards straight onto a listing. The printing and condition are the
 * listing's, so they are not asked for.
 *
 * @param unitCost what one card cost; required [RQ-7]
 */
public record NewUnitsRequest(

        @Min(1) @Max(100)
        Integer quantity,

        @NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2)
        BigDecimal unitCost,

        @PastOrPresent
        OffsetDateTime acquiredAt,

        @Size(max = 64)
        String certNumber,

        @Size(max = 255)
        String unitNote) {

    public StockIn toStockIn(long listingId) {
        return new StockIn(null, null, listingId, quantity == null ? 1 : quantity, unitCost, acquiredAt,
                blankToNull(certNumber), blankToNull(unitNote));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
