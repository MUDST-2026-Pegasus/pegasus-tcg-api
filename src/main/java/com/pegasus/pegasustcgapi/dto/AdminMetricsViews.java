package com.pegasus.pegasustcgapi.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The admin overview's numbers, raw. Percent changes are left to the page: it has
 * both {@code value} and {@code previous}, and knows how it wants to word a jump
 * from zero.
 */
public final class AdminMetricsViews {

    private AdminMetricsViews() {}

    /** @param month {@code YYYY-MM}; every range is half-open and cut in Bangkok time */
    public record Period(
            String month,
            OffsetDateTime from,
            OffsetDateTime to,
            OffsetDateTime previousFrom,
            OffsetDateTime previousTo) {
    }

    /** @param sparkline eight weekly totals, oldest first, the last being the current week */
    public record MoneyStat(BigDecimal value, BigDecimal previous, List<BigDecimal> sparkline) {
    }

    public record CountStat(long value, long previous, List<Long> sparkline) {
    }

    /**
     * Only as of now: listing status has no history, so there is nothing to
     * compare with and no sparkline.
     *
     * @param sellerCount shops with at least one active listing
     */
    public record ActiveListingsStat(long value, long sellerCount) {
    }

    /**
     * @param averageRatePercent commission over the items it was charged on, null when
     *                           nothing completed in the month
     */
    public record CommissionStat(
            BigDecimal value,
            BigDecimal previous,
            List<BigDecimal> sparkline,
            BigDecimal averageRatePercent) {
    }

    public record Stats(
            MoneyStat gmv,
            CountStat newSellers,
            ActiveListingsStat activeListings,
            CommissionStat commission) {
    }

    /** @param month {@code YYYY-MM} */
    public record MonthlySales(String month, BigDecimal singles, BigDecimal sealed) {
    }

    public record TopSeller(long sellerProfileId, String username, String displayName, BigDecimal amount) {
    }

    /**
     * @param gameId  null on the one slice that gathers every game past the top four
     * @param others  true on that slice
     * @param percent of the month's sales, one decimal place
     */
    public record GameShare(Short gameId, String gameName, BigDecimal amount, BigDecimal percent,
            boolean others) {
    }

    public record Overview(
            Period period,
            Stats stats,
            List<MonthlySales> monthlySales,
            List<TopSeller> topSellers,
            List<GameShare> gameShare) {
    }
}
