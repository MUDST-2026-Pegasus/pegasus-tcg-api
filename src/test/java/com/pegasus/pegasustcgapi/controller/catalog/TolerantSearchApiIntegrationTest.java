package com.pegasus.pegasustcgapi.controller.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.ApiClient.Response;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Card;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Public HTTP contract shared with the All products page (TCG-690/693). */
class TolerantSearchApiIntegrationTest extends PostgresIntegrationTest {

    private ApiClient client;

    @BeforeEach
    void setUp() {
        client = new ApiClient(port);
    }

    @Test
    void typoReturnsCorrectedQueryAndKeepsFiltersAndPagination() {
        short game = data.game("Pokemon");
        Card pikachu = data.card(game, "Pikachu", "C");
        data.card(game, "Charizard", "SR");
        int category = dsl.fetchSingle("SELECT category_id FROM catalog_product WHERE id = ?", pikachu.productId())
                .get(0, Integer.class);

        Response response = client.get("/api/v1/catalog/products?gameId=" + game
                + "&categoryId=" + category + "&q=pokamon&sort=relevance&page=0&size=1", null);

        assertThat(response.status()).isEqualTo(200);
        JsonNode page = response.data();
        assertThat(page.path("correctedQuery").asString()).isEqualTo("Pokemon");
        assertThat(page.path("items").size()).isEqualTo(1);
        assertThat(page.path("items").get(0).path("id").asLong()).isEqualTo(pikachu.productId());
        assertThat(page.path("page").asInt()).isZero();
        assertThat(page.path("size").asInt()).isEqualTo(1);
        assertThat(page.path("totalItems").asLong()).isEqualTo(1);
        assertThat(page.path("totalPages").asInt()).isEqualTo(1);
    }

    @Test
    void exactMatchRanksClosestProductFirstWithoutCorrection() {
        short game = data.game("Pokemon");
        data.card(game, "Mega Pikachu ex", "SR");
        Card exact = data.card(game, "Pikachu", "C");

        Response response = client.get("/api/v1/catalog/products?gameId=" + game
                + "&q=Pikachu&page=0&size=1", null);

        assertThat(response.status()).isEqualTo(200);
        JsonNode page = response.data();
        assertThat(page.has("correctedQuery")).isFalse();
        assertThat(page.path("items").get(0).path("id").asLong()).isEqualTo(exact.productId());
        assertThat(page.path("totalItems").asLong()).isEqualTo(2);
        assertThat(page.path("totalPages").asInt()).isEqualTo(2);
    }

    @Test
    void noCorrectionReturnsUnchangedEmptyPage() {
        short game = data.game("Pokemon");
        data.card(game, "Pikachu", "C");

        Response response = client.get("/api/v1/catalog/products?gameId=" + game
                + "&q=zzzzzzzzzz&page=0&size=20", null);

        assertThat(response.status()).isEqualTo(200);
        JsonNode page = response.data();
        assertThat(page.has("correctedQuery")).isFalse();
        assertThat(page.path("items").size()).isZero();
        assertThat(page.path("totalItems").asLong()).isZero();
        assertThat(page.path("totalPages").asInt()).isZero();
    }
}
