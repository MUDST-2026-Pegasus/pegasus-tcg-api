package com.pegasus.pegasustcgapi.service.listing;

import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.listing.ListingStatus;
import com.pegasus.pegasustcgapi.port.inventory.InventoryPort;
import com.pegasus.pegasustcgapi.port.inventory.InventoryPort.ReservedUnit;
import com.pegasus.pegasustcgapi.repository.listing.ListingRepository;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.ApiClient.Response;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Card;
import com.pegasus.pegasustcgapi.support.TestData.Listing;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** TCG-730, over HTTP against the real schema and its stock trigger. */
@DisplayName("Admin card listings and blocks (integration)")
class AdminCardListingIntegrationTest extends PostgresIntegrationTest {

    private static final String PRODUCTS = ApiPaths.ADMIN + "/catalog/products";
    private static final Map<String, String> REASON = Map.of("reason", "สงสัยการ์ดปลอม");

    @Autowired private InventoryPort inventory;
    @Autowired private ListingRepository listings;
    @Autowired private ListingService sellerListings;

    private ApiClient api;
    private String admin;
    private short game;
    private Card card;
    private Seller seller;

    @BeforeEach
    void seed() {
        api = new ApiClient(port);
        admin = data.bearer(data.admin());
        game = data.game("AdminListings");
        card = data.card(game, "Charizard ex", "SR");
        seller = data.seller();
    }

    private String listingsPath(String query) {
        return PRODUCTS + "/" + card.productId() + "/listings" + query;
    }

    private Response block(long id) {
        return api.post(ApiPaths.ADMIN_LISTINGS + "/" + id + "/block", admin, REASON);
    }

    private Response unblock(long id) {
        return api.post(ApiPaths.ADMIN_LISTINGS + "/" + id + "/unblock", admin, Map.of("reason", "ตรวจแล้วของจริง"));
    }

    private String status(long id) {
        return dsl.select(LISTING.STATUS).from(LISTING).where(LISTING.ID.eq(id)).fetchSingle(LISTING.STATUS);
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("listings").get("items").forEach(item -> ids.add(item.get("id").asLong()));
        return ids;
    }

    private void vacation(Seller shop) {
        dsl.update(SELLER_PROFILE).set(SELLER_PROFILE.VACATION_MODE, true)
                .where(SELLER_PROFILE.ID.eq(shop.profileId())).execute();
    }

    private void suspend(Seller shop) {
        dsl.update(SELLER_PROFILE).set(SELLER_PROFILE.STATUS, "SUSPENDED")
                .where(SELLER_PROFILE.ID.eq(shop.profileId())).execute();
    }

    @Test
    @DisplayName("lists every shop, buyable first, with a summary that ignores the filters")
    void listsShopsWithSummary() {
        Seller other = data.seller();
        Seller onHoliday = data.seller();
        Listing small = data.onSale(seller, card, CardCondition.NM, 1, "1290.00");
        Listing large = data.onSale(other, card, CardCondition.LP, 4, "990.00");
        Listing closed = data.onSale(onHoliday, card, CardCondition.NM, 2, "1000.00");
        vacation(onHoliday);
        Listing paused = data.onSale(seller, card, CardCondition.HP, 3, "500.00");
        sellerListings.changeStatus(seller.userId(), paused.id(), ListingStatus.PAUSED);
        Listing blocked = data.onSale(other, card, CardCondition.NM, 5, "400.00");
        block(blocked.id());

        JsonNode page = api.get(listingsPath(""), admin).data();

        assertThat(ids(page)).containsExactly(large.id(), closed.id(), small.id(), paused.id(), blocked.id());
        JsonNode summary = page.get("summary");
        assertThat(summary.get("shopCount").asInt()).isEqualTo(3);
        assertThat(summary.get("listingCount").asInt()).isEqualTo(5);
        assertThat(summary.get("availableQuantity").asInt()).isEqualTo(5);
        assertThat(summary.get("availableShops").asInt()).isEqualTo(2);
        assertThat(summary.get("pausedQuantity").asInt()).isEqualTo(3);
        assertThat(summary.get("blockedQuantity").asInt()).isEqualTo(5);
        assertThat(summary.get("blockedListingCount").asInt()).isEqualTo(1);
        assertThat(summary.get("closedShopQuantity").asInt()).isEqualTo(2);

        JsonNode closedRow = page.get("listings").get("items").get(1);
        assertThat(closedRow.get("purchasable").asBoolean()).isFalse();
        assertThat(closedRow.get("seller").get("onVacation").asBoolean()).isTrue();

        JsonNode filtered = api.get(listingsPath("?status=BLOCKED"), admin).data();
        assertThat(ids(filtered)).containsExactly(blocked.id());
        assertThat(filtered.get("summary").get("listingCount").asInt()).isEqualTo(5);
        assertThat(filtered.get("listings").get("items").get(0).get("block").get("reason").asText())
                .isEqualTo("สงสัยการ์ดปลอม");
        assertThat(filtered.get("listings").get("items").get(0).get("block").get("fromStatus").asText())
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("searches the shop's name and the listing number, with or without its #, and filters by printing")
    void searchesAndFilters() {
        Listing mine = data.onSale(seller, card, CardCondition.NM, 1, "1290.00");
        Listing theirs = data.onSale(data.seller(), card, CardCondition.NM, 1, "1300.00");
        String username = api.get(listingsPath(""), admin).data().get("listings").get("items").get(0)
                .get("seller").get("username").asText();

        assertThat(ids(api.get(listingsPath("?q=" + username), admin).data())).hasSize(1);
        assertThat(ids(api.get(listingsPath("?q=" + mine.id()), admin).data())).containsExactly(mine.id());
        assertThat(ids(api.get(listingsPath("?q=%23" + theirs.id()), admin).data())).containsExactly(theirs.id());
        assertThat(ids(api.get(listingsPath("?variantId=" + card.variantId()), admin).data())).hasSize(2);
        assertThat(ids(api.get(listingsPath("?variantId=999999999"), admin).data())).isEmpty();
    }

    @Test
    @DisplayName("drafts and closed listings stay out unless asked for")
    void draftsAndClosedOnRequest() {
        Listing closed = data.onSale(seller, card, CardCondition.NM, 1, "1290.00");
        sellerListings.changeStatus(seller.userId(), closed.id(), ListingStatus.DELISTED);

        assertThat(ids(api.get(listingsPath(""), admin).data())).isEmpty();
        assertThat(ids(api.get(listingsPath("?status=DELISTED"), admin).data())).containsExactly(closed.id());
    }

    @Test
    @DisplayName("only an admin gets in, and an unknown card is not found")
    void guards() {
        String buyer = data.bearer(data.buyer());
        Listing listing = data.onSale(seller, card, CardCondition.NM, 1, "1290.00");

        assertThat(api.get(listingsPath(""), buyer).status()).isEqualTo(403);
        assertThat(api.post(ApiPaths.ADMIN_LISTINGS + "/" + listing.id() + "/block", buyer, REASON).status())
                .isEqualTo(403);
        assertThat(api.get(PRODUCTS + "/999999999/listings", admin).errorCode()).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(block(999999999L).errorCode()).isEqualTo("LISTING_NOT_FOUND");
    }

    @Test
    @DisplayName("a block takes the listing off the market and out of the seller's hands, and the seller sees why")
    void blockTakesItOffTheMarket() {
        Listing listing = data.onSale(seller, card, CardCondition.NM, 2, "1290.00");

        Response blocked = block(listing.id());

        assertThat(blocked.status()).isEqualTo(200);
        assertThat(blocked.data().get("status").asText()).isEqualTo("BLOCKED");
        assertThat(blocked.data().get("block").get("blockedByUsername").asText()).isNotBlank();
        assertThat(listings.findPublic(listing.id())).isEmpty();
        assertThat(listings.findOffers(List.of(listing.id())).getFirst().purchasable()).isFalse();

        String sellerToken = data.bearer(seller.principal());
        String mine = ApiPaths.API_V1 + "/sellers/me/listings/" + listing.id();
        assertThat(api.get(mine, sellerToken).data().get("blockReason").asText()).isEqualTo("สงสัยการ์ดปลอม");
        assertThat(api.get(mine, sellerToken).data().get("blockedAt").isNull()).isFalse();
        assertThat(api.get(mine, sellerToken).data().has("blockedBy")).isFalse();
        assertThatThrownBy(() -> sellerListings.delete(seller.userId(), listing.id()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).errorCode()).isEqualTo(ErrorCode.LISTING_CLOSED));
        assertThatThrownBy(() -> sellerListings.changeStatus(seller.userId(), listing.id(), ListingStatus.PAUSED))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).errorCode())
                        .isEqualTo(ErrorCode.LISTING_STATUS_TRANSITION));
    }

    @Test
    @DisplayName("a block cannot be repeated, cannot close a closed listing, and needs a reason")
    void blockRefusals() {
        Listing listing = data.onSale(seller, card, CardCondition.NM, 1, "1290.00");
        Response blank = api.post(ApiPaths.ADMIN_LISTINGS + "/" + listing.id() + "/block", admin,
                Map.of("reason", "   "));
        assertThat(blank.status()).isEqualTo(400);
        assertThat(status(listing.id())).isEqualTo("ACTIVE");
        Response tooLong = api.post(ApiPaths.ADMIN_LISTINGS + "/" + listing.id() + "/block", admin,
                Map.of("reason", "x".repeat(501)));
        assertThat(tooLong.status()).isEqualTo(400);

        assertThat(unblock(listing.id()).errorCode()).isEqualTo("LISTING_STATUS_TRANSITION");
        block(listing.id());
        assertThat(block(listing.id()).errorCode()).isEqualTo("LISTING_STATUS_TRANSITION");

        Listing closed = data.onSale(seller, card, CardCondition.LP, 1, "900.00");
        sellerListings.changeStatus(seller.userId(), closed.id(), ListingStatus.DELISTED);
        assertThat(block(closed.id()).errorCode()).isEqualTo("LISTING_STATUS_TRANSITION");
    }

    @Test
    @DisplayName("lifting restores the status it had and clears the block")
    void unblockRestores() {
        Listing onSale = data.onSale(seller, card, CardCondition.NM, 2, "1290.00");
        block(onSale.id());
        Response restored = unblock(onSale.id());
        assertThat(restored.data().get("status").asText()).isEqualTo("ACTIVE");
        assertThat(restored.data().get("block").isNull()).isTrue();
        assertThat(listings.findPublic(onSale.id())).isPresent();

        Listing paused = data.onSale(seller, card, CardCondition.LP, 1, "900.00");
        sellerListings.changeStatus(seller.userId(), paused.id(), ListingStatus.PAUSED);
        block(paused.id());
        unblock(paused.id());
        assertThat(status(paused.id())).isEqualTo("PAUSED");
    }

    @Test
    @DisplayName("an open order survives a block, and the stock decides where lifting goes")
    void reservationsSurviveABlock() {
        Listing listing = data.onSale(seller, card, CardCondition.NM, 1, "1290.00");
        List<ReservedUnit> held = inventory.reserve(Map.of(listing.id(), 1)).get(listing.id());
        assertThat(status(listing.id())).isEqualTo("SOLD_OUT");

        block(listing.id());
        assertThat(api.get(listingsPath(""), admin).data().get("summary").get("reservedQuantity").asInt())
                .isEqualTo(1);

        // The order falls through while blocked: the card comes back, the block stays.
        inventory.release(List.of(held.getFirst().unitId()));
        assertThat(status(listing.id())).isEqualTo("BLOCKED");

        // It was sold out when blocked, but a card has come back since.
        unblock(listing.id());
        assertThat(status(listing.id())).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("a listing blocked sold out with nothing back stays sold out")
    void soldOutStaysSoldOut() {
        Listing listing = data.onSale(seller, card, CardCondition.NM, 1, "1290.00");
        inventory.reserve(Map.of(listing.id(), 1));

        block(listing.id());
        unblock(listing.id());

        assertThat(status(listing.id())).isEqualTo("SOLD_OUT");
    }

    @Test
    @DisplayName("a shop that lost its verification waits PAUSED, since the database refuses it ACTIVE")
    void unverifiedShopWaitsPaused() {
        Listing listing = data.onSale(seller, card, CardCondition.NM, 1, "1290.00");
        block(listing.id());
        suspend(seller);

        assertThat(unblock(listing.id()).status()).isEqualTo(200);

        assertThat(status(listing.id())).isEqualTo("PAUSED");
    }

    @Test
    @DisplayName("browse tiles carry who sells them, and the sale filter agrees with the numbers")
    void browseTiles() {
        data.onSale(seller, card, CardCondition.NM, 2, "1290.00");
        data.onSale(data.seller(), card, CardCondition.LP, 1, "900.00");
        Card unlisted = data.card(game, "Pikachu", "R");
        Card blockedCard = data.card(game, "Mewtwo", "R");
        block(data.onSale(seller, blockedCard, CardCondition.NM, 1, "500.00").id());
        Card holidayCard = data.card(game, "Gengar", "R");
        Seller onHoliday = data.seller();
        data.onSale(onHoliday, holidayCard, CardCondition.NM, 1, "500.00");
        vacation(onHoliday);

        String browse = PRODUCTS + "?gameId=" + game + "&size=50";
        JsonNode tiles = api.get(browse, admin).data().get("items");
        assertThat(tiles).hasSize(4);
        JsonNode tile = find(tiles, card.productId());
        assertThat(tile.get("name").asText()).isEqualTo(card.name());
        assertThat(tile.get("sellerCount").asInt()).isEqualTo(2);
        assertThat(tile.get("availableQuantity").asInt()).isEqualTo(3);
        assertThat(tile.get("blockedListingCount").asInt()).isZero();
        assertThat(find(tiles, unlisted.productId()).get("sellerCount").asInt()).isZero();
        assertThat(find(tiles, blockedCard.productId()).get("blockedListingCount").asInt()).isEqualTo(1);
        assertThat(find(tiles, holidayCard.productId()).get("availableQuantity").asInt()).isZero();

        assertThat(productIds(browse + "&saleStatus=HAS_SELLER")).containsExactly(card.productId());
        assertThat(productIds(browse + "&saleStatus=NO_SELLER"))
                .containsExactlyInAnyOrder(unlisted.productId(), blockedCard.productId(), holidayCard.productId());
        assertThat(productIds(browse + "&saleStatus=HAS_BLOCKED")).containsExactly(blockedCard.productId());
        assertThat(productIds(browse + "&saleStatus=ALL")).hasSize(4);
        assertThat(api.get(browse + "&saleStatus=HAS_BLOCKED", admin).data().get("totalItems").asInt()).isEqualTo(1);

        JsonNode publicTile = api.get(ApiPaths.API_V1 + "/catalog/products?gameId=" + game + "&size=50", null)
                .data().get("items").get(0);
        assertThat(publicTile.has("sellerCount")).isFalse();
    }

    private List<Long> productIds(String path) {
        List<Long> ids = new ArrayList<>();
        api.get(path, admin).data().get("items").forEach(item -> ids.add(item.get("id").asLong()));
        return ids;
    }

    private static JsonNode find(JsonNode tiles, long productId) {
        for (JsonNode tile : tiles) {
            if (tile.get("id").asLong() == productId) {
                return tile;
            }
        }
        throw new AssertionError("No tile for product " + productId);
    }
}
