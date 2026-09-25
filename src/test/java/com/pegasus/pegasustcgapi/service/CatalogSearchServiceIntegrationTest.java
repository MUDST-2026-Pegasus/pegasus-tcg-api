package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.ProductSummaryResponse;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Card;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Searching the catalogue by name and by game against the real query [CR-2 US-08, US-10]:
 * ILIKE on the English and local names, the game filter, and the public view
 * hiding retired products.
 */
@DisplayName("CatalogSearchService (integration) — search by name and game")
class CatalogSearchServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private CatalogSearchService search;

    private PageResponse<ProductSummaryResponse> publicSearch(Short gameId, String q) {
        return search.search(browse(gameId, q, true));
    }

    private static ProductBrowse browse(Short gameId, String q, boolean activeOnly) {
        return new ProductBrowse(gameId == null ? Set.of() : Set.of(gameId), null, null, null, q, null, null,
                activeOnly, false, null, null, null, 0, 50);
    }

    @ParameterizedTest(name = "\"{0}\" finds Charizard ex")
    @ValueSource(strings = {"Charizard", "charizard", "CHARIZARD EX", "izard", "  chariz  "})
    void nameSearchIsCaseInsensitiveAndMatchesAnyPart(String q) {
        short pokemon = data.game("Pokemon");
        Card charizard = data.card(pokemon, "Charizard ex", "SAR");
        data.card(pokemon, "Pikachu", "C");

        PageResponse<ProductSummaryResponse> page = publicSearch(pokemon, q);

        assertThat(page.items()).extracting(ProductSummaryResponse::id).containsExactly(charizard.productId());
        assertThat(page.totalItems()).isEqualTo(1);
    }

    @Test
    @DisplayName("name search alone (no game) finds the card across the whole catalogue")
    void nameSearchWithoutGame() {
        String unique = "Zekrom " + data.tag();
        Card zekrom = data.card(data.game("Pokemon"), unique, "UR");

        PageResponse<ProductSummaryResponse> page = publicSearch(null, unique.toLowerCase());

        assertThat(page.items()).singleElement().satisfies(found -> {
            assertThat(found.id()).isEqualTo(zekrom.productId());
            assertThat(found.rarityCode()).isEqualTo("UR");
            assertThat(found.variantCount()).isEqualTo(1);
        });
    }

    @ParameterizedTest(name = "wildcard \"{0}\" is matched literally, not as a pattern")
    @ValueSource(strings = {"%", "_", "\\"})
    void likeWildcardsAreEscaped(String q) {
        short game = data.game("Pokemon");
        data.card(game, "Pikachu", "C");
        data.card(game, "Mew", "SR");

        assertThat(publicSearch(game, q).items()).isEmpty();
    }

    @Test
    @DisplayName("gameId returns that game's cards only, even when another game has the same name")
    void gameFilterSeparatesGames() {
        short pokemon = data.game("Pokemon");
        short yugioh = data.game("Yu-Gi-Oh");
        Card pokemonDragon = data.card(pokemon, "Dragon", "C");
        Card yugiohDragon = data.card(yugioh, "Dragon", "UR");
        Card blueEyes = data.card(yugioh, "Blue-Eyes White Dragon", "SCR");

        assertThat(publicSearch(pokemon, null).items())
                .extracting(ProductSummaryResponse::id).containsExactly(pokemonDragon.productId());
        assertThat(publicSearch(yugioh, null).items())
                .extracting(ProductSummaryResponse::id)
                .containsExactlyInAnyOrder(yugiohDragon.productId(), blueEyes.productId());
        assertThat(publicSearch(yugioh, "dragon").items())
                .allSatisfy(found -> assertThat(found.gameId()).isEqualTo(yugioh))
                .hasSize(2);
    }

    @Test
    @DisplayName("a retired product is hidden from the public search but still found by an admin")
    void retiredProductIsHiddenFromThePublic() {
        short game = data.game("One Piece");
        Card luffy = data.card(game, "Monkey D. Luffy", "SEC");
        dsl.update(CATALOG_PRODUCT).set(CATALOG_PRODUCT.IS_ACTIVE, false)
                .where(CATALOG_PRODUCT.ID.eq(luffy.productId())).execute();

        assertThat(publicSearch(game, "luffy").items()).isEmpty();
        assertThat(search.search(browse(game, "luffy", false)).items())
                .extracting(ProductSummaryResponse::id).containsExactly(luffy.productId());
    }

    @Test
    @DisplayName("a name nobody sells is an empty page, not an error")
    void noMatchIsAnEmptyPage() {
        short game = data.game("Pokemon");
        data.card(game, "Pikachu", "C");

        PageResponse<ProductSummaryResponse> page = publicSearch(game, "Agumon");

        assertThat(page.items()).isEmpty();
        assertThat(page.totalItems()).isZero();
        assertThat(page.totalPages()).isZero();
    }
}
