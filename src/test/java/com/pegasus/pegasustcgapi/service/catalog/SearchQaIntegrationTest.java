package com.pegasus.pegasustcgapi.service.catalog;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.catalog.ProductSummaryResponse;
import com.pegasus.pegasustcgapi.dto.catalog.SearchFacetsResponse;
import com.pegasus.pegasustcgapi.dto.catalog.SearchShopResponse;
import com.pegasus.pegasustcgapi.dto.catalog.SearchSuggestResponse;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.repository.catalog.SearchText;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Card;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Bugs found by the search QA pass (TCG-779), kept from coming back. */
class SearchQaIntegrationTest extends PostgresIntegrationTest {

    @Autowired CatalogSearchService search;
    @Autowired SearchSuggestService suggest;

    private static ProductBrowse browse(Set<Short> games, String q) {
        return new ProductBrowse(games, null, null, null, q, Map.of(), null, true, false, Set.of(), null, null,
                0, 50, null, false);
    }

    private String username(Seller seller) {
        return dsl.select(USER_ACCOUNT.USERNAME).from(USER_ACCOUNT).where(USER_ACCOUNT.ID.eq(seller.userId()))
                .fetchSingle(USER_ACCOUNT.USERNAME);
    }

    @Test
    void facetsAndShopsCountTheAliasResultsNotTheThaiText() {
        short pokemon = data.game("Pokemon");
        Card charizard = data.card(pokemon, "Charizard ex", "SAR");
        Seller seller = data.seller();
        data.onSale(seller, charizard, CardCondition.NM, 1, "420.00");

        ProductBrowse thai = browse(Set.of(pokemon), "ชาริซาร์ด");
        PageResponse<ProductSummaryResponse> page = search.search(thai);
        assertThat(page.correctionReason()).isEqualTo("ALIAS");

        SearchFacetsResponse facets = search.facets(thai);
        assertThat(facets.totals().products()).isEqualTo(page.totalItems()).isPositive();
        assertThat(facets.totals().shops()).isEqualTo(1);
        assertThat(search.shops(thai, null, null).items()).extracting(SearchShopResponse::username)
                .containsExactly(username(seller));
    }

    @Test
    void facetsAndShopsCountTheOtherKeyboardLayoutResults() {
        short game = data.game("Pokemon");
        String name = "qwerty" + data.tag().replaceAll("[0-9]", "").toLowerCase() + "zap";
        Card card = data.card(game, name, "C");
        Seller seller = data.seller();
        data.onSale(seller, card, CardCondition.NM, 1, "10.00");
        String typedOnThai = SearchText.switchKeyboard(name);

        ProductBrowse wrongLayout = browse(Set.of(game), typedOnThai);
        PageResponse<ProductSummaryResponse> page = search.search(wrongLayout);
        assertThat(page.correctionReason()).isEqualTo("KEYBOARD_LAYOUT");
        assertThat(page.items()).extracting(ProductSummaryResponse::id).containsExactly(card.productId());

        assertThat(search.facets(wrongLayout).totals().products()).isEqualTo(1);
        assertThat(search.shops(wrongLayout, null, null).totalItems()).isEqualTo(1);
    }

    @Test
    void anUnknownCardNumberIsNotCorrectedIntoAnotherCard() {
        short game = data.game("Pokemon");
        Card hoOh = data.card(game, "Ho-Oh ex", "SIR");
        dsl.update(CATALOG_PRODUCT).set(CATALOG_PRODUCT.CARD_NUMBER, "230/182")
                .where(CATALOG_PRODUCT.ID.eq(hoOh.productId())).execute();
        assertThat(search.search(browse(Set.of(game), "230/182")).items()).hasSize(1);

        for (String q : List.of("020/189", "20/189", "020-189")) {
            PageResponse<ProductSummaryResponse> page = search.search(browse(Set.of(game), q));
            assertThat(page.items()).as(q).isEmpty();
            assertThat(page.correctedQuery()).as(q).isNull();
        }
    }

    @Test
    void twoLettersMatchTheStartOfAWordOnly() {
        short game = data.game("Yu-Gi-Oh");
        Card inside = data.card(game, "Diabellstar Vengeance", "SR");
        Card wordStart = data.card(game, "Charizard ex", "SR");

        assertThat(search.search(browse(Set.of(game), "ab")).items()).isEmpty();
        assertThat(search.search(browse(Set.of(game), "ex")).items()).extracting(ProductSummaryResponse::id)
                .containsExactly(wordStart.productId()).doesNotContain(inside.productId());
    }

    @Test
    void suggestCountsWhatViewAllOpensAndShowsInStockRows() {
        short game = data.game("Pokemon");
        String q = "Suggestq" + data.tag();
        Card selling = data.card(game, q + " A", "SR");
        data.card(game, q + " B", "SR");
        Seller seller = data.seller();
        data.onSale(seller, selling, CardCondition.NM, 1, "99.00");

        SearchSuggestResponse dropdown = suggest.suggest(q, game);

        assertThat(dropdown.products()).extracting(SearchSuggestResponse.Product::id)
                .containsExactly(selling.productId());
        assertThat(dropdown.totalProducts()).isEqualTo(search.search(browse(Set.of(game), q)).totalItems())
                .isEqualTo(2);
        assertThat(dropdown.shops()).extracting(SearchSuggestResponse.Shop::username)
                .containsExactly(username(seller));
    }

    @Test
    void trendingRanksByHowManySessionsSearched() {
        String wide = "wide" + data.tag().toLowerCase();
        String loud = "loud" + data.tag().toLowerCase();
        PageResponse<ProductSummaryResponse> hit = new PageResponse<>(List.<ProductSummaryResponse>of(), 0, 20, 1, 1);
        for (String client : List.of("s1", "s2", "s3", "s4")) {
            suggest.record(wide, hit, client);
        }
        for (int i = 0; i < 10; i++) {
            suggest.record(loud, hit, i < 3 ? "s" + i : "s0");
        }

        List<String> trending = suggest.trending(20).stream().map(SearchSuggestResponse.Trending::query).toList();
        assertThat(trending).containsSubsequence(wide, loud);
    }
}
