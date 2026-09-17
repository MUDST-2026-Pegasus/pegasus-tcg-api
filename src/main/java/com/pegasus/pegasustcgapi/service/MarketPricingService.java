package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.config.PricingProperties;
import com.pegasus.pegasustcgapi.model.Listing;
import com.pegasus.pegasustcgapi.model.ListingStatus;
import com.pegasus.pegasustcgapi.model.MarketKey;
import com.pegasus.pegasustcgapi.model.MarketStat;
import com.pegasus.pegasustcgapi.model.PricingMode;
import com.pegasus.pegasustcgapi.repository.ListingPriceHistoryRepository;
import com.pegasus.pegasustcgapi.repository.ListingRepository;
import com.pegasus.pegasustcgapi.repository.VariantMarketStatRepository;
import com.pegasus.pegasustcgapi.service.AutoPriceCalculator.Quote;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The nightly market run [RQ-5]: the day's statistics per printing and
 * condition, then every AUTO_MEDIAN listing moved to its market.
 *
 * <p>Safe to run twice, or on two instances at once. The day's statistics are
 * computed once and kept, so a second run prices from the same median — not
 * from prices the first run already moved — reaches the same prices, and changes
 * nothing. MANUAL listings are never read, let alone written.
 *
 * <p>Each market is its own transaction. Two AUTO_MEDIAN listings of one seller
 * converging on the same price is normal and breaks nothing (there is
 * deliberately no unique index to collide with), and should one market still
 * fail, the rest of the night carries on without it.
 */
@Service
public class MarketPricingService {

    private static final Logger log = LoggerFactory.getLogger(MarketPricingService.class);

    /** PAUSED and SOLD_OUT follow the market too, so they come back at today's price rather than last month's. */
    static final Set<ListingStatus> REPRICED = EnumSet.of(
            ListingStatus.ACTIVE, ListingStatus.PAUSED, ListingStatus.SOLD_OUT);

    private final ListingRepository listings;
    private final VariantMarketStatRepository stats;
    private final ListingPriceHistoryRepository priceHistory;
    private final PlatformSettingService settings;
    private final TransactionTemplate transactions;
    private final PricingProperties properties;
    private final Clock clock;

    public MarketPricingService(
            ListingRepository listings,
            VariantMarketStatRepository stats,
            ListingPriceHistoryRepository priceHistory,
            PlatformSettingService settings,
            PlatformTransactionManager transactionManager,
            PricingProperties properties,
            Clock clock) {

        this.listings = listings;
        this.stats = stats;
        this.priceHistory = priceHistory;
        this.settings = settings;
        this.transactions = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.clock = clock;
    }

    /** Today in the market's own time zone, which is what the 03:00 schedule is in. */
    public RunResult runToday() {
        return run(LocalDate.now(clock.withZone(properties.zone())));
    }

    public RunResult run(LocalDate day) {
        OffsetDateTime now = OffsetDateTime.now(clock);

        int marketsComputed = Objects.requireNonNull(
                transactions.execute(status -> stats.computeDay(day, now, now.minusDays(30))));
        int minSample = settings.getInt(PlatformSettingService.PRICING_MEDIAN_MIN_SAMPLE);

        int repriced = 0;
        int unchanged = 0;
        int skipped = 0;
        int failed = 0;
        for (MarketKey market : listings.autoPricedMarkets(REPRICED)) {
            try {
                Tally tally = Objects.requireNonNull(
                        transactions.execute(status -> repriceMarket(market, day, minSample, now)));
                repriced += tally.repriced();
                unchanged += tally.unchanged();
                skipped += tally.skipped();
            } catch (RuntimeException e) {
                failed++;
                log.error("Auto-pricing failed for variant {} in {} on {}; the other markets carry on",
                        market.catalogVariantId(), market.condition(), day, e);
            }
        }

        RunResult result = new RunResult(day, marketsComputed, repriced, unchanged, skipped, failed);
        log.info("Market run for {}: {} market(s) computed, {} listing(s) repriced, {} unchanged, {} skipped, "
                + "{} market(s) failed", day, marketsComputed, repriced, unchanged, skipped, failed);
        return result;
    }

    /**
     * One market's AUTO_MEDIAN listings, locked, each moved to its own quote. A
     * listing with nothing usable to follow — no market today, or too thin a one —
     * keeps its price.
     */
    private Tally repriceMarket(MarketKey market, LocalDate day, int minSample, OffsetDateTime now) {
        MarketStat stat = stats.find(market, day).orElse(null);

        int repriced = 0;
        int unchanged = 0;
        int skipped = 0;
        for (Listing listing : listings.lockAutoPriced(market, REPRICED)) {
            Optional<Quote> quote = AutoPriceCalculator.quote(listing, stat, minSample);
            if (quote.isEmpty()) {
                skipped++;
            } else if (quote.get().price().compareTo(listing.price()) == 0) {
                unchanged++;
            } else if (listings.applyAutoPrice(listing.id(), quote.get().price(), now)) {
                priceHistory.insert(listing.id(), listing.price(), quote.get().price(), PricingMode.AUTO_MEDIAN,
                        null, quote.get().reason());
                repriced++;
            }
        }
        return new Tally(repriced, unchanged, skipped);
    }

    /**
     * @param marketsComputed markets that got a statistics row on this run; zero on a
     *                        repeat run the same day
     * @param listingsSkipped AUTO_MEDIAN listings left alone for want of a usable median
     * @param marketsFailed   markets whose transaction failed and was rolled back
     */
    public record RunResult(
            LocalDate statDate,
            int marketsComputed,
            int listingsRepriced,
            int listingsUnchanged,
            int listingsSkipped,
            int marketsFailed) {
    }

    private record Tally(int repriced, int unchanged, int skipped) {
    }
}
