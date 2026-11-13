package com.pegasus.pegasustcgapi.service.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.catalog.ProductSummaryResponse;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Card;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Thai search aliases (TCG-773), against the seeded search_alias table. */
class SearchAliasIntegrationTest extends PostgresIntegrationTest {

    private static final String PATH = "/api/v1/admin/search-aliases";

    @Autowired CatalogSearchService search;

    private PageResponse<ProductSummaryResponse> find(Short game, String q) {
        return search.search(new ProductBrowse(game == null ? Set.of() : Set.of(game), null, null, null, q,
                Map.of(), null, true, false, Set.of(), null, null, 0, 50));
    }

    @Test
    void thaiGameNameFindsPokemonWithoutAnyThaiNameInTheCatalogue() {
        short pokemon = data.game("Pokemon");
        Card card = data.card(pokemon, "Pikachu", "C");

        for (String q : new String[] {"โปเกมอน", "โปเกม่อน"}) {
            PageResponse<ProductSummaryResponse> page = find(pokemon, q);
            assertThat(page.items()).as(q).extracting(ProductSummaryResponse::id).containsExactly(card.productId());
            assertThat(page.correctionReason()).as(q).isEqualTo("ALIAS");
        }
    }

    @Test
    void thaiCardNameIsReplacedAndOtherWordsKept() {
        short pokemon = data.game("Pokemon");
        Card charizard = data.card(pokemon, "Charizard ex", "SAR");

        PageResponse<ProductSummaryResponse> alone = find(pokemon, "ชาริซาร์ด");
        assertThat(alone.items()).extracting(ProductSummaryResponse::id).contains(charizard.productId());
        assertThat(alone.correctedQuery()).isEqualTo("Charizard");
        assertThat(alone.correctionReason()).isEqualTo("ALIAS");

        PageResponse<ProductSummaryResponse> withEx = find(pokemon, "ชาริซาร์ด ex");
        assertThat(withEx.correctedQuery()).isEqualTo("Charizard ex");
        assertThat(withEx.items()).extracting(ProductSummaryResponse::id).contains(charizard.productId());
    }

    @Test
    void gameFilterStillHoldsAndUnknownThaiFindsNothing() {
        short pokemon = data.game("Pokemon");
        data.card(pokemon, "Pikachu", "C");
        short onePiece = data.game("One Piece");
        data.card(onePiece, "Monkey D. Luffy", "SEC");

        assertThat(find(onePiece, "ปิกาจู").items()).isEmpty();
        PageResponse<ProductSummaryResponse> nonsense = find(null, "ฟหกดสวง");
        assertThat(nonsense.items()).isEmpty();
        assertThat(nonsense.correctedQuery()).isNull();
    }

    @Test
    void adminAddsAndRemovesAliasWithImmediateEffect() {
        ApiClient api = new ApiClient(port);
        String admin = data.bearer(data.admin());
        short game = data.game("Pokemon");
        Card card = data.card(game, "Bulbasaur", "C");
        String alias = "ฟชิกิดาเนะ";

        assertThat(find(game, alias).items()).isEmpty();
        ApiClient.Response created = api.post(PATH, admin, Map.of("alias", alias, "target", "Bulbasaur"));
        assertThat(created.status()).isEqualTo(201);
        assertThat(find(game, alias).items()).extracting(ProductSummaryResponse::id).containsExactly(card.productId());

        assertThat(api.post(PATH, admin, Map.of("alias", alias, "target", "Bulbasaur")).errorCode())
                .isEqualTo("SEARCH_ALIAS_EXISTS");
        assertThat(api.post(PATH, data.bearer(data.buyer()), Map.of("alias", "x", "target", "y")).status())
                .isEqualTo(403);
        assertThat(api.post(PATH, admin, Map.of("alias", "", "target", "y")).status()).isEqualTo(400);
        assertThat(api.get(PATH + "?q=" + "Bulbasaur", admin).data().get("totalItems").asInt()).isEqualTo(1);

        long id = created.data().get("id").asLong();
        assertThat(api.delete(PATH + "/" + id, admin).status()).isEqualTo(200);
        assertThat(find(game, alias).items()).isEmpty();
        assertThat(api.delete(PATH + "/" + id, admin).status()).isEqualTo(404);
    }
}
