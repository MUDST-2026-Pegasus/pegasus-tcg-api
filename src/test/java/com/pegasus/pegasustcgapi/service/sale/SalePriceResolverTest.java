package com.pegasus.pegasustcgapi.service.sale;

import static com.pegasus.pegasustcgapi.support.PriceSaleFixture.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.listing.ListingStatus;
import com.pegasus.pegasustcgapi.model.sale.PriceSale;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.OwnerType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.ScopeType;
import com.pegasus.pegasustcgapi.service.sale.SalePriceResolver.ListingFacts;
import com.pegasus.pegasustcgapi.service.sale.SalePriceResolver.Resolved;
import com.pegasus.pegasustcgapi.service.sale.SalePriceResolver.SaleListing;
import com.pegasus.pegasustcgapi.support.PriceSaleFixture;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SalePriceResolverTest {

    private static final long SHOP = 7;
    private static final long OTHER_SHOP = 8;

    private final Map<String, SaleListing> rows = new HashMap<>();

    @Test
    void percentOffWithAPerCardCap() {
        PriceSale tenPercent = PriceSaleFixture.platform().percent("10", null).build();
        PriceSale capped = PriceSaleFixture.platform().percent("50", "100").build();

        assertThat(only(tenPercent, listing("1000"))).hasValueSatisfying(r -> {
            assertThat(r.salePrice()).isEqualByComparingTo("900");
            assertThat(r.discountAmount()).isEqualByComparingTo("100");
            assertThat(r.fundedBy()).isEqualTo(OwnerType.PLATFORM);
            assertThat(r.endsAt()).isEqualTo(tenPercent.endsAt());
            assertThat(r.quotaLeft()).isNull();
        });
        assertThat(only(capped, listing("1000")).orElseThrow().salePrice()).isEqualByComparingTo("900");
    }

    @Test
    void percentRoundsToTheSatang() {
        PriceSale s = PriceSaleFixture.platform().percent("15", null).build();

        assertThat(only(s, listing("99.99")).orElseThrow().discountAmount()).isEqualByComparingTo("15.00");
    }

    @Test
    void fixedOffNeverGoesBelowOneBaht() {
        PriceSale s = PriceSaleFixture.platform().fixedOff("200").build();

        assertThat(only(s, listing("1000")).orElseThrow().salePrice()).isEqualByComparingTo("800");
        assertThat(only(s, listing("150")).orElseThrow().salePrice()).isEqualByComparingTo("1");
        assertThat(only(s, listing("1"))).isEmpty();
    }

    @Test
    void fixedPriceComesFromTheListingRow() {
        PriceSale s = PriceSaleFixture.shop(SHOP).fixedPrice().build();
        ListingFacts chosen = listing(1, "500");
        ListingFacts notChosen = listing(2, "500");
        row(s, chosen, new SaleListing(new BigDecimal("349"), null, 0));

        assertThat(only(s, chosen).orElseThrow().salePrice()).isEqualByComparingTo("349");
        assertThat(only(s, chosen).orElseThrow().fundedBy()).isEqualTo(OwnerType.SELLER);
        assertThat(only(s, notChosen)).isEmpty();
    }

    @Test
    void fixedPriceAtOrAboveTheAskingPriceIsNoSale() {
        PriceSale s = PriceSaleFixture.shop(SHOP).fixedPrice().build();
        ListingFacts l = listing(1, "300");
        row(s, l, new SaleListing(new BigDecimal("300"), null, 0));

        assertThat(only(s, l)).isEmpty();
    }

    @Test
    void lowestPriceWinsAcrossOverlappingSales() {
        PriceSale tenPercent = PriceSaleFixture.platform().percent("10", null).build();
        PriceSale shopTwenty = PriceSaleFixture.shop(SHOP).percent("20", null).build();
        PriceSale fiftyOff = PriceSaleFixture.platform().fixedOff("50").build();

        Resolved r = resolve(listing("1000"), tenPercent, shopTwenty, fiftyOff).orElseThrow();

        assertThat(r.saleId()).isEqualTo(shopTwenty.id());
        assertThat(r.salePrice()).isEqualByComparingTo("800");
    }

    @Test
    void onATiePegasusPaysSoTheShopKeepsItsMoney() {
        PriceSale shop = PriceSaleFixture.shop(SHOP).percent("20", null).build();
        PriceSale pegasus = PriceSaleFixture.platform().fixedOff("200").build();

        Resolved r = resolve(listing("1000"), shop, pegasus).orElseThrow();

        assertThat(r.saleId()).isEqualTo(pegasus.id());
        assertThat(r.fundedBy()).isEqualTo(OwnerType.PLATFORM);
    }

    @Test
    void scopeByGameCategoryAndCardSet() {
        ListingFacts l = new ListingFacts(1, SHOP, 3, 30, 300L, CardCondition.NM, new BigDecimal("100"),
                ListingStatus.ACTIVE);
        ListingFacts noSet = new ListingFacts(2, SHOP, 3, 30, null, CardCondition.NM, new BigDecimal("100"),
                ListingStatus.ACTIVE);

        assertThat(only(PriceSaleFixture.platform().scope(ScopeType.GAME, 3L).build(), l)).isPresent();
        assertThat(only(PriceSaleFixture.platform().scope(ScopeType.GAME, 4L).build(), l)).isEmpty();
        assertThat(only(PriceSaleFixture.platform().scope(ScopeType.CATEGORY, 30L).build(), l)).isPresent();
        assertThat(only(PriceSaleFixture.platform().scope(ScopeType.CATEGORY, 3L).build(), l)).isEmpty();
        assertThat(only(PriceSaleFixture.platform().scope(ScopeType.CARD_SET, 300L).build(), l)).isPresent();
        assertThat(only(PriceSaleFixture.platform().scope(ScopeType.CARD_SET, 300L).build(), noSet)).isEmpty();
    }

    @Test
    void aShopSaleReachesOnlyThatShop() {
        PriceSale s = PriceSaleFixture.shop(SHOP).build();
        PriceSale shopCategory = PriceSaleFixture.shop(SHOP).scope(ScopeType.CATEGORY, 30L).build();
        ListingFacts other = new ListingFacts(9, OTHER_SHOP, 3, 30, null, CardCondition.NM,
                new BigDecimal("100"), ListingStatus.ACTIVE);

        assertThat(only(s, listing("100"))).isPresent();
        assertThat(only(s, other)).isEmpty();
        assertThat(only(shopCategory, listing("100"))).isPresent();
        assertThat(only(shopCategory, other)).isEmpty();
    }

    @Test
    void aCampaignSkipsTheShopsItLeavesOut() {
        PriceSale campaign = PriceSaleFixture.platform().excluding(OTHER_SHOP).build();
        ListingFacts other = new ListingFacts(9, OTHER_SHOP, 3, 30, null, CardCondition.NM,
                new BigDecimal("100"), ListingStatus.ACTIVE);

        assertThat(only(campaign, listing("100"))).isPresent();
        assertThat(only(campaign, other)).isEmpty();
        assertThat(SalePriceResolver.covers(campaign, other, null)).isFalse();
    }

    @Test
    void conditionsAndMinimumPriceFilterListings() {
        PriceSale nmOnly = PriceSaleFixture.platform().conditions(CardCondition.NM, CardCondition.LP).build();
        PriceSale over500 = PriceSaleFixture.platform().minUnitPrice("500").build();
        ListingFacts played = new ListingFacts(1, SHOP, 3, 30, null, CardCondition.HP, new BigDecimal("1000"),
                ListingStatus.ACTIVE);

        assertThat(only(nmOnly, listing("1000"))).isPresent();
        assertThat(only(nmOnly, played)).isEmpty();
        assertThat(only(over500, listing("500"))).isPresent();
        assertThat(only(over500, listing("499.99"))).isEmpty();
    }

    @Test
    void onlyActiveListingsTakeASalePrice() {
        PriceSale s = PriceSaleFixture.platform().build();
        for (ListingStatus status : ListingStatus.values()) {
            ListingFacts l = new ListingFacts(1, SHOP, 3, 30, null, CardCondition.NM, new BigDecimal("100"), status);
            assertThat(resolve(l, s).isPresent()).as(status.name()).isEqualTo(status == ListingStatus.ACTIVE);
        }
    }

    @Test
    void flashSaleStopsCountingOnceTheListingQuotaIsUsed() {
        PriceSale flash = PriceSaleFixture.flash(5).build();
        PriceSale shop = PriceSaleFixture.shop(SHOP).percent("5", null).build();
        ListingFacts fresh = listing(1, "1000");
        ListingFacts selling = listing(2, "1000");
        ListingFacts gone = listing(3, "1000");
        ListingFacts raised = listing(4, "1000");
        row(flash, selling, new SaleListing(null, null, 3));
        row(flash, gone, new SaleListing(null, null, 5));
        row(flash, raised, new SaleListing(null, 8, 5));

        assertThat(resolve(fresh, flash).orElseThrow().quotaLeft()).isEqualTo(5);
        assertThat(resolve(selling, flash).orElseThrow().quotaLeft()).isEqualTo(2);
        assertThat(resolve(gone, flash)).isEmpty();
        assertThat(resolve(gone, flash, shop).orElseThrow().saleId()).isEqualTo(shop.id());
        assertThat(resolve(raised, flash).orElseThrow().quotaLeft()).isEqualTo(3);
    }

    @Test
    void salesThatAreNotRunningAreSkipped() {
        PriceSale scheduled = PriceSaleFixture.platform().window(NOW.plusHours(1), NOW.plusDays(1)).build();
        PriceSale paused = PriceSaleFixture.platform().pausedAt(NOW.minusMinutes(5)).build();
        PriceSale ended = PriceSaleFixture.platform().endedAt(NOW.minusMinutes(5)).build();
        PriceSale broke = PriceSaleFixture.platform().budget("100", "100").build();

        assertThat(resolve(listing("1000"), scheduled, paused, ended, broke)).isEmpty();
    }

    @Test
    void anEarlyEndIsWhatTheBuyerSees() {
        PriceSale s = PriceSaleFixture.platform().endedAt(NOW.plusHours(2)).build();

        assertThat(resolve(listing("100"), s).orElseThrow().endsAt()).isEqualTo(NOW.plusHours(2));
    }

    @Test
    void coversIgnoresDatesPauseAndQuota() {
        PriceSale scheduled = PriceSaleFixture.flash(1).window(NOW.plusHours(1), NOW.plusHours(5))
                .pausedAt(NOW).build();
        ListingFacts l = listing(1, "100");

        assertThat(SalePriceResolver.covers(scheduled, l, new SaleListing(null, null, 1))).isTrue();
        assertThat(SalePriceResolver.price(scheduled, l, new SaleListing(null, null, 1))).isEmpty();
    }

    // ---------- helpers ----------

    private ListingFacts listing(String price) {
        return listing(1, price);
    }

    private ListingFacts listing(long id, String price) {
        return new ListingFacts(id, SHOP, 3, 30, null, CardCondition.NM, new BigDecimal(price), ListingStatus.ACTIVE);
    }

    private void row(PriceSale sale, ListingFacts listing, SaleListing row) {
        rows.put(sale.id() + ":" + listing.listingId(), row);
    }

    private Optional<Resolved> only(PriceSale sale, ListingFacts listing) {
        return resolve(listing, sale);
    }

    private Optional<Resolved> resolve(ListingFacts listing, PriceSale... sales) {
        return SalePriceResolver.resolve(listing, List.of(sales),
                (saleId, listingId) -> rows.get(saleId + ":" + listingId), NOW);
    }
}
