package com.pegasus.pegasustcgapi.service.sale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.listing.ListingStatus;
import com.pegasus.pegasustcgapi.model.pricing.PricingMode;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.DiscountType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.OwnerType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.SaleType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.ScopeType;
import com.pegasus.pegasustcgapi.repository.sale.ListingSalePriceRepository;
import com.pegasus.pegasustcgapi.repository.sale.PriceSaleRepository;
import com.pegasus.pegasustcgapi.repository.sale.PriceSaleRepository.NewPriceSale;
import com.pegasus.pegasustcgapi.service.listing.ListingService;
import com.pegasus.pegasustcgapi.service.listing.ListingService.PriceChange;
import com.pegasus.pegasustcgapi.service.sale.SalePriceResolver.Resolved;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Card;
import com.pegasus.pegasustcgapi.support.TestData.Listing;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * listing_sale_price against the real schema [TCG-676]. Every sale here is
 * scoped to a game of the test's own, so it never reprices another test's listings.
 */
class SalePriceRefresherIntegrationTest extends PostgresIntegrationTest {

    @Autowired SalePriceRefresher refresher;
    @Autowired PriceSaleRepository sales;
    @Autowired ListingSalePriceRepository prices;
    @Autowired ListingService listingService;

    private Seller seller;
    private short game;
    private Listing listing;

    @BeforeEach
    void listingOnSale() {
        seller = data.seller();
        game = data.game("Sale");
        Card card = data.card(game, "Sale card", "R");
        listing = data.onSale(seller, card, CardCondition.NM, 2, "1000");
    }

    @Test
    void aSaleStartsAndEndsOnTime() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long saleId = platformSale("10", now.plusHours(1), now.plusHours(2));

        refresher.refreshAll();
        assertThat(priceOf(listing)).isEmpty();

        clock.advance(Duration.ofHours(1));
        refresher.refreshAll();
        assertThat(priceOf(listing)).hasValueSatisfying(r -> {
            assertThat(r.saleId()).isEqualTo(saleId);
            assertThat(r.salePrice()).isEqualByComparingTo("900");
            assertThat(r.discountAmount()).isEqualByComparingTo("100");
            assertThat(r.fundedBy()).isEqualTo(OwnerType.PLATFORM);
            assertThat(r.endsAt()).isAtSameInstantAs(now.plusHours(2));
        });

        clock.advance(Duration.ofHours(1));
        refresher.refreshAll();
        assertThat(priceOf(listing)).isEmpty();
    }

    @Test
    void refreshingTwiceChangesNothing() {
        platformSale("10", OffsetDateTime.now(clock).minusMinutes(1), OffsetDateTime.now(clock).plusHours(1));

        assertThat(refresher.refreshListings(List.of(listing.id()))).isEqualTo(1);
        assertThat(refresher.refreshListings(List.of(listing.id()))).isZero();
    }

    @Test
    void pausingTakesThePriceAwayAndResumingBringsItBack() {
        long saleId = platformSale("10", OffsetDateTime.now(clock).minusMinutes(1),
                OffsetDateTime.now(clock).plusHours(1));
        refresher.refreshAll();
        assertThat(priceOf(listing)).isPresent();

        sales.pause(saleId, seller.userId(), "test", OffsetDateTime.now(clock));
        refresher.refreshAll();
        assertThat(priceOf(listing)).isEmpty();

        sales.resume(saleId);
        refresher.refreshAll();
        assertThat(priceOf(listing)).isPresent();
    }

    @Test
    void endingEarlyTakesThePriceAway() {
        long saleId = platformSale("10", OffsetDateTime.now(clock).minusMinutes(1),
                OffsetDateTime.now(clock).plusHours(1));
        refresher.refreshAll();

        sales.endAt(saleId, OffsetDateTime.now(clock));
        refresher.refreshAll();

        assertThat(priceOf(listing)).isEmpty();
    }

    @Test
    void theSalePriceFollowsTheListingPriceAtOnce() {
        platformSale("10", OffsetDateTime.now(clock).minusMinutes(1), OffsetDateTime.now(clock).plusHours(1));
        refresher.refreshAll();

        listingService.changePrice(seller.userId(), listing.id(), manual("800"));

        assertThat(priceOf(listing).orElseThrow().salePrice()).isEqualByComparingTo("720");
    }

    @Test
    void closingTheListingTakesThePriceAway() {
        platformSale("10", OffsetDateTime.now(clock).minusMinutes(1), OffsetDateTime.now(clock).plusHours(1));
        refresher.refreshAll();

        listingService.changeStatus(seller.userId(), listing.id(), ListingStatus.PAUSED);

        assertThat(priceOf(listing)).isEmpty();
    }

    @Test
    void lowestPriceWinsAndATieGoesToPegasus() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        platformSale("10", now.minusMinutes(1), now.plusHours(1));
        long shop = shopSale("20", now.minusMinutes(1), now.plusHours(1));
        refresher.refreshAll();
        assertThat(priceOf(listing).orElseThrow().saleId()).isEqualTo(shop);

        long pegasus = platformSale("20", now.minusMinutes(1), now.plusHours(1));
        refresher.refreshAll();
        assertThat(priceOf(listing).orElseThrow().saleId()).isEqualTo(pegasus);
    }

    @Test
    void aFlashSaleQuotaUsedUpEndsItsPrice() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long flash = sales.insert(new NewPriceSale("Flash", "flash-" + data.tag(), OwnerType.PLATFORM, null,
                SaleType.FLASH_SALE, DiscountType.PERCENT, new BigDecimal("30"), null, ScopeType.GAME,
                List.of((long) game), List.of(), null, 2, 1, null, null, false, null,
                now.minusMinutes(1), now.plusHours(3), null));
        refresher.refreshAll();
        assertThat(priceOf(listing).orElseThrow().quotaLeft()).isEqualTo(2);

        dsl.execute("INSERT INTO price_sale_listing (sale_id, listing_id, sold_count) VALUES (?, ?, 2)",
                flash, listing.id());
        refresher.refreshAll();
        assertThat(priceOf(listing)).isEmpty();
    }

    @Test
    void raisingAPriceInAScheduledOrRunningSaleIsRefused() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long saleId = platformSale("10", now.plusDays(1), now.plusDays(2));

        assertThatThrownBy(() -> listingService.changePrice(seller.userId(), listing.id(), manual("1100")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SALE_PRICE_LOCKED));

        listingService.changePrice(seller.userId(), listing.id(), manual("950"));

        sales.pause(saleId, seller.userId(), "test", now);
        listingService.changePrice(seller.userId(), listing.id(), manual("1100"));
    }

    @Test
    void aSaleOfAnotherShopDoesNotLockThisOne() {
        Seller other = data.seller();
        OffsetDateTime now = OffsetDateTime.now(clock);
        sales.insert(new NewPriceSale("Other shop", "other-" + data.tag(), OwnerType.SELLER, other.profileId(),
                SaleType.SHOP_SALE, DiscountType.PERCENT, new BigDecimal("10"), null, ScopeType.SHOP,
                List.of(), List.of(), null, null, null, null, null, false, null,
                now.minusMinutes(1), now.plusHours(1), null));

        listingService.changePrice(seller.userId(), listing.id(), manual("1100"));
    }

    @Test
    void schemaRefusesAFlashSaleLongerThan72Hours() {
        OffsetDateTime now = OffsetDateTime.now(clock);

        assertThatThrownBy(() -> sales.insert(new NewPriceSale("Long", "long-" + data.tag(), OwnerType.PLATFORM,
                null, SaleType.FLASH_SALE, DiscountType.PERCENT, new BigDecimal("10"), null, ScopeType.ALL,
                List.of(), List.of(), null, 5, null, null, null, false, null,
                now, now.plusHours(73), null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void schemaRefusesAFixedPriceOutsideAListingsSale() {
        OffsetDateTime now = OffsetDateTime.now(clock);

        assertThatThrownBy(() -> sales.insert(new NewPriceSale("Fixed", "fixed-" + data.tag(), OwnerType.SELLER,
                seller.profileId(), SaleType.SHOP_SALE, DiscountType.FIXED_PRICE, BigDecimal.ZERO, null,
                ScopeType.SHOP, List.of(), List.of(), null, null, null, null, null, false, null,
                now, now.plusDays(1), null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------- helpers ----------

    private long platformSale(String percent, OffsetDateTime starts, OffsetDateTime ends) {
        return sales.insert(new NewPriceSale("Game sale", "game-" + data.tag(), OwnerType.PLATFORM, null,
                SaleType.CATEGORY_SALE, DiscountType.PERCENT, new BigDecimal(percent), null, ScopeType.GAME,
                List.of((long) game), List.of(), null, null, null, null, null, false, null, starts, ends, null));
    }

    private long shopSale(String percent, OffsetDateTime starts, OffsetDateTime ends) {
        return sales.insert(new NewPriceSale("Shop sale", "shop-" + data.tag(), OwnerType.SELLER,
                seller.profileId(), SaleType.SHOP_SALE, DiscountType.PERCENT, new BigDecimal(percent), null,
                ScopeType.SHOP, List.of(), List.of(), null, null, null, null, null, false, null, starts, ends,
                null));
    }

    private Optional<Resolved> priceOf(Listing l) {
        return prices.findByListingId(l.id());
    }

    private static PriceChange manual(String price) {
        return new PriceChange(PricingMode.MANUAL, new BigDecimal(price), null, null, null);
    }
}
