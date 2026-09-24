package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.jooq.tables.Cart.CART;
import static com.pegasus.pegasustcgapi.jooq.tables.CartItem.CART_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingUnit.LISTING_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItem.ORDER_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItemUnit.ORDER_ITEM_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrderStatusHistory.SELLER_ORDER_STATUS_HISTORY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pegasus.pegasustcgapi.dto.CartItemRequest;
import com.pegasus.pegasustcgapi.dto.CheckoutRequest;
import com.pegasus.pegasustcgapi.dto.CheckoutResponse;
import com.pegasus.pegasustcgapi.dto.CheckoutResponse.SellerOrderResponse;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.jooq.tables.records.ListingRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderStatusHistoryRecord;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.ListingUnitStatus;
import com.pegasus.pegasustcgapi.repository.AddressRepository.AddressFields;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Card;
import com.pegasus.pegasustcgapi.support.TestData.Listing;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Placing an order against the real schema [CR-4 US-17, US-20; CR-6/CR-7 commission]:
 * the cart turns into one sales order with a sub-order per seller, the exact cards
 * are reserved, the address and commission are frozen, and a failure leaves nothing
 * half-written.
 */
@DisplayName("CheckoutService (integration) — placing an order")
class CheckoutServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private CartService cartService;

    @Autowired
    private AddressService addressService;

    private Seller seller;
    private Card card;
    private AuthPrincipal buyer;

    @BeforeEach
    void setUp() {
        seller = data.seller();
        card = data.card(data.game("Pokemon"), "Charizard ex", "SAR");
        buyer = data.buyer();
    }

    private CheckoutResponse checkout(AuthPrincipal who) {
        return checkoutService.checkout(who, "key-" + data.tag(), null, null);
    }

    private ListingRecord listingRow(long listingId) {
        return dsl.selectFrom(LISTING).where(LISTING.ID.eq(listingId)).fetchSingle();
    }

    private int unitsIn(long listingId, ListingUnitStatus status) {
        return dsl.fetchCount(LISTING_UNIT,
                LISTING_UNIT.LISTING_ID.eq(listingId).and(LISTING_UNIT.STATUS.eq(status.name())));
    }

    private int cartLines(AuthPrincipal who) {
        return dsl.fetchCount(CART_ITEM, CART_ITEM.CART_ID.in(
                dsl.select(CART.ID).from(CART).where(CART.USER_ID.eq(who.userId()))));
    }

    @Test
    @DisplayName("checkout reserves the exact cards, empties the cart and opens the audit trail at PENDING_PAYMENT")
    void placingAnOrderReservesStock() {
        Listing listing = data.onSale(seller, card, CardCondition.NM, 3, "250.00");
        cartService.addItem(buyer, null, new CartItemRequest(listing.id(), 2));

        CheckoutResponse order = checkout(buyer);

        assertThat(order.status()).isEqualTo("PENDING_PAYMENT");
        assertThat(order.itemsSubtotal()).isEqualByComparingTo("500.00");
        assertThat(order.sellerOrders()).singleElement().satisfies(sub -> {
            assertThat(sub.status()).isEqualTo("PENDING_PAYMENT");
            assertThat(sub.items()).singleElement().satisfies(item -> assertThat(item.unitIds()).hasSize(2));
        });

        // Stock: two of three cards held for this order, none gone, none negative.
        ListingRecord row = listingRow(listing.id());
        assertThat(row.getQuantityAvailable()).isEqualTo(1);
        assertThat(row.getQuantityReserved()).isEqualTo(2);
        assertThat(unitsIn(listing.id(), ListingUnitStatus.RESERVED)).isEqualTo(2);
        assertThat(unitsIn(listing.id(), ListingUnitStatus.LISTED)).isEqualTo(1);

        // order_item_unit names exactly the cards that were reserved.
        List<Long> linkedUnits = dsl.select(ORDER_ITEM_UNIT.LISTING_UNIT_ID).from(ORDER_ITEM_UNIT)
                .join(ORDER_ITEM).on(ORDER_ITEM.ID.eq(ORDER_ITEM_UNIT.ORDER_ITEM_ID))
                .where(ORDER_ITEM.SELLER_ORDER_ID.eq(order.sellerOrders().getFirst().id()))
                .fetch(ORDER_ITEM_UNIT.LISTING_UNIT_ID);
        List<Long> reservedUnits = dsl.select(LISTING_UNIT.ID).from(LISTING_UNIT)
                .where(LISTING_UNIT.LISTING_ID.eq(listing.id()))
                .and(LISTING_UNIT.STATUS.eq(ListingUnitStatus.RESERVED.name()))
                .fetch(LISTING_UNIT.ID);
        assertThat(linkedUnits).containsExactlyInAnyOrderElementsOf(reservedUnits);

        assertThat(cartLines(buyer)).as("the basket is emptied").isZero();

        SellerOrderStatusHistoryRecord opened = dsl.selectFrom(SELLER_ORDER_STATUS_HISTORY)
                .where(SELLER_ORDER_STATUS_HISTORY.SELLER_ORDER_ID.eq(order.sellerOrders().getFirst().id()))
                .fetchSingle();
        assertThat(opened.getFromStatus()).isNull();
        assertThat(opened.getToStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(opened.getChangedBy()).isEqualTo(buyer.userId());
    }

    @Test
    @DisplayName("a basket from two sellers becomes one order with one sub-order per seller")
    void multiSellerBasketIsSplitPerSeller() {
        Seller otherSeller = data.seller();
        Listing fromFirst = data.onSale(seller, card, CardCondition.NM, 1, "100.00");
        Listing fromSecond = data.onSale(otherSeller, card, CardCondition.LP, 2, "40.00");
        cartService.addItem(buyer, null, new CartItemRequest(fromFirst.id(), 1));
        cartService.addItem(buyer, null, new CartItemRequest(fromSecond.id(), 2));

        CheckoutResponse order = checkout(buyer);

        assertThat(order.sellerOrders()).hasSize(2);
        assertThat(order.sellerOrders()).extracting(SellerOrderResponse::sellerProfileId)
                .containsExactlyInAnyOrder(seller.profileId(), otherSeller.profileId());
        assertThat(order.sellerOrders()).extracting(SellerOrderResponse::itemsSubtotal)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactlyInAnyOrder(new BigDecimal("100.00"), new BigDecimal("80.00"));
        assertThat(order.itemsSubtotal()).isEqualByComparingTo("180.00");
        assertThat(dsl.fetchCount(SALES_ORDER, SALES_ORDER.BUYER_ID.eq(buyer.userId()))).isEqualTo(1);
    }

    @Test
    @DisplayName("commission is frozen on the sub-order: 3% of 175.50 = 5.27, seller nets 170.23")
    void commissionIsFrozenOnTheSubOrder() {
        data.setting(PlatformSettingService.COMMISSION_DEFAULT_RATE, "3");
        Listing listing = data.onSale(seller, card, CardCondition.NM, 1, "175.50");
        cartService.addItem(buyer, null, new CartItemRequest(listing.id(), 1));

        CheckoutResponse order = checkout(buyer);
        long sellerOrderId = order.sellerOrders().getFirst().id();

        // A later change to the platform rate must not rewrite an order already placed.
        data.setting(PlatformSettingService.COMMISSION_DEFAULT_RATE, "10");

        SellerOrderRecord stored = dsl.selectFrom(SELLER_ORDER).where(SELLER_ORDER.ID.eq(sellerOrderId)).fetchSingle();
        assertThat(stored.getCommissionRatePercent()).isEqualByComparingTo("3");
        assertThat(stored.getCommissionAmount()).isEqualByComparingTo("5.27");
        assertThat(stored.getSellerNetAmount()).isEqualByComparingTo("170.23");
        assertThat(stored.getSellerNetAmount())
                .isEqualByComparingTo(stored.getGrandTotal().subtract(stored.getCommissionAmount()));
    }

    @Test
    @DisplayName("the shipping address is snapshotted: editing the address book later does not rewrite the order")
    void addressIsSnapshotted() {
        long addressId = data.address(buyer);
        Listing listing = data.onSale(seller, card, CardCondition.NM, 1, "90.00");
        cartService.addItem(buyer, null, new CartItemRequest(listing.id(), 1));

        CheckoutResponse order = checkoutService.checkout(buyer, "key-" + data.tag(), null,
                new CheckoutRequest(addressId, "Please pack in a toploader"));
        addressService.update(buyer.userId(), addressId, new AddressFields("Office", "Somsri Rakdee", "0899999999",
                "1 Silom Rd", null, null, "Bang Rak", "Bangkok", "10500", "TH", true, true));

        String snapshot = dsl.select(SALES_ORDER.SHIPPING_ADDRESS_SNAPSHOT).from(SALES_ORDER)
                .where(SALES_ORDER.ID.eq(order.orderId())).fetchSingle().value1().data();
        assertThat(snapshot).contains("Somchai Jaidee", "10110").doesNotContain("Somsri", "10500");
        assertThat(dsl.select(SALES_ORDER.BUYER_NOTE).from(SALES_ORDER)
                .where(SALES_ORDER.ID.eq(order.orderId())).fetchSingle().value1())
                .isEqualTo("Please pack in a toploader");
    }

    @Test
    @DisplayName("stock that ran out after the card went into the basket is 409, and nothing is ordered or held")
    void stockThatRanOutIsRefusedWithoutAPartialOrder() {
        Listing listing = data.onSale(seller, card, CardCondition.NM, 2, "300.00");
        AuthPrincipal quickerBuyer = data.buyer();
        cartService.addItem(buyer, null, new CartItemRequest(listing.id(), 2));
        cartService.addItem(quickerBuyer, null, new CartItemRequest(listing.id(), 1));
        checkout(quickerBuyer);

        assertThatThrownBy(() -> checkout(buyer))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.errorCode())
                        .isIn(ErrorCode.INSUFFICIENT_STOCK, ErrorCode.LISTING_NOT_PURCHASABLE));

        assertThat(dsl.fetchCount(SALES_ORDER, SALES_ORDER.BUYER_ID.eq(buyer.userId()))).isZero();
        assertThat(unitsIn(listing.id(), ListingUnitStatus.RESERVED)).as("only the quicker buyer's card").isEqualTo(1);
        assertThat(listingRow(listing.id()).getQuantityAvailable()).isEqualTo(1).isNotNegative();
        assertThat(cartLines(buyer)).as("the refused buyer keeps their basket").isEqualTo(1);
    }

    @Test
    @DisplayName("ECC commission < 0 (current behaviour): the database CHECK refuses the order and it all rolls back")
    void negativeCommissionRateIsStoppedByTheDatabase() {
        data.setting(PlatformSettingService.COMMISSION_DEFAULT_RATE, "-5");
        Listing listing = data.onSale(seller, card, CardCondition.NM, 1, "175.50");
        cartService.addItem(buyer, null, new CartItemRequest(listing.id(), 1));

        assertThatThrownBy(() -> checkout(buyer)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(dsl.fetchCount(SALES_ORDER, SALES_ORDER.BUYER_ID.eq(buyer.userId()))).isZero();
        assertThat(unitsIn(listing.id(), ListingUnitStatus.LISTED)).as("the card is back on sale").isEqualTo(1);
        assertThat(cartLines(buyer)).isEqualTo(1);
    }

    @Test
    @DisplayName("ECC commission > 100 (current behaviour): 105% is accepted and leaves the seller a negative net")
    void commissionAboveHundredIsAccepted() {
        data.setting(PlatformSettingService.COMMISSION_DEFAULT_RATE, "105");
        Listing listing = data.onSale(seller, card, CardCondition.NM, 1, "175.50");
        cartService.addItem(buyer, null, new CartItemRequest(listing.id(), 1));

        SellerOrderResponse sub = checkout(buyer).sellerOrders().getFirst();

        // Neither LedgerService nor the seller_order CHECK caps the rate, so the order goes through.
        assertThat(sub.commissionAmount()).isEqualByComparingTo("184.28");
        assertThat(sub.sellerNetAmount()).isEqualByComparingTo("-8.78").isNegative();
    }
}
