package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.model.Listing;
import com.pegasus.pegasustcgapi.model.MarketStat;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * The AUTO_MEDIAN formula [RQ-5], free of I/O so tests can pin it down:
 *
 * <pre>
 * price = median × (1 + offset / 100), rounded to satang, then held between floor and ceiling
 * </pre>
 *
 * e.g. median 1,358.00 with offset −5 is 1,290.10.
 */
public final class AutoPriceCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private AutoPriceCalculator() {
    }

    /**
     * @param stat      the day's market; null when there is none
     * @param minSample the {@code pricing.median_min_sample} setting
     * @return empty when the market says nothing usable — no median, too few
     *         listings behind it, or a result that is not a positive price. The
     *         listing then keeps the price it has.
     */
    public static Optional<Quote> quote(Listing listing, MarketStat stat, int minSample) {
        if (stat == null || stat.medianPrice() == null || stat.sampleSize() < minSample) {
            return Optional.empty();
        }
        BigDecimal offset = listing.autoPriceOffsetPercent() == null
                ? BigDecimal.ZERO
                : listing.autoPriceOffsetPercent();

        BigDecimal price = stat.medianPrice()
                .multiply(BigDecimal.ONE.add(offset.divide(HUNDRED)))
                .setScale(2, RoundingMode.HALF_UP);

        String heldAt = null;
        if (listing.autoPriceFloor() != null && price.compareTo(listing.autoPriceFloor()) < 0) {
            price = listing.autoPriceFloor().setScale(2, RoundingMode.HALF_UP);
            heldAt = "floor";
        }
        if (listing.autoPriceCeiling() != null && price.compareTo(listing.autoPriceCeiling()) > 0) {
            price = listing.autoPriceCeiling().setScale(2, RoundingMode.HALF_UP);
            heldAt = "ceiling";
        }
        if (price.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(new Quote(price, reason(stat.medianPrice(), offset, heldAt, price)));
    }

    /** Written into {@code listing_price_history.reason}, e.g. {@code median 1358.00 × (1 − 5%)}. */
    static String reason(BigDecimal median, BigDecimal offset, String heldAt, BigDecimal price) {
        StringBuilder reason = new StringBuilder("median ").append(median.setScale(2, RoundingMode.HALF_UP));
        if (offset.signum() != 0) {
            reason.append(" × (1 ")
                    .append(offset.signum() < 0 ? "−" : "+")
                    .append(' ')
                    .append(offset.abs().stripTrailingZeros().toPlainString())
                    .append("%)");
        }
        if (heldAt != null) {
            reason.append(", held at ").append(heldAt).append(' ').append(price);
        }
        return reason.toString();
    }

    /** @param reason why, in the words the price history shows */
    public record Quote(BigDecimal price, String reason) {
    }
}
