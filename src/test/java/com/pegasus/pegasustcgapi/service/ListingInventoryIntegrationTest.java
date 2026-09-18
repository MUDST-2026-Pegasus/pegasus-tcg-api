package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogCategory.CATALOG_CATEGORY;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.Game.GAME;
import static com.pegasus.pegasustcgapi.jooq.tables.InventoryMovement.INVENTORY_MOVEMENT;
import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingPriceHistory.LISTING_PRICE_HISTORY;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingUnit.LISTING_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerVariantCost.SELLER_VARIANT_COST;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;
import static com.pegasus.pegasustcgapi.jooq.tables.VariantMarketStat.VARIANT_MARKET_STAT;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.dto.SellerListingResponse;
import com.pegasus.pegasustcgapi.dto.SimilarListingResponse;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.jooq.tables.records.ListingPriceHistoryRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.ListingRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerVariantCostRecord;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.ListingStatus;
import com.pegasus.pegasustcgapi.model.ListingUnitStatus;
import com.pegasus.pegasustcgapi.model.MarketKey;
import com.pegasus.pegasustcgapi.model.PricingMode;
import com.pegasus.pegasustcgapi.port.InventoryPort;
import com.pegasus.pegasustcgapi.port.InventoryPort.ReservedUnit;
import com.pegasus.pegasustcgapi.repository.ListingRepository.Details;
import com.pegasus.pegasustcgapi.repository.ListingRepository.Pricing;
import com.pegasus.pegasustcgapi.service.ListingService.PriceChange;
import com.pegasus.pegasustcgapi.service.ListingUnitService.StockIn;
import com.pegasus.pegasustcgapi.service.MarketPricingService.RunResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The schema guide's regression tests for listings and stock, against a real
 * Postgres with every migration and trigger applied.
 *
 * <p>A throwaway container rather than the shared development database, for two
 * reasons: the races need genuinely concurrent sessions, and the market run
 * reprices every AUTO_MEDIAN listing it can see — not something to do to anyone's
 * real data. Each test makes its own seller and card, so tests share the
 * container without seeing each other's markets.
 */
@SpringBootTest
@Testcontainers
class ListingInventoryIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private DSLContext dsl;

    @Autowired
    private InventoryPort inventory;

    @Autowired
    private ListingService listings;

    @Autowired
    private ListingUnitService units;

    @Autowired
    private MarketPricingService marketPricing;

    private long userId;
    private long sellerProfileId;
    private long variantId;

    @BeforeEach
    void verifiedSellerAndACard() {
        String tag = Long.toString(System.nanoTime(), 36);

        userId = dsl.insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.EMAIL, "seller_" + tag + "@example.com")
                .set(USER_ACCOUNT.PASSWORD_HASH, "x")
                .set(USER_ACCOUNT.USERNAME, "seller_" + tag)
                .set(USER_ACCOUNT.DISPLAY_NAME, "Seller " + tag)
                .returningResult(USER_ACCOUNT.ID)
                .fetchSingle(USER_ACCOUNT.ID);

        sellerProfileId = dsl.insertInto(SELLER_PROFILE)
                .set(SELLER_PROFILE.USER_ID, userId)
                .set(SELLER_PROFILE.STATUS, "VERIFIED")
                .set(SELLER_PROFILE.VERIFIED_AT, OffsetDateTime.now())
                .returningResult(SELLER_PROFILE.ID)
                .fetchSingle(SELLER_PROFILE.ID);

        Short gameId = dsl.insertInto(GAME)
                .set(GAME.CODE, "G" + tag.toUpperCase())
                .set(GAME.NAME, "Game " + tag)
                .set(GAME.SLUG, "game-" + tag)
                .returningResult(GAME.ID)
                .fetchSingle(GAME.ID);

        Integer categoryId = dsl.insertInto(CATALOG_CATEGORY)
                .set(CATALOG_CATEGORY.GAME_ID, gameId)
                .set(CATALOG_CATEGORY.CODE, "SINGLES")
                .set(CATALOG_CATEGORY.NAME, "Singles")
                .set(CATALOG_CATEGORY.SLUG, "singles-" + tag)
                .returningResult(CATALOG_CATEGORY.ID)
                .fetchSingle(CATALOG_CATEGORY.ID);

        Long productId = dsl.insertInto(CATALOG_PRODUCT)
                .set(CATALOG_PRODUCT.GAME_ID, gameId)
                .set(CATALOG_PRODUCT.CATEGORY_ID, categoryId)
                .set(CATALOG_PRODUCT.NAME, "Pikachu ex")
                .set(CATALOG_PRODUCT.SLUG, "pikachu-ex-" + tag)
                .returningResult(CATALOG_PRODUCT.ID)
                .fetchSingle(CATALOG_PRODUCT.ID);

        variantId = dsl.insertInto(CATALOG_VARIANT)
                .set(CATALOG_VARIANT.CATALOG_PRODUCT_ID, productId)
                .set(CATALOG_VARIANT.SKU, "PKM-" + tag)
                .set(CATALOG_VARIANT.LANGUAGE_CODE, "JP")
                .set(CATALOG_VARIANT.FINISH, "FOIL")
                .returningResult(CATALOG_VARIANT.ID)
                .fetchSingle(CATALOG_VARIANT.ID);
    }

    // ---------- guide test 3, and the race behind the quantity trigger fix ----------

    @Test
    @DisplayName("guide test 3 — 20 buyers, 1 card: one gets it, 19 are turned away, and stock never goes negative")
    void twentyBuyersOneCard() throws Exception {
        long listingId = onSale(1, "1290.00", PricingMode.MANUAL, null);

        Map<Outcome, Long> outcomes = race(20, listingId);

        assertThat(outcomes).containsEntry(Outcome.GOT_A_CARD, 1L).containsEntry(Outcome.TURNED_AWAY, 19L);
        ListingRecord listing = listingRow(listingId);
        assertThat(listing.getQuantityAvailable()).isZero();
        assertThat(listing.getQuantityReserved()).isEqualTo(1);
        assertThat(listing.getStatus()).isEqualTo(ListingStatus.SOLD_OUT.name());
        assertCachedCountsMatchCards(listingId);

        // The winner's checkout falls through: the card comes back and the listing is on sale again.
        inventory.release(dsl.select(LISTING_UNIT.ID).from(LISTING_UNIT)
                .where(LISTING_UNIT.LISTING_ID.eq(listingId))
                .fetch(LISTING_UNIT.ID));

        assertThat(listingRow(listingId).getStatus()).isEqualTo(ListingStatus.ACTIVE.name());
        assertCachedCountsMatchCards(listingId);
    }

    @Test
    @DisplayName("20 buyers, 10 cards: ten get one each, and the cached counts still equal the cards")
    void twentyBuyersTenCards() throws Exception {
        long listingId = onSale(10, "1290.00", PricingMode.MANUAL, null);

        Map<Outcome, Long> outcomes = race(20, listingId);

        assertThat(outcomes).containsEntry(Outcome.GOT_A_CARD, 10L).containsEntry(Outcome.TURNED_AWAY, 10L);
        assertThat(listingRow(listingId).getQuantityReserved()).isEqualTo(10);
        assertCachedCountsMatchCards(listingId);
    }

    // ---------- guide test 2, and the books ----------

    @Test
    @DisplayName("guide test 2 — through reserve, sale and release, quantity_* equals the cards and the ledger equals the cost books")
    void countsAndBooksReconcile() {
        long listingId = onSale(4, "700.00", PricingMode.MANUAL, null);

        List<ReservedUnit> held = inventory.reserve(Map.of(listingId, 3)).get(listingId);
        inventory.commitSale(8001L, List.of(held.get(0).unitId()), null);
        inventory.release(List.of(held.get(1).unitId()));

        assertCachedCountsMatchCards(listingId);
        assertThat(listingRow(listingId).getQuantityAvailable()).isEqualTo(2);
        assertThat(listingRow(listingId).getQuantityReserved()).isEqualTo(1);

        int ledgerBalance = dsl.select(DSL.sum(INVENTORY_MOVEMENT.QUANTITY_DELTA))
                .from(INVENTORY_MOVEMENT)
                .where(INVENTORY_MOVEMENT.SELLER_PROFILE_ID.eq(sellerProfileId))
                .fetchSingle().value1().intValue();
        int onHand = dsl.fetchCount(LISTING_UNIT, LISTING_UNIT.SELLER_PROFILE_ID.eq(sellerProfileId)
                .and(LISTING_UNIT.STATUS.in(onHandStatuses())));
        SellerVariantCostRecord cost = dsl.selectFrom(SELLER_VARIANT_COST)
                .where(SELLER_VARIANT_COST.SELLER_PROFILE_ID.eq(sellerProfileId))
                .fetchSingle();

        assertThat(ledgerBalance).isEqualTo(3).isEqualTo(onHand).isEqualTo(cost.getTotalQuantity());
        assertThat(cost.getTotalCost()).isEqualByComparingTo("1500.00");
        assertThat(cost.getAverageUnitCost()).isEqualByComparingTo("500.0000");
    }

    // ---------- guide tests 4 and 5 ----------

    @Test
    @DisplayName("guide test 4 — the same seller lists the same card in the same condition twice; nothing refuses it")
    void secondListingOfTheSameCard() {
        long first = onSale(1, "1290.00", PricingMode.MANUAL, null);

        SellerListingResponse second = listings.create(userId, market(), noDetails(),
                new Pricing(PricingMode.MANUAL, new BigDecimal("1290.00"), null, null, null), List.of());

        assertThat(second.id()).isNotEqualTo(first);
        assertThat(second.similarListings()).extracting(SimilarListingResponse::id).containsExactly(first);
        assertThat(second.message("Listing created")).contains("merge");
    }

    @Test
    @DisplayName("guide test 5 — one price edit reaches all three cards on the listing, and the history names the seller")
    void priceEditReachesEveryCard() {
        long listingId = onSale(3, "600.00", PricingMode.MANUAL, null);

        listings.changePrice(userId, listingId,
                new PriceChange(PricingMode.MANUAL, new BigDecimal("700.00"), null, null, null));

        List<BigDecimal> pricesTheCardsSee = dsl.select(LISTING.PRICE)
                .from(LISTING_UNIT)
                .join(LISTING).on(LISTING.ID.eq(LISTING_UNIT.LISTING_ID))
                .where(LISTING_UNIT.LISTING_ID.eq(listingId))
                .fetch(LISTING.PRICE);
        assertThat(pricesTheCardsSee).hasSize(3).allSatisfy(price -> assertThat(price).isEqualByComparingTo("700.00"));

        ListingPriceHistoryRecord last = dsl.selectFrom(LISTING_PRICE_HISTORY)
                .where(LISTING_PRICE_HISTORY.LISTING_ID.eq(listingId))
                .orderBy(LISTING_PRICE_HISTORY.ID.desc())
                .limit(1)
                .fetchSingle();
        assertThat(last.getOldPrice()).isEqualByComparingTo("600.00");
        assertThat(last.getChangedBy()).isEqualTo(userId);
    }

    // ---------- the 03:00 job ----------

    @Test
    @DisplayName("the market run moves AUTO_MEDIAN listings — two of them onto the same price — leaves MANUAL alone, and a second run changes nothing")
    void marketRunIsIdempotent() {
        long manualLow = onSale(1, "1000.00", PricingMode.MANUAL, null);
        long manualHigh = onSale(1, "1200.00", PricingMode.MANUAL, null);
        long autoHigh = onSale(1, "5000.00", PricingMode.AUTO_MEDIAN, "-5");
        long autoLow = onSale(1, "100.00", PricingMode.AUTO_MEDIAN, "-5");
        LocalDate day = LocalDate.of(2026, 9, 14);

        RunResult first = marketPricing.run(day);

        // The median of 100, 1000, 1200 and 5000 is 1100; five percent under it is 1045.00, for both.
        assertThat(first.marketsFailed()).isZero();
        assertThat(price(autoHigh)).isEqualByComparingTo("1045.00");
        assertThat(price(autoLow)).isEqualByComparingTo("1045.00");
        assertThat(price(manualLow)).isEqualByComparingTo("1000.00");
        assertThat(price(manualHigh)).isEqualByComparingTo("1200.00");
        assertThat(systemChanges(autoHigh)).singleElement()
                .satisfies(change -> assertThat(change.getReason()).isEqualTo("median 1100.00 × (1 − 5%)"));
        assertThat(systemChanges(manualLow)).isEmpty();

        RunResult second = marketPricing.run(day);

        assertThat(second.marketsComputed()).isZero();
        assertThat(second.listingsRepriced()).isZero();
        assertThat(second.marketsFailed()).isZero();
        assertThat(systemChanges(autoHigh)).hasSize(1);
        assertThat(systemChanges(autoLow)).hasSize(1);
        assertThat(dsl.fetchCount(VARIANT_MARKET_STAT, VARIANT_MARKET_STAT.CATALOG_VARIANT_ID.eq(variantId)))
                .isEqualTo(1);
    }

    // ---------- helpers ----------

    private MarketKey market() {
        return new MarketKey(variantId, CardCondition.NM);
    }

    private static Details noDetails() {
        return new Details(null, null, null, null);
    }

    /** A listing on sale with {@code cards} cards, built through the calls a seller makes. */
    private long onSale(int cards, String price, PricingMode mode, String offsetPercent) {
        SellerListingResponse created = listings.create(userId, market(), noDetails(),
                new Pricing(mode, new BigDecimal(price), offsetPercent == null ? null : new BigDecimal(offsetPercent),
                        null, null),
                List.of());
        units.stockIn(userId, new StockIn(null, null, created.id(), cards, new BigDecimal("500.00"), null, null, null));
        listings.changeStatus(userId, created.id(), ListingStatus.ACTIVE);
        return created.id();
    }

    /**
     * {@code buyers} threads released at once, each trying to hold one card in its
     * own transaction. Anything but a clean INSUFFICIENT_STOCK — a deadlock, a
     * constraint violation — fails the test through {@link Future#get}.
     */
    private Map<Outcome, Long> race(int buyers, long listingId) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(buyers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> attempts = new ArrayList<>();
            for (int i = 0; i < buyers; i++) {
                attempts.add(pool.submit(() -> {
                    start.await();
                    try {
                        inventory.reserve(Map.of(listingId, 1));
                        return Outcome.GOT_A_CARD;
                    } catch (ApiException e) {
                        if (e.errorCode() == ErrorCode.INSUFFICIENT_STOCK) {
                            return Outcome.TURNED_AWAY;
                        }
                        throw e;
                    }
                }));
            }
            start.countDown();

            Map<Outcome, Long> outcomes = new EnumMap<>(Outcome.class);
            for (Future<Outcome> attempt : attempts) {
                outcomes.merge(attempt.get(60, TimeUnit.SECONDS), 1L, Long::sum);
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private enum Outcome {
        GOT_A_CARD,
        TURNED_AWAY
    }

    /** The invariant the trigger exists for: the cache on the listing is exactly the count of its cards. */
    private void assertCachedCountsMatchCards(long listingId) {
        ListingRecord listing = listingRow(listingId);
        int listed = dsl.fetchCount(LISTING_UNIT, LISTING_UNIT.LISTING_ID.eq(listingId)
                .and(LISTING_UNIT.STATUS.eq(ListingUnitStatus.LISTED.name())));
        int reserved = dsl.fetchCount(LISTING_UNIT, LISTING_UNIT.LISTING_ID.eq(listingId)
                .and(LISTING_UNIT.STATUS.eq(ListingUnitStatus.RESERVED.name())));

        assertThat(listing.getQuantityAvailable()).as("quantity_available").isEqualTo(listed);
        assertThat(listing.getQuantityReserved()).as("quantity_reserved").isEqualTo(reserved);
        assertThat(listing.getQuantityTotal()).as("quantity_total").isEqualTo(listed + reserved);
    }

    private static List<String> onHandStatuses() {
        return List.of(ListingUnitStatus.values()).stream()
                .filter(ListingUnitStatus::onHand)
                .map(Enum::name)
                .toList();
    }

    private ListingRecord listingRow(long listingId) {
        return dsl.selectFrom(LISTING).where(LISTING.ID.eq(listingId)).fetchSingle();
    }

    private BigDecimal price(long listingId) {
        return listingRow(listingId).getPrice();
    }

    /** Price changes the nightly job made: the rows with nobody's name on them. */
    private List<ListingPriceHistoryRecord> systemChanges(long listingId) {
        return dsl.selectFrom(LISTING_PRICE_HISTORY)
                .where(LISTING_PRICE_HISTORY.LISTING_ID.eq(listingId))
                .and(LISTING_PRICE_HISTORY.CHANGED_BY.isNull())
                .fetch();
    }
}
