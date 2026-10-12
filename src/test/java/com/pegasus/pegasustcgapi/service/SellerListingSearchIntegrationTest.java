package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.SellerListingSummaryResponse;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.ListingStatus;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Card;
import com.pegasus.pegasustcgapi.support.TestData.Listing;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The seller dashboard's search and sort against the real schema: the ILIKE
 * semi-join, the escaping of LIKE wildcards, and the per-row name used for A–Z.
 *
 * <p>Each test gets a fresh seller, so the only listings it can see are the three
 * seeded here; another seller's copy of the same card is the decoy.
 */
@DisplayName("ListingService.mine (integration) — dashboard search and sort")
class SellerListingSearchIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private ListingService listings;

    private Seller seller;
    private Card charizardCard;
    private Listing charizard;
    private Listing pikachu;
    private Listing mewtwo;

    @BeforeEach
    void seed() {
        seller = data.seller();
        short game = data.game("Dashboard");

        charizardCard = data.card(game, "Charizard ex", "SR");
        charizard = data.onSale(seller, charizardCard, CardCondition.NM, 3, "1290.00");
        // A literal % in the name: searching for it must not match everything.
        pikachu = data.onSale(seller, data.card(game, "Pikachu 100%", "R"), CardCondition.NM, 1, "180.00");
        mewtwo = data.onSale(seller, data.card(game, "Mewtwo V", "R"), CardCondition.LP, 5, "980.00");

        dsl.update(LISTING).set(LISTING.LOT_LABEL, "BOX-17").where(LISTING.ID.eq(mewtwo.id())).execute();

        // Somebody else selling the same Charizard never shows on this dashboard.
        data.onSale(data.seller(), charizardCard, CardCondition.NM, 1, "1000.00");
    }

    private PageResponse<SellerListingSummaryResponse> mine(String search, String sort) {
        return listings.mine(seller.userId(), null, null, null, search, sort, 0, 20);
    }

    private static List<Long> ids(PageResponse<SellerListingSummaryResponse> page) {
        return page.items().stream().map(SellerListingSummaryResponse::id).toList();
    }

    @Test
    @DisplayName("part of the card's name finds it, whatever the case, and the count agrees with the page")
    void searchesTheCardName() {
        PageResponse<SellerListingSummaryResponse> page = mine("CHARIZ", null);

        assertThat(ids(page)).containsExactly(charizard.id());
        assertThat(page.totalItems()).isEqualTo(1);
    }

    @Test
    @DisplayName("the printing's SKU finds the listing")
    void searchesTheSku() {
        String sku = dsl.select(CATALOG_VARIANT.SKU).from(CATALOG_VARIANT)
                .where(CATALOG_VARIANT.ID.eq(charizardCard.variantId()))
                .fetchSingle(CATALOG_VARIANT.SKU);

        assertThat(ids(mine(sku.toLowerCase(), null))).containsExactly(charizard.id());
    }

    @Test
    @DisplayName("the seller's own lot label finds the listing")
    void searchesTheLotLabel() {
        assertThat(ids(mine("box-17", null))).containsExactly(mewtwo.id());
    }

    @Test
    @DisplayName("% in the search is the character, not a wildcard")
    void wildcardsAreTakenLiterally() {
        assertThat(ids(mine("%", null))).containsExactly(pikachu.id());
        assertThat(ids(mine("100%", null))).containsExactly(pikachu.id());
    }

    @Test
    @DisplayName("search narrows within a status filter rather than replacing it")
    void searchCombinesWithStatus() {
        PageResponse<SellerListingSummaryResponse> paused =
                listings.mine(seller.userId(), ListingStatus.PAUSED, null, null, "charizard", null, 0, 20);

        assertThat(paused.items()).isEmpty();
        assertThat(paused.totalItems()).isZero();
    }

    @Test
    @DisplayName("price sorts both ways")
    void sortsByPrice() {
        assertThat(ids(mine(null, "price_asc"))).containsExactly(pikachu.id(), mewtwo.id(), charizard.id());
        assertThat(ids(mine(null, "price_desc"))).containsExactly(charizard.id(), mewtwo.id(), pikachu.id());
    }

    @Test
    @DisplayName("fewest cards left first, for finding what needs restocking")
    void sortsByStockLeft() {
        assertThat(ids(mine(null, "stock_asc"))).containsExactly(pikachu.id(), charizard.id(), mewtwo.id());
        assertThat(ids(mine(null, "stock_desc"))).containsExactly(mewtwo.id(), charizard.id(), pikachu.id());
    }

    @Test
    @DisplayName("A to Z by the card's name")
    void sortsByCardName() {
        assertThat(ids(mine(null, "name"))).containsExactly(charizard.id(), mewtwo.id(), pikachu.id());
    }

    @Test
    @DisplayName("an unknown sort is refused before anything is read")
    void unknownSortIsRefused() {
        assertThatThrownBy(() -> mine(null, "popular"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
