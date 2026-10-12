package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.PublicListingResponse;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.ListingStatus;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Card;
import com.pegasus.pegasustcgapi.support.TestData.Listing;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Pairwise (all-pairs) test of the market filters [CR-2 US-08, US-13].
 *
 * <p>Four factors: Game (3) × Rarity (3) × Condition NM/LP/MP (3) × In stock (2)
 * are 54 combinations. The nine rows below are an L9 orthogonal array, with the
 * fourth column folded onto two levels, so every pair of values from any two
 * factors appears in at least one row.
 *
 * <p>How each factor reaches the code: game and condition are filters on
 * {@code GET /listings}; in stock is whether the listing is ACTIVE or has sold out
 * (the market shows ACTIVE only); rarity is a property of the card — there is no
 * rarity filter — so it is checked to come back unchanged on whatever is found.
 *
 * <p>The whole matrix is seeded once, so every row runs against the other eight as
 * decoys: a row passes only if its filters keep the other games and conditions out.
 */
@DisplayName("ListingBrowseService (integration) — PWC: Game × Rarity × Condition × In stock")
class ListingBrowseServiceIntegrationTest extends PostgresIntegrationTest {

    private enum Game { POKEMON, YUGIOH, ONE_PIECE }

    /** Rarity codes as stored on the product. */
    private enum Rarity { COMMON, SECRET_RARE, ULTRA_RARE }

    private record Seeded(Map<Game, Short> gameIds, Map<String, Listing> listingByRow, Map<Long, Rarity> rarityByProduct) {
    }

    /** Built on the first row and reused: the rows share one matrix of decoys. */
    private static Seeded seeded;

    @Autowired
    private ListingBrowseService browse;

    @BeforeEach
    void seedTheMatrixOnce() {
        if (seeded != null) {
            return;
        }
        Seller seller = data.seller();
        Map<Game, Short> gameIds = new EnumMap<>(Game.class);
        for (Game game : Game.values()) {
            gameIds.put(game, data.game(game.name()));
        }

        Map<String, Listing> listingByRow = new HashMap<>();
        Map<Long, Rarity> rarityByProduct = new HashMap<>();
        for (Object[] row : ROWS) {
            String id = (String) row[0];
            Game game = (Game) row[1];
            Rarity rarity = (Rarity) row[2];
            CardCondition condition = (CardCondition) row[3];
            boolean inStock = (Boolean) row[4];

            Card card = data.card(gameIds.get(game), "Card " + id, rarity.name());
            Listing listing = data.onSale(seller, card, condition, 1, "100.00");
            if (!inStock) {
                data.sellOut(seller, listing);
            }
            listingByRow.put(id, listing);
            rarityByProduct.put(card.productId(), rarity);
        }
        seeded = new Seeded(gameIds, listingByRow, rarityByProduct);
    }

    /**
     * The L9 array: each row is a case id, then Game, Rarity, Condition, In stock.
     * Seeding and the parameterized cases both read it, so they cannot drift apart.
     */
    private static final List<Object[]> ROWS = List.of(
            new Object[] {"P1", Game.POKEMON, Rarity.COMMON, CardCondition.NM, true},
            new Object[] {"P2", Game.POKEMON, Rarity.SECRET_RARE, CardCondition.LP, false},
            new Object[] {"P3", Game.POKEMON, Rarity.ULTRA_RARE, CardCondition.MP, true},
            new Object[] {"P4", Game.YUGIOH, Rarity.COMMON, CardCondition.LP, true},
            new Object[] {"P5", Game.YUGIOH, Rarity.SECRET_RARE, CardCondition.MP, true},
            new Object[] {"P6", Game.YUGIOH, Rarity.ULTRA_RARE, CardCondition.NM, false},
            new Object[] {"P7", Game.ONE_PIECE, Rarity.COMMON, CardCondition.MP, false},
            new Object[] {"P8", Game.ONE_PIECE, Rarity.SECRET_RARE, CardCondition.NM, true},
            new Object[] {"P9", Game.ONE_PIECE, Rarity.ULTRA_RARE, CardCondition.LP, true});

    static Stream<Arguments> rows() {
        return ROWS.stream().map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}: {1} × {2} × {3} × inStock={4}")
    @MethodSource("rows")
    void filtersHoldForEveryPairOfValues(String row, Game game, Rarity rarity, CardCondition condition,
            boolean inStock) {
        short gameId = seeded.gameIds().get(game);
        Listing listing = seeded.listingByRow().get(row);

        PageResponse<PublicListingResponse> page =
                browse.market(null, null, gameId, condition, null, null, null, 0, 50);

        // Game and condition filters keep every other row out.
        assertThat(page.items()).allSatisfy(found -> {
            assertThat(found.card().gameId()).isEqualTo(gameId);
            assertThat(found.condition()).isEqualTo(condition);
            assertThat(found.status()).isEqualTo(ListingStatus.ACTIVE);
            assertThat(found.quantityAvailable()).isPositive();
        });

        List<Long> ids = page.items().stream().map(PublicListingResponse::id).toList();
        if (inStock) {
            assertThat(ids).containsExactly(listing.id());
            // Rarity is carried by the card, not filtered on: it must come back as seeded.
            long productId = page.items().getFirst().card().productId();
            assertThat(seeded.rarityByProduct().get(productId)).isEqualTo(rarity);
        } else {
            assertThat(ids).as("a sold-out listing is not in the market").doesNotContain(listing.id()).isEmpty();
        }
        assertThat(page.totalItems()).isEqualTo(ids.size());
    }

    @Test
    @DisplayName("a sold-out listing keeps its own page, marked SOLD_OUT, though the market leaves it out")
    void soldOutListingStillHasAPage() {
        Listing soldOut = seeded.listingByRow().get("P2");

        assertThat(browse.get(soldOut.id()).listing().status()).isEqualTo(ListingStatus.SOLD_OUT);
    }

    @Test
    @DisplayName("minPrice above maxPrice is refused as VALIDATION_FAILED")
    void invertedPriceRangeIsRefused() {
        assertThatThrownBy(() -> browse.market(null, null, null, null,
                new BigDecimal("500"), new BigDecimal("100"), null, 0, 20))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Nested
    @DisplayName("Sorting, price range and storefront")
    class SortingAndStorefront {

        private Seller shop;
        private short gameId;
        private List<Long> cheapToDear;

        @BeforeEach
        void threeListingsAtDifferentPrices() {
            shop = data.seller();
            gameId = data.game("Pokemon");
            cheapToDear = new ArrayList<>();
            for (String price : List.of("50.00", "150.00", "500.00")) {
                Card card = data.card(gameId, "Card at " + price, "C");
                cheapToDear.add(data.onSale(shop, card, CardCondition.NM, 1, price).id());
            }
        }

        private List<Long> ids(PageResponse<PublicListingResponse> page) {
            return page.items().stream().map(PublicListingResponse::id).toList();
        }

        @Test
        @DisplayName("default and \"price\" sort cheapest first; \"price_desc\" dearest first")
        void sortByPrice() {
            assertThat(ids(browse.market(null, null, gameId, null, null, null, null, 0, 20)))
                    .containsExactlyElementsOf(cheapToDear);
            assertThat(ids(browse.market(null, null, gameId, null, null, null, "PRICE", 0, 20)))
                    .containsExactlyElementsOf(cheapToDear);
            assertThat(ids(browse.market(null, null, gameId, null, null, null, "price_desc", 0, 20)))
                    .containsExactlyElementsOf(cheapToDear.reversed());
        }

        @Test
        @DisplayName("\"newest\" puts the most recently published listing first")
        void sortByNewest() {
            assertThat(ids(browse.market(null, null, gameId, null, null, null, "newest", 0, 20)).getFirst())
                    .isEqualTo(cheapToDear.getLast());
        }

        @Test
        @DisplayName("an unknown sort is refused rather than silently ignored")
        void unknownSortIsRefused() {
            assertThatThrownBy(() -> browse.market(null, null, gameId, null, null, null, "rarity", 0, 20))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }

        @Test
        @DisplayName("minPrice and maxPrice are inclusive bounds")
        void priceRangeIsInclusive() {
            assertThat(ids(browse.market(null, null, gameId, null,
                    new BigDecimal("150.00"), new BigDecimal("500.00"), null, 0, 20)))
                    .containsExactly(cheapToDear.get(1), cheapToDear.get(2));
            assertThat(ids(browse.market(null, null, gameId, null,
                    null, new BigDecimal("149.99"), null, 0, 20)))
                    .containsExactly(cheapToDear.getFirst());
        }

        @Test
        @DisplayName("a seller's storefront lists only their own cards on sale")
        void storefrontShowsTheSellersListings() {
            Seller otherShop = data.seller();
            data.onSale(otherShop, data.card(gameId, "Someone else's card", "C"), CardCondition.NM, 1, "10.00");

            PageResponse<PublicListingResponse> storefront =
                    browse.storefront(shop.principal().username(), null, null, null, 0, 20);

            assertThat(ids(storefront)).containsExactlyElementsOf(cheapToDear);
            assertThat(storefront.items()).allSatisfy(listing ->
                    assertThat(listing.seller().username()).isEqualTo(shop.principal().username()));
        }

        @Test
        @DisplayName("the storefront of a username nobody has is USER_NOT_FOUND")
        void unknownStorefrontIsNotFound() {
            assertThatThrownBy(() -> browse.storefront("nobody_" + data.tag(), null, null, null, 0, 20))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND));
        }
    }
}
