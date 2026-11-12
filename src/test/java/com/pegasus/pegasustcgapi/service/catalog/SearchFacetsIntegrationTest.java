package com.pegasus.pegasustcgapi.service.catalog;

import static com.pegasus.pegasustcgapi.jooq.tables.ListingSalePrice.LISTING_SALE_PRICE;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerRatingSummary.SELLER_RATING_SUMMARY;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.catalog.ProductSummaryResponse;
import com.pegasus.pegasustcgapi.dto.catalog.SearchFacetsResponse;
import com.pegasus.pegasustcgapi.dto.catalog.SearchShopResponse;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.DiscountType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.OwnerType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.SaleType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.ScopeType;
import com.pegasus.pegasustcgapi.repository.sale.PriceSaleRepository;
import com.pegasus.pegasustcgapi.repository.sale.PriceSaleRepository.NewPriceSale;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Card;
import com.pegasus.pegasustcgapi.support.TestData.Listing;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Search page data (TCG-775): tile spread, onSale, facets, shop search. */
class SearchFacetsIntegrationTest extends PostgresIntegrationTest {

    @Autowired CatalogSearchService search;
    @Autowired PriceSaleRepository sales;

    private static ProductBrowse browse(Set<Short> games, String q, boolean onSale) {
        return new ProductBrowse(games, null, null, null, q, Map.of(), null, true, false, Set.of(), null, null,
                0, 50, null, onSale);
    }

    /** Ten ratings: {@code fives} fives and the rest fours. */
    private void rate(Seller seller, int fives) {
        dsl.deleteFrom(SELLER_RATING_SUMMARY)
                .where(SELLER_RATING_SUMMARY.SELLER_PROFILE_ID.eq(seller.profileId())).execute();
        dsl.insertInto(SELLER_RATING_SUMMARY)
                .set(SELLER_RATING_SUMMARY.SELLER_PROFILE_ID, seller.profileId())
                .set(SELLER_RATING_SUMMARY.COUNT_5, fives)
                .set(SELLER_RATING_SUMMARY.COUNT_4, 10 - fives)
                .set(SELLER_RATING_SUMMARY.RATING_COUNT, 10)
                .set(SELLER_RATING_SUMMARY.RATING_SUM, fives * 5 + (10 - fives) * 4)
                .set(SELLER_RATING_SUMMARY.RATING_AVG, new BigDecimal(fives * 5 + (10 - fives) * 4).movePointLeft(1))
                .execute();
    }

    private void putOnSale(short game, Listing listing, String price) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long sale = sales.insert(new NewPriceSale("Sale", "s-" + data.tag(), OwnerType.PLATFORM, null,
                SaleType.CATEGORY_SALE, DiscountType.PERCENT, new BigDecimal("10"), null, ScopeType.GAME,
                List.of((long) game), List.of(), null, null, null, null, null, false, null,
                now.minusHours(1), now.plusDays(1), null));
        dsl.insertInto(LISTING_SALE_PRICE)
                .set(LISTING_SALE_PRICE.LISTING_ID, listing.id())
                .set(LISTING_SALE_PRICE.SALE_ID, sale)
                .set(LISTING_SALE_PRICE.SALE_PRICE, new BigDecimal(price))
                .set(LISTING_SALE_PRICE.DISCOUNT_AMOUNT, BigDecimal.ONE)
                .set(LISTING_SALE_PRICE.FUNDED_BY, "PLATFORM")
                .set(LISTING_SALE_PRICE.ENDS_AT, now.plusDays(1))
                .set(LISTING_SALE_PRICE.REFRESHED_AT, now)
                .execute();
    }

    @Test
    void tileShowsSellerCountPriceSpreadAndIgnoresSoldOutListings() {
        short game = data.game("Pokemon");
        Card card = data.card(game, "Spread " + data.tag(), "SR");
        Seller a = data.seller();
        Seller b = data.seller();
        data.onSale(a, card, CardCondition.NM, 1, "100.00");
        data.onSale(b, card, CardCondition.LP, 1, "300.00");
        Seller c = data.seller();
        data.sellOut(c, data.onSale(c, card, CardCondition.NM, 1, "900.00"));

        ProductSummaryResponse tile = search.search(browse(Set.of(game), card.name(), false)).items().getFirst();

        assertThat(tile.sellerCount()).isEqualTo(2);
        assertThat(tile.lowestPrice()).isEqualByComparingTo("100");
        assertThat(tile.highestPrice()).isEqualByComparingTo("300");
        assertThat(tile.onSale()).isFalse();
    }

    @Test
    void onSaleKeepsOnlyProductsInARunningSale() {
        short game = data.game("Pokemon");
        Card discounted = data.card(game, "Discounted", "SR");
        Card full = data.card(game, "Full price", "SR");
        Seller seller = data.seller();
        Listing cut = data.onSale(seller, discounted, CardCondition.NM, 1, "100.00");
        data.onSale(seller, full, CardCondition.NM, 1, "100.00");
        putOnSale(game, cut, "80.00");

        PageResponse<ProductSummaryResponse> page = search.search(browse(Set.of(game), null, true));

        assertThat(page.items()).extracting(ProductSummaryResponse::id).containsExactly(discounted.productId());
        assertThat(page.items().getFirst().onSale()).isTrue();
        assertThat(page.items().getFirst().lowestPrice()).isEqualByComparingTo("80");
        assertThat(page.items().getFirst().lowestRegularPrice()).isEqualByComparingTo("100");
    }

    @Test
    void facetsCountOtherGamesAndMatchTheResultTotal() {
        String q = "Facet" + data.tag();
        short pokemon = data.game("Pokemon");
        short onePiece = data.game("One Piece");
        Card p1 = data.card(pokemon, q + " A", "SR");
        data.card(pokemon, q + " B", "SR");
        data.card(onePiece, q + " C", "SR");
        Seller seller = data.seller();
        data.onSale(seller, p1, CardCondition.NM, 1, "50.00");

        ProductBrowse picked = browse(Set.of(pokemon), q, false);
        SearchFacetsResponse facets = search.facets(picked);

        assertThat(facets.games()).extracting(SearchFacetsResponse.Option::id, SearchFacetsResponse.Option::count)
                .contains(org.assertj.core.groups.Tuple.tuple((long) pokemon, 2),
                        org.assertj.core.groups.Tuple.tuple((long) onePiece, 1));
        assertThat(facets.categories()).extracting(SearchFacetsResponse.Option::count).containsOnly(1);
        assertThat(facets.categories()).hasSize(2);
        assertThat(facets.conditions()).containsExactly(new SearchFacetsResponse.ConditionCount("NM", 1));
        assertThat(facets.totals().products()).isEqualTo(search.search(picked).totalItems());
        assertThat(facets.totals().shops()).isEqualTo(1);
        assertThat(facets.totals().listings()).isEqualTo(1);
        assertThat(facets.onSaleCount()).isZero();
    }

    @Test
    void shopsRankByMatchesAndFilterByRating() {
        String q = "Shopq" + data.tag();
        short game = data.game("Pokemon");
        Card a = data.card(game, q + " A", "SR");
        Card b = data.card(game, q + " B", "SR");
        Card c = data.card(game, q + " C", "SR");
        Card d = data.card(game, q + " D", "SR");
        Seller many = data.seller();
        Seller few = data.seller();
        for (Card card : List.of(a, b, c, d)) {
            data.onSale(many, card, CardCondition.NM, 1, "100.00");
        }
        data.onSale(few, a, CardCondition.NM, 1, "70.00");
        rate(many, 2);
        rate(few, 8);

        List<SearchShopResponse> byMatches = search.shops(browse(Set.of(), q, false), null, null).items();
        assertThat(byMatches).extracting(SearchShopResponse::matchCount).containsExactly(4, 1);
        assertThat(byMatches.getFirst().previews()).hasSize(3);
        assertThat(byMatches.get(1).lowestPrice()).isEqualByComparingTo("70");

        PageResponse<SearchShopResponse> rated = search.shops(browse(Set.of(), q, false), new BigDecimal("4.5"), null);
        assertThat(rated.items()).extracting(SearchShopResponse::matchCount).containsExactly(1);
        assertThat(rated.totalItems()).isEqualTo(1);
    }
}
