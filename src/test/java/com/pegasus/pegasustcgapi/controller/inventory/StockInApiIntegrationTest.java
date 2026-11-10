package com.pegasus.pegasustcgapi.controller.inventory;

import static com.pegasus.pegasustcgapi.jooq.tables.InventoryMovement.INVENTORY_MOVEMENT;
import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingUnit.LISTING_UNIT;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Card;
import com.pegasus.pegasustcgapi.support.TestData.Listing;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** TCG-669: HTTP validation and seller isolation must leave both stock and its ledger intact. */
class StockInApiIntegrationTest extends PostgresIntegrationTest {
    private static final String UNITS = "/api/v1/sellers/me/units";
    private ApiClient api;
    private Seller seller;
    private Card card;
    private Listing listing;
    private String sellerToken;
    private String otherSellerToken;
    private String buyerToken;

    @BeforeEach
    void stockForOneSeller() {
        api = new ApiClient(port);
        seller = data.seller();
        sellerToken = data.bearer(seller.principal());
        otherSellerToken = data.bearer(data.seller().principal());
        buyerToken = data.bearer(data.buyer());
        clock.setInstant(Instant.parse("2026-01-15T08:00:00Z"));
        card = data.card(data.game("Pokemon"), "Stock-in test", "C");
        listing = data.onSale(seller, card, CardCondition.NM, 1, "100.00");
    }

    @Test
    void restockPersistsEachCardAndCostWithoutReservingAnything() {
        var response = api.post(UNITS, sellerToken, request(2));
        assertThat(response.status()).as(response.body().toString()).isEqualTo(201);
        assertThat(response.data().size()).isEqualTo(2);
        assertStock(3);
        var added = dsl.selectFrom(LISTING_UNIT).where(LISTING_UNIT.LISTING_ID.eq(listing.id()))
                .and(LISTING_UNIT.ACQUISITION_COST.eq(new BigDecimal("75.25"))).fetch();
        assertThat(added).hasSize(2).allSatisfy(unit -> {
            assertThat(unit.getStatus()).isEqualTo("LISTED");
            assertThat(unit.getSellerProfileId()).isEqualTo(seller.profileId());
            assertThat(unit.getAcquiredAt().toInstant()).isEqualTo(clock.instant());
        });
        var movements = dsl.selectFrom(INVENTORY_MOVEMENT)
                .where(INVENTORY_MOVEMENT.LISTING_UNIT_ID.in(added.stream().map(u -> u.getId()).toList())).fetch();
        assertThat(movements).hasSize(2).allSatisfy(movement -> {
            assertThat(movement.getQuantityDelta()).isEqualTo(1);
            assertThat(movement.getUnitCost()).isEqualByComparingTo("75.25");
            assertThat(movement.getCreatedBy()).isEqualTo(seller.userId());
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 101})
    void invalidQuantityCannotAddCardsOrLedgerEntries(int quantity) {
        int movementsBefore = movementCount();
        var response = api.post(UNITS, sellerToken, request(quantity));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.errorCode()).isEqualTo("VALIDATION_FAILED");
        assertStock(1);
        assertThat(movementCount()).isEqualTo(movementsBefore);
    }

    @Test
    void otherSellerBuyerGuestAndMissingListingCannotStockThisListing() {
        int movementsBefore = movementCount();
        var other = api.post(UNITS, otherSellerToken, request(2));
        assertThat(other.status()).isEqualTo(404);
        assertThat(other.errorCode()).isEqualTo("LISTING_NOT_FOUND");
        var buyer = api.post(UNITS, buyerToken, request(2));
        assertThat(buyer.status()).isEqualTo(404);
        assertThat(buyer.errorCode()).isEqualTo("SELLER_NOT_FOUND");
        assertThat(api.post(UNITS, null, request(2)).status()).isEqualTo(401);
        var missing = api.post(UNITS, sellerToken, Map.of("catalogVariantId", card.variantId(),
                "listingId", Long.MAX_VALUE, "quantity", 2, "unitCost", "75.25"));
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.errorCode()).isEqualTo("LISTING_NOT_FOUND");
        assertStock(1);
        assertThat(movementCount()).isEqualTo(movementsBefore);
    }

    private Map<String, Object> request(int quantity) {
        return Map.of("catalogVariantId", card.variantId(), "listingId", listing.id(),
                "quantity", quantity, "unitCost", "75.25");
    }

    private void assertStock(int quantity) {
        assertThat(dsl.fetchCount(LISTING_UNIT, LISTING_UNIT.LISTING_ID.eq(listing.id()))).isEqualTo(quantity);
        var row = dsl.selectFrom(LISTING).where(LISTING.ID.eq(listing.id())).fetchSingle();
        assertThat(row.getQuantityTotal()).isEqualTo(quantity);
        assertThat(row.getQuantityAvailable()).isEqualTo(quantity);
        assertThat(row.getQuantityReserved()).isZero();
    }

    private int movementCount() {
        return dsl.fetchCount(INVENTORY_MOVEMENT, INVENTORY_MOVEMENT.LISTING_ID.eq(listing.id()));
    }
}
