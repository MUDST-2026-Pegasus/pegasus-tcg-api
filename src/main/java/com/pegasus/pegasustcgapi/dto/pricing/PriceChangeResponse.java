package com.pegasus.pegasustcgapi.dto.pricing;

import com.pegasus.pegasustcgapi.model.pricing.ListingPriceChange;
import com.pegasus.pegasustcgapi.model.pricing.PricingMode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A row of price history [RQ-5, RQ-6].
 *
 * @param bySystem true when the nightly job changed it; {@code changedBy} is then null
 */
public record PriceChangeResponse(
        BigDecimal oldPrice,
        BigDecimal newPrice,
        PricingMode pricingMode,
        boolean bySystem,
        Long changedBy,
        String reason,
        OffsetDateTime createdAt) {

    public static PriceChangeResponse of(ListingPriceChange c) {
        return new PriceChangeResponse(c.oldPrice(), c.newPrice(), c.pricingMode(), c.bySystem(),
                c.changedBy(), c.reason(), c.createdAt());
    }
}
