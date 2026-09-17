package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.service.ListingUnitService.StockIn;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Cards coming into the seller's stock. Each becomes its own row with its own
 * UUID, and each gets a line in the stock ledger.
 *
 * @param condition absent means NM, or the listing's condition when one is named
 * @param listingId put the cards straight on this listing; absent keeps them in hand
 * @param unitCost  what one card cost. Required: profit cannot be worked out later
 *                  for stock that came in without a cost [RQ-7]
 * @param certNumber a graded slab's number; only with quantity 1
 */
public record StockInRequest(

        @NotNull @Positive
        Long catalogVariantId,

        CardCondition condition,

        @Positive
        Long listingId,

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

    public StockIn toStockIn() {
        return new StockIn(catalogVariantId, condition, listingId, quantity == null ? 1 : quantity, unitCost,
                acquiredAt, blankToNull(certNumber), blankToNull(unitNote));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
