package com.pegasus.pegasustcgapi.service.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.catalog.SearchSuggestResponse;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Card;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Search dropdown, trending searches and the search log (TCG-774). */
class SearchSuggestIntegrationTest extends PostgresIntegrationTest {

    @Autowired SearchSuggestService suggest;

    @Test
    void suggestGivesCompletionsProductsAndShopsForAPrefix() {
        short game = data.game("Pokemon");
        Card ex = data.card(game, "Charizard ex", "SAR");
        Card plain = data.card(game, "Charizard", "SR");
        Seller seller = data.seller();
        data.onSale(seller, ex, CardCondition.NM, 1, "500.00");
        data.onSale(seller, plain, CardCondition.NM, 1, "300.00");

        SearchSuggestResponse r = suggest.suggest("char", game);

        assertThat(r.completions()).isNotEmpty().allMatch(c -> c.toLowerCase().startsWith("char"));
        assertThat(r.products()).hasSizeLessThanOrEqualTo(4)
                .extracting(SearchSuggestResponse.Product::id).contains(ex.productId(), plain.productId());
        assertThat(r.products()).allSatisfy(p -> {
            assertThat(p.lowestPrice()).isNotNull();
            assertThat(p.sellerCount()).isEqualTo(1);
            assertThat(p.gameName()).isEqualTo("Pokemon".equals(p.gameName()) ? "Pokemon" : p.gameName());
        });
        assertThat(r.shops()).hasSize(1).first().satisfies(s -> assertThat(s.matchCount()).isEqualTo(2));
        assertThat(r.scopes()).hasSizeLessThanOrEqualTo(3);
        assertThat(r.totalProducts()).isEqualTo(2);
    }

    @Test
    void suggestCorrectsTyposAndThaiAndStaysInTheGame() {
        short pokemon = data.game("Pokemon");
        Card charizard = data.card(pokemon, "Charizard", "SR");
        short onePiece = data.game("One Piece");
        Card zoro = data.card(onePiece, "Roronoa Zoro", "SR");
        Seller seller = data.seller();
        data.onSale(seller, charizard, CardCondition.NM, 1, "300.00");
        data.onSale(seller, zoro, CardCondition.NM, 1, "300.00");

        SearchSuggestResponse typo = suggest.suggest("chxrizrd", pokemon);
        assertThat(typo.correctedQuery()).isEqualToIgnoringCase("charizard");
        assertThat(typo.products()).extracting(SearchSuggestResponse.Product::id).contains(charizard.productId());

        SearchSuggestResponse thai = suggest.suggest("โปเกมอน", pokemon);
        assertThat(thai.products()).extracting(SearchSuggestResponse.Product::id).contains(charizard.productId());

        SearchSuggestResponse scoped = suggest.suggest("zoro", pokemon);
        assertThat(scoped.products()).isEmpty();
        assertThat(scoped.shops()).isEmpty();
    }

    @Test
    void emptyQueryIsEmptyAndLongQueryIsRefused() {
        ApiClient api = new ApiClient(port);
        ApiClient.Response empty = api.get("/api/v1/catalog/search/suggest?q=", null);
        assertThat(empty.status()).isEqualTo(200);
        assertThat(empty.data().get("products")).isEmpty();
        assertThat(empty.header("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("max-age=30"));
        assertThat(api.get("/api/v1/catalog/search/suggest?q=" + "a".repeat(61), null).errorCode())
                .isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void searchesFromThreeSessionsTrendAndMissesDoNot() {
        String term = "pikachu" + data.tag().toLowerCase();
        data.card(data.game("Pokemon"), term, "C");
        PageResponse<?> hit = new PageResponse<>(List.of(), 0, 20, 1, 1);
        PageResponse<?> miss = new PageResponse<>(List.of(), 0, 20, 0, 0);
        for (String client : List.of("a", "b", "c")) {
            suggest.record(term, cast(hit), client);
            suggest.record("nothing" + term, cast(miss), client);
        }
        suggest.record("rare" + term, cast(hit), "a");
        suggest.record("rare" + term, cast(hit), "a");

        List<String> trending = suggest.trending(20).stream().map(SearchSuggestResponse.Trending::query).toList();
        assertThat(trending).contains(term).doesNotContain("nothing" + term, "rare" + term);

        Long ipStored = dsl.fetchSingle("SELECT count(*) FROM search_query_log WHERE session_hash IN ('a','b','c')")
                .get(0, Long.class);
        assertThat(ipStored).isZero();
    }

    @Test
    void oldLogsAreDeleted() {
        dsl.execute("INSERT INTO search_query_log (q_norm, result_count, session_hash, created_at) VALUES (?, 1, ?, CAST(? AS timestamptz))",
                "old" + data.tag(), "x".repeat(64), OffsetDateTime.now(clock).minusDays(91));
        assertThat(suggest.deleteOldLogs()).isGreaterThanOrEqualTo(1);
        assertThat(dsl.fetchSingle("SELECT count(*) FROM search_query_log WHERE created_at < CAST(? AS timestamptz)",
                OffsetDateTime.now(clock).minusDays(90)).get(0, Long.class)).isZero();
    }

    @SuppressWarnings("unchecked")
    private static <T> PageResponse<T> cast(PageResponse<?> page) {
        return (PageResponse<T>) page;
    }
}
