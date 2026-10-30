package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingUnit.LISTING_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.Payment.PAYMENT;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.pegasus.pegasustcgapi.dto.CartItemRequest;
import com.pegasus.pegasustcgapi.dto.CheckoutResponse;
import com.pegasus.pegasustcgapi.dto.CheckoutResponse.SellerOrderResponse;
import com.pegasus.pegasustcgapi.job.PendingPaymentTimeoutScheduler;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.ListingUnitStatus;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.storage.StorageService;
import com.pegasus.pegasustcgapi.storage.StoredObject;
import com.pegasus.pegasustcgapi.storage.UploadPurpose;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Listing;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * TCG-582: one shop of a two-shop order cancels before the buyer pays. The order
 * must stay payable for the rest, and the payment timeout must still apply to it.
 */
class PartialCancelBeforePaymentIntegrationTest extends PostgresIntegrationTest {

    @Autowired CartService cart;
    @Autowired CheckoutService checkout;
    @Autowired PaymentService payments;
    @Autowired OrderLifecycleService orders;
    @Autowired ListingService listings;
    @Autowired PendingPaymentTimeoutScheduler paymentTimeout;
    @MockitoBean StorageService storage;

    /** A buyer's order with one card from each of two shops; {@code kept} is the shop that stays. */
    private record TwoShopOrder(AuthPrincipal buyer, CheckoutResponse order,
            Seller keptSeller, Listing keptListing, SellerOrderResponse kept,
            Seller droppedSeller, SellerOrderResponse dropped) {
    }

    private TwoShopOrder placeTwoShopOrder() {
        Seller keptSeller = data.seller();
        Seller droppedSeller = data.seller();
        Listing keptListing = data.onSale(keptSeller,
                data.card(data.game("Pokemon"), "Kept " + data.tag(), "C"), CardCondition.NM, 1, "100.00");
        Listing droppedListing = data.onSale(droppedSeller,
                data.card(data.game("Pokemon"), "Dropped " + data.tag(), "C"), CardCondition.NM, 1, "40.00");
        AuthPrincipal buyer = data.buyer();
        cart.addItem(buyer, null, new CartItemRequest(keptListing.id(), 1));
        cart.addItem(buyer, null, new CartItemRequest(droppedListing.id(), 1));

        CheckoutResponse order = checkout.checkout(buyer, "two-shop-" + data.tag(), null, null);
        assertThat(order.sellerOrders()).hasSize(2);
        SellerOrderResponse kept = order.sellerOrders().stream()
                .filter(so -> so.sellerProfileId() == keptSeller.profileId()).findFirst().orElseThrow();
        SellerOrderResponse dropped = order.sellerOrders().stream()
                .filter(so -> so.sellerProfileId() == droppedSeller.profileId()).findFirst().orElseThrow();
        return new TwoShopOrder(buyer, order, keptSeller, keptListing, kept, droppedSeller, dropped);
    }

    private SalesOrderRecord salesOrder(TwoShopOrder o) {
        return dsl.selectFrom(SALES_ORDER).where(SALES_ORDER.ID.eq(o.order().orderId())).fetchSingle();
    }

    private String paymentStatus(long paymentId) {
        return dsl.select(PAYMENT.STATUS).from(PAYMENT).where(PAYMENT.ID.eq(paymentId)).fetchSingle(PAYMENT.STATUS);
    }

    private String sellerOrderStatus(SellerOrderResponse so) {
        return dsl.select(SELLER_ORDER.STATUS).from(SELLER_ORDER).where(SELLER_ORDER.ID.eq(so.id()))
                .fetchSingle(SELLER_ORDER.STATUS);
    }

    @Test
    @DisplayName("a shop cancels before payment -> order stays PENDING_PAYMENT and the buyer pays the rest at the new amount")
    void buyerPaysTheRemainingShopAfterAnotherCancels() {
        TwoShopOrder o = placeTwoShopOrder();
        var fullPayment = payments.create(o.buyer(), o.order().orderId(), "BANK_TRANSFER", "pay-full-" + data.tag());
        assertThat(fullPayment.amount()).isEqualByComparingTo(o.order().grandTotal());

        orders.cancelSellerOrderBySeller(o.droppedSeller().principal(), o.dropped().id(), "Out of stock");

        SalesOrderRecord afterCancel = salesOrder(o);
        assertThat(afterCancel.getStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(afterCancel.getPaidAt()).isNull();
        // The old payment asked for both shops; it must not be handed back to the buyer.
        assertThat(paymentStatus(fullPayment.id())).isEqualTo("CANCELLED");

        var rest = payments.create(o.buyer(), o.order().orderId(), "BANK_TRANSFER", "pay-rest-" + data.tag());
        assertThat(rest.id()).isNotEqualTo(fullPayment.id());
        assertThat(rest.status()).isEqualTo("PENDING");
        assertThat(rest.amount()).isEqualByComparingTo(o.kept().grandTotal());

        String slipKey = "payments/partial-" + data.tag() + ".png";
        given(storage.requireUploadedFor(UploadPurpose.PAYMENT_SLIP, slipKey))
                .willReturn(new StoredObject(slipKey, 1024L, "image/png"));
        var submitted = payments.attachSlip(o.buyer(), rest.id(), slipKey, "BANK-PARTIAL");
        assertThat(submitted.status()).isEqualTo("AWAITING_VERIFICATION");

        payments.verify(data.admin().userId(), rest.id(), true, null);
        assertThat(paymentStatus(rest.id())).isEqualTo("SUCCEEDED");
        assertThat(sellerOrderStatus(o.kept())).isEqualTo("PAID");
        assertThat(sellerOrderStatus(o.dropped())).isEqualTo("CANCELLED");
        SalesOrderRecord paid = salesOrder(o);
        assertThat(paid.getStatus()).isEqualTo("PAID");
        assertThat(paid.getPaidAt()).isNotNull();
    }

    @Test
    @DisplayName("a shop cancels before payment and the buyer never pays -> the timeout cancels the order and frees the cards")
    void paymentTimeoutStillCancelsTheRemainingShop() {
        data.setting(PlatformSettingService.ORDER_PAYMENT_TIMEOUT_MINUTES, "5");
        TwoShopOrder o = placeTwoShopOrder();
        payments.create(o.buyer(), o.order().orderId(), "BANK_TRANSFER", "pay-full-" + data.tag());
        orders.cancelSellerOrderBySeller(o.droppedSeller().principal(), o.dropped().id(), "Out of stock");
        var rest = payments.create(o.buyer(), o.order().orderId(), "BANK_TRANSFER", "pay-rest-" + data.tag());

        OffsetDateTime placedAt = salesOrder(o).getPlacedAt();
        clock.setInstant(placedAt.plusMinutes(5).plusSeconds(1).toInstant());
        paymentTimeout.processExpiredOrders();

        assertThat(sellerOrderStatus(o.kept())).isEqualTo("CANCELLED");
        assertThat(salesOrder(o).getStatus()).isEqualTo("CANCELLED");
        assertThat(paymentStatus(rest.id())).isEqualTo("EXPIRED");
        assertThat(dsl.fetchCount(LISTING_UNIT, LISTING_UNIT.LISTING_ID.eq(o.keptListing().id())
                .and(LISTING_UNIT.STATUS.eq(ListingUnitStatus.RESERVED.name())))).isZero();

        // With nothing reserved any more, the seller can take the listing down.
        listings.delete(o.keptSeller().userId(), o.keptListing().id());
        assertThat(dsl.select(LISTING.DELETED_AT).from(LISTING).where(LISTING.ID.eq(o.keptListing().id()))
                .fetchSingle(LISTING.DELETED_AT)).isNotNull();
    }
}
