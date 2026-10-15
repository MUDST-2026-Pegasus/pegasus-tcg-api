package com.pegasus.pegasustcgapi.controller;

import static com.pegasus.pegasustcgapi.jooq.tables.Cart.CART;
import static com.pegasus.pegasustcgapi.jooq.tables.CartItem.CART_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingUnit.LISTING_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerShippingOption.SELLER_SHIPPING_OPTION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.dto.CartItemRequest;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.repository.ShippingOptionRepository.OptionFields;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.CartService;
import com.pegasus.pegasustcgapi.service.ShippingOptionService;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.ApiClient.Response;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class ShippingQuoteIntegrationTest extends PostgresIntegrationTest {
    @Autowired CartService cart;
    @Autowired ShippingOptionService shipping;
    private ApiClient api;
    private AuthPrincipal buyer;
    private Seller seller;
    private long listingId;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
        buyer = data.buyerWithoutAddress();
        seller = data.seller();
        disableAll(seller);
        listingId = data.onSale(seller, data.card(data.game("Pokemon"), "Shipping test", "AR"),
                CardCondition.NM, 5, "100.00").id();
        cart.addItem(buyer, null, new CartItemRequest(listingId, 2));
    }

    private void disableAll(Seller owner) {
        dsl.update(SELLER_SHIPPING_OPTION).set(SELLER_SHIPPING_OPTION.IS_ACTIVE, false)
                .where(SELLER_SHIPPING_OPTION.SELLER_PROFILE_ID.eq(owner.profileId())).execute();
    }
    private long option(Seller owner, String name, String base, String extra, String threshold, int order) {
        return shipping.create(owner.userId(), new OptionFields(name, "KERRY", new BigDecimal(base),
                new BigDecimal(extra), threshold == null ? null : new BigDecimal(threshold),
                (short) 1, (short) 3, true, (short) order)).id();
    }
    private Response quotes(Object body) {
        return api.post(ApiPaths.CHECKOUT + "/shipping-quotes", data.bearer(buyer), body);
    }
    private Response checkout(Object body) {
        return api.post(ApiPaths.CHECKOUT, data.bearer(buyer), body,
                Map.of("Idempotency-Key", "shipping-" + data.tag()));
    }
    private long cartItem(AuthPrincipal owner, long listing) {
        return dsl.select(CART_ITEM.ID).from(CART_ITEM).join(CART).on(CART.ID.eq(CART_ITEM.CART_ID))
                .where(CART.USER_ID.eq(owner.userId())).and(CART_ITEM.LISTING_ID.eq(listing))
                .fetchSingle(CART_ITEM.ID);
    }
    private void assertUnchanged() {
        verify(inventoryPort, never()).reserve(any());
        assertThat(dsl.fetchCount(SALES_ORDER, SALES_ORDER.BUYER_ID.eq(buyer.userId()))).isZero();
        assertThat(dsl.fetchCount(LISTING_UNIT, LISTING_UNIT.LISTING_ID.eq(listingId)
                .and(LISTING_UNIT.STATUS.eq("RESERVED")))).isZero();
        assertThat(dsl.fetchCount(CART_ITEM, CART_ITEM.ID.eq(cartItem(buyer, listingId)))).isEqualTo(1);
    }
    private JsonNode group(JsonNode groups, long sellerId) {
        for (JsonNode item : groups) if (item.path("sellerProfileId").asLong() == sellerId) return item;
        throw new AssertionError("Seller missing from quotes: " + sellerId);
    }
    private BigDecimal amount(JsonNode parent, String field) {
        return new BigDecimal(parent.path(field).asString());
    }

    @Test
    void quotesGroupPhysicalQuantitiesSortActiveOptionsAndMatchTheOrderFees() {
        long secondListing = data.onSale(seller, data.card(data.game("One Piece"), "Second", "SR"),
                CardCondition.LP, 1, "50.00").id();
        cart.addItem(buyer, null, new CartItemRequest(secondListing, 1));
        long ems = option(seller, "EMS", "60.00", "10.00", null, 2);
        long kerry = option(seller, "Kerry", "40.00", "5.00", "300.00", 0);
        long hidden = option(seller, "Disabled", "1.00", "0.00", null, -1);
        shipping.deactivate(seller.userId(), hidden);
        var other = data.seller();
        disableAll(other);
        long freeOption = option(other, "Free threshold", "45.00", "0.00", "400.00", 0);
        long otherListing = data.onSale(other, data.card(data.game("Pokemon"), "Other shop", "AR"),
                CardCondition.NM, 1, "400.00").id();
        cart.addItem(buyer, null, new CartItemRequest(otherListing, 1));

        Response response = quotes(Map.of("cartItemIds", List.of()));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.data().size()).isEqualTo(2);
        JsonNode first = group(response.data(), seller.profileId());
        assertThat(first.path("sellerName").asString()).isEqualTo(seller.principal().username());
        assertThat(first.path("itemCount").asInt()).isEqualTo(3);
        assertThat(amount(first, "itemsSubtotal")).isEqualByComparingTo("250.00");
        assertThat(first.path("defaultOptionId").asLong()).isEqualTo(kerry);
        assertThat(first.path("options").size()).isEqualTo(2);
        JsonNode chosen = first.path("options").get(0);
        assertThat(chosen.path("id").asLong()).isEqualTo(kerry);
        assertThat(first.path("options").get(1).path("id").asLong()).isEqualTo(ems);
        assertThat(amount(chosen, "fee")).isEqualByComparingTo("50.00");
        assertThat(chosen.path("free").asBoolean()).isFalse();
        assertThat(amount(chosen, "amountToFree")).isEqualByComparingTo("50.00");
        JsonNode otherQuote = group(response.data(), other.profileId()).path("options").get(0);
        assertThat(otherQuote.path("free").asBoolean()).isTrue();
        assertThat(otherQuote.path("amountToFree").isNull()).isTrue();
        assertThat(amount(otherQuote, "fee")).isEqualByComparingTo("0");
        assertUnchanged();

        data.address(buyer);
        Response order = checkout(Map.of("shippingOptionBySeller", Map.of(
                Long.toString(seller.profileId()), kerry, Long.toString(other.profileId()), freeOption)));
        assertThat(order.status()).isEqualTo(201);
        assertThat(amount(group(order.data().path("sellerOrders"), seller.profileId()), "shippingFee"))
                .isEqualByComparingTo(amount(chosen, "fee"));
        assertThat(amount(group(order.data().path("sellerOrders"), other.profileId()), "shippingFee"))
                .isEqualByComparingTo(amount(otherQuote, "fee"));
        assertThat(dsl.select(SELLER_ORDER.SHIPPING_OPTION_ID).from(SELLER_ORDER)
                .where(SELLER_ORDER.SALES_ORDER_ID.eq(order.data().path("orderId").asLong()))
                .and(SELLER_ORDER.SELLER_PROFILE_ID.eq(seller.profileId())).fetchSingle().value1()).isEqualTo(kerry);
    }

    @Test
    void selectedCartItemsAreScopedToBuyerAndUseTheSameSelectionForCheckout() {
        long selectedOption = option(seller, "Standard", "50.00", "5.00", null, 0);
        long secondListing = data.onSale(seller, data.card(data.game("Pokemon"), "Unselected", "AR"),
                CardCondition.NM, 1, "70.00").id();
        cart.addItem(buyer, null, new CartItemRequest(secondListing, 1));
        var stranger = data.buyer();
        cart.addItem(stranger, null, new CartItemRequest(secondListing, 1));
        long selected = cartItem(buyer, listingId);
        var ids = List.of(selected, selected, cartItem(stranger, secondListing));
        Response quoted = quotes(Map.of("cartItemIds", ids));
        assertThat(quoted.status()).isEqualTo(200);
        assertThat(quoted.data().get(0).path("itemCount").asInt()).isEqualTo(2);
        assertThat(amount(quoted.data().get(0), "itemsSubtotal")).isEqualByComparingTo("200.00");
        assertThat(quotes(Map.of("cartItemIds", List.of(cartItem(stranger, secondListing)))).errorCode()).isEqualTo("CART_EMPTY");
        data.address(buyer);
        Response order = checkout(Map.of("cartItemIds", ids));
        assertThat(order.status()).isEqualTo(201);
        assertThat(amount(order.data(), "shippingTotal")).isEqualByComparingTo("55.00");
        assertThat(dsl.fetchCount(CART_ITEM, CART_ITEM.ID.eq(cartItem(buyer, secondListing)))).isEqualTo(1);
        assertThat(selectedOption).isPositive();
    }

    @Test
    void noActiveOptionIsQuotedAsUnavailableAndCannotCheckout() {
        Response quoted = quotes(Map.of());
        assertThat(quoted.status()).isEqualTo(200);
        assertThat(quoted.data().get(0).path("options").size()).isZero();
        assertThat(quoted.data().get(0).path("defaultOptionId").isNull()).isTrue();
        data.address(buyer);
        Response rejected = checkout(Map.of());
        assertThat(rejected.status()).isEqualTo(400);
        assertThat(rejected.errorCode()).isEqualTo("SELLER_SHIPPING_UNAVAILABLE");
        assertThat(rejected.data().path("details").path("sellerProfileId").asLong()).isEqualTo(seller.profileId());
        assertUnchanged();
    }

    @Test
    void deactivatedOrAnotherSellersOptionIsInvalidEvenWithAnActiveDefault() {
        long inactive = option(seller, "Deactivated", "5.00", "0.00", null, 0);
        shipping.deactivate(seller.userId(), inactive);
        option(seller, "Active", "50.00", "0.00", null, 1);
        var otherSeller = data.seller();
        long foreignId = option(otherSeller, "Foreign", "1.00", "0.00", null, 0);
        data.address(buyer);
        for (long id : List.of(inactive, foreignId)) {
            Response rejected = checkout(Map.of("shippingOptionBySeller", Map.of(Long.toString(seller.profileId()), id)));
            assertThat(rejected.status()).isEqualTo(400);
            assertThat(rejected.errorCode()).isEqualTo("SHIPPING_OPTION_INVALID");
            assertUnchanged();
        }
    }

    @Test
    void missingAddressAndAnotherBuyersAddressAreRefusedBeforeStockReservation() {
        option(seller, "Standard", "50.00", "5.00", null, 0);
        Response absent = checkout(Map.of());
        assertThat(absent.status()).isEqualTo(400);
        assertThat(absent.errorCode()).isEqualTo("SHIPPING_ADDRESS_REQUIRED");
        assertUnchanged();
        long foreignAddress = data.address(data.buyer());
        Response foreign = checkout(Map.of("shippingAddressId", foreignAddress));
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(foreign.errorCode()).isEqualTo("ADDRESS_NOT_FOUND");
        assertUnchanged();
    }

    @Test
    void defaultOptionStillSupportsOldClientsAndQuoteEndpointRequiresLogin() {
        option(seller, "Later", "10.00", "0.00", null, 2);
        long first = option(seller, "First", "40.00", "5.00", null, 0);
        Response quoted = quotes(null);
        assertThat(quoted.status()).isEqualTo(200);
        assertThat(quoted.data().get(0).path("defaultOptionId").asLong()).isEqualTo(first);
        data.address(buyer);
        Response placed = checkout(null);
        assertThat(placed.status()).isEqualTo(201);
        assertThat(amount(placed.data(), "shippingTotal")).isEqualByComparingTo("45.00");
        assertThat(api.post(ApiPaths.CHECKOUT + "/shipping-quotes", null, Map.of()).status()).isEqualTo(401);
    }

    @Test
    void rejectsMalformedIdentifiersAndDoesNotQuoteAnEmptyCart() {
        assertThat(quotes(Map.of("cartItemIds", List.of(-1))).status()).isEqualTo(400);
        var emptyBuyer = data.buyer();
        var empty = api.post(ApiPaths.CHECKOUT + "/shipping-quotes", data.bearer(emptyBuyer), Map.of());
        assertThat(empty.status()).isEqualTo(409);
        assertThat(empty.errorCode()).isEqualTo("CART_EMPTY");
    }
    @Test
    void swaggerExposesTheAuthenticatedQuoteRequestAndResponseSchemas() {
        var spec = api.get("/v1/api-docs", null);
        assertThat(spec.status()).isEqualTo(200);
        var operation = spec.body().path("paths").path(ApiPaths.CHECKOUT + "/shipping-quotes").path("post");
        assertThat(operation.path("security").isArray()).isTrue();
        assertThat(operation.path("responses").has("200")).isTrue();
        assertThat(spec.body().path("components").path("schemas").has("ShippingQuoteRequest")).isTrue();
        assertThat(spec.body().path("components").path("schemas").has("ShippingQuoteOptionResponse")).isTrue();
    }
}
