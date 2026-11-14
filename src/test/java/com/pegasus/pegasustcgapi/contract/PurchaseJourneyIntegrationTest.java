package com.pegasus.pegasustcgapi.contract;

import static com.pegasus.pegasustcgapi.jooq.tables.CartItem.CART_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.EscrowHold.ESCROW_HOLD;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingUnit.LISTING_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.Payment.PAYMENT;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;

import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.TestData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** TCG-755 journey 1: Auth → Cart → Checkout → Inventory → Order → Payment, over HTTP. */
@DisplayName("TCG-755 Auth → Cart → Checkout → Inventory → Order → Payment")
class PurchaseJourneyIntegrationTest extends CrossModuleJourneyTest {

    static final FeShape CART_ITEM_SHAPE = FeShape.of("CartItemResponse (cart.types.ts)")
            .field("id", "number").field("cartId", "number").field("listingId", "number")
            .field("quantity", "number").field("unitPriceAtAdd", "number").field("currentPrice", "number|null")
            .field("priceChanged", "boolean").field("purchasable", "boolean")
            .field("quantityAvailable", "number|null").field("sellerProfileId", "number|null")
            .field("catalogVariantId", "number|null").field("condition", "string").field("currency", "string")
            .field("addedAt", "string").field("updatedAt", "string").field("productName", "string|null")
            .field("variantLabel", "string|null").field("sellerName", "string|null")
            .field("imageUrl", "string|null").field("productId", "number|null");
    static final FeShape CART_SHAPE = FeShape.of("CartResponse (cart.types.ts)")
            .field("id", "number").field("userId", "number|null").field("sessionKey", "string|null")
            .field("currency", "string").field("items", "array").field("totalQuantity", "number")
            .field("itemsSubtotal", "number").field("expiresAt", "string|null");
    static final FeShape QUOTE_SHAPE = FeShape.of("ShippingQuote (checkout.types.ts)")
            .field("sellerProfileId", "number").field("sellerName", "string").field("itemCount", "number")
            .field("itemsSubtotal", "number").field("defaultOptionId", "number|null").field("options", "array");
    static final FeShape CHECKOUT_SHAPE = FeShape.of("CheckoutResponse (checkout.types.ts)")
            .field("orderId", "number").field("orderNumber", "string").field("buyerId", "number")
            .field("status", "string").field("currency", "string").field("itemsSubtotal", "number")
            .field("shippingTotal", "number").field("discountTotal", "number")
            .field("saleDiscountTotal", "?number|null").field("grandTotal", "number")
            .field("placedAt", "string").field("sellerOrders", "array").field("replayed", "boolean");
    static final FeShape SELLER_ORDER_SHAPE = FeShape.of("CheckoutSellerOrderResponse (checkout.types.ts)")
            .field("id", "number").field("sellerProfileId", "number").field("sellerOrderNumber", "string")
            .field("status", "string").field("itemsSubtotal", "number").field("shippingFee", "number")
            .field("grandTotal", "number").field("commissionAmount", "number").field("sellerNetAmount", "number")
            .field("items", "array");
    static final FeShape ORDER_ITEM_SHAPE = FeShape.of("CheckoutOrderItemResponse (checkout.types.ts)")
            .field("id", "number").field("listingId", "number|null").field("catalogVariantId", "number")
            .field("productName", "string").field("variantLabel", "string").field("condition", "string")
            .field("quantity", "number").field("unitPrice", "number").field("lineTotal", "number")
            .field("unitIds", "array").field("imageUrl", "?string|null");
    static final FeShape PAYMENT_SHAPE = FeShape.of("Payment (finance.types.ts)")
            .field("id", "number").field("salesOrderId", "number").field("method", "string")
            .field("status", "string").field("amount", "number").field("expiresAt", "string")
            .field("transferAccountJson", "string").field("referenceCode", "string|null")
            .field("failureReason", "string|null");

    @Test
    @DisplayName("a signed-in buyer pays by slip and the admin's approval reaches order, stock and escrow")
    void buyerCheckoutAndSlipPaymentEndToEnd() {
        TestData.Seller seller = data.seller();
        TestData.Listing listing = data.onSale(seller, data.card(data.game("Pokemon"), "Journey " + data.tag(), "R"),
                CardCondition.NM, 3, "150.00");
        AuthPrincipal buyer = data.buyer();
        String token = login(buyer);
        // Roles come from the token; TestData admins have no role rows, so mint the admin token directly.
        String adminToken = data.bearer(data.admin());

        expect(200, "GET", "/api/v1/auth/me", api.get("/api/v1/auth/me", token));

        // Cart
        ApiClient.Response added = api.post("/api/v1/cart/items", token, Map.of("listingId", listing.id(), "quantity", 2));
        assertThat(added.status()).as(added.body().toString()).isIn(200, 201);
        expect(added.status(), "POST", "/api/v1/cart/items", added);
        CART_ITEM_SHAPE.check(added.data());
        ApiClient.Response cart = expect(200, "GET", "/api/v1/cart", api.get("/api/v1/cart", token));
        CART_SHAPE.check(cart.data());
        CART_ITEM_SHAPE.check(only(cart.data().path("items")));
        assertThat(cart.data().path("itemsSubtotal").decimalValue()).isEqualByComparingTo("300.00");
        long cartItemId = cart.data().path("items").get(0).path("id").asLong();

        // Shipping quote (no stock touched)
        ApiClient.Response quotes = expect(200, "POST", "/api/v1/checkout/shipping-quotes",
                api.post("/api/v1/checkout/shipping-quotes", token, Map.of("cartItemIds", List.of(cartItemId))));
        QUOTE_SHAPE.check(only(quotes.data()));
        assertThat(units(listing.id(), "LISTED")).isEqualTo(3);

        // Checkout reserves stock and empties the cart
        String key = "tcg755-" + data.tag();
        ApiClient.Response placed = expect(201, "POST", "/api/v1/checkout",
                api.post("/api/v1/checkout", token, Map.of(), Map.of("Idempotency-Key", key)));
        JsonNode order = placed.data();
        CHECKOUT_SHAPE.check(order);
        SELLER_ORDER_SHAPE.check(only(order.path("sellerOrders")));
        ORDER_ITEM_SHAPE.check(only(order.path("sellerOrders").get(0).path("items")));
        assertThat(order.path("grandTotal").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(order.path("replayed").asBoolean()).isFalse();
        long orderId = order.path("orderId").asLong();
        assertThat(units(listing.id(), "RESERVED")).isEqualTo(2);
        assertThat(units(listing.id(), "LISTED")).isEqualTo(1);
        assertThat(dsl.fetchCount(CART_ITEM, CART_ITEM.ID.eq(cartItemId))).isZero();
        assertThat(orderStatus(orderId)).isEqualTo("PENDING_PAYMENT");

        // Idempotent replay: same order, nothing new written
        int ordersBefore = dsl.fetchCount(SALES_ORDER, SALES_ORDER.BUYER_ID.eq(buyer.userId()));
        ApiClient.Response replay = expect(200, "POST", "/api/v1/checkout",
                api.post("/api/v1/checkout", token, Map.of(), Map.of("Idempotency-Key", key)));
        assertThat(replay.data().path("orderId").asLong()).isEqualTo(orderId);
        assertThat(replay.data().path("replayed").asBoolean()).isTrue();
        assertThat(dsl.fetchCount(SALES_ORDER, SALES_ORDER.BUYER_ID.eq(buyer.userId()))).isEqualTo(ordersBefore);

        // Payment + slip upload to object storage
        ApiClient.Response payment = expect(200, "POST", "/api/v1/payments",
                api.post("/api/v1/payments", token, Map.of("salesOrderId", orderId, "method", "BANK_TRANSFER"),
                        Map.of("Idempotency-Key", "pay-" + key)));
        PAYMENT_SHAPE.check(payment.data());
        long paymentId = payment.data().path("id").asLong();
        assertThat(payment.data().path("amount").decimalValue()).isEqualByComparingTo("300.00");
        byte[] slip = png(1);
        String slipKey = upload("PAYMENT_SLIP", token, slip, "image/png");
        assertThat(slipKey).startsWith("payments/");
        PAYMENT_SHAPE.check(expect(200, "POST", "/api/v1/payments/" + paymentId + "/slip",
                api.post("/api/v1/payments/" + paymentId + "/slip", token,
                        Map.of("slipImageKey", slipKey, "referenceCode", "REF-" + key))).data());
        assertThat(dsl.select(PAYMENT.SLIP_IMAGE_KEY).from(PAYMENT).where(PAYMENT.ID.eq(paymentId))
                .fetchSingle(PAYMENT.SLIP_IMAGE_KEY)).isEqualTo(slipKey);

        // Only the admin sees the slip queue; the signed URL serves exactly what was uploaded
        assertThat(api.get("/api/v1/admin/payments", token).status()).isEqualTo(403);
        ApiClient.Response queue = expect(200, "GET", "/api/v1/admin/payments",
                api.get("/api/v1/admin/payments", adminToken));
        JsonNode review = null;
        for (JsonNode row : queue.data()) {
            if (row.path("id").asLong() == paymentId) {
                review = row;
            }
        }
        assertThat(review).as("payment %d in the admin queue", paymentId).isNotNull();
        assertThat(download(review.path("slipUrl").asString())).isEqualTo(slip);

        // Approval: payment, order, stock and escrow all move together
        expect(200, "POST", "/api/v1/admin/payments/" + paymentId + "/verify",
                api.post("/api/v1/admin/payments/" + paymentId + "/verify", adminToken, Map.of("approved", true)));
        assertThat(dsl.select(PAYMENT.STATUS).from(PAYMENT).where(PAYMENT.ID.eq(paymentId))
                .fetchSingle(PAYMENT.STATUS)).isEqualTo("SUCCEEDED");
        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(dsl.select(SELLER_ORDER.STATUS).from(SELLER_ORDER).where(SELLER_ORDER.SALES_ORDER_ID.eq(orderId))
                .fetchSingle(SELLER_ORDER.STATUS)).isEqualTo("PAID");
        assertThat(units(listing.id(), "RESERVED") + units(listing.id(), "SOLD")).isEqualTo(2);
        assertThat(dsl.fetchCount(ESCROW_HOLD, ESCROW_HOLD.SELLER_ORDER_ID.in(
                dsl.select(SELLER_ORDER.ID).from(SELLER_ORDER).where(SELLER_ORDER.SALES_ORDER_ID.eq(orderId))))).isEqualTo(1);

        // The buyer's order view and PDF agree; nobody else can read either
        ApiClient.Response mine = expect(200, "GET", "/api/v1/orders/" + orderId,
                api.get("/api/v1/orders/" + orderId, token));
        assertThat(mine.data().path("grandTotal").decimalValue()).isEqualByComparingTo("300.00");
        ApiClient.BinaryResponse pdf = api.getBytes("/api/v1/orders/" + orderId + "/summary.pdf", token, "application/pdf");
        assertThat(pdf.status()).isEqualTo(200);
        assertThat(new String(pdf.body(), 0, 5)).isEqualTo("%PDF-");
        String stranger = data.bearer(data.buyer());
        assertThat(api.get("/api/v1/orders/" + orderId, stranger).status()).isEqualTo(404);
        assertThat(api.getBytes("/api/v1/orders/" + orderId + "/summary.pdf", stranger, "application/pdf").status())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("checkout that fails while reserving stock leaves no order, no reservation and the cart intact")
    void inventoryFailureRollsBackCheckout() {
        TestData.Seller seller = data.seller();
        TestData.Listing listing = data.onSale(seller, data.card(data.game("Pokemon"), "Rollback " + data.tag(), "R"),
                CardCondition.NM, 2, "90.00");
        AuthPrincipal buyer = data.buyer();
        String token = data.bearer(buyer);
        api.post("/api/v1/cart/items", token, Map.of("listingId", listing.id(), "quantity", 2));
        doThrow(new IllegalStateException("inventory down (TCG-755 fault injection)"))
                .when(inventoryPort).reserve(anyMap());

        ApiClient.Response failed = api.post("/api/v1/checkout", token, Map.of(),
                Map.of("Idempotency-Key", "fail-" + data.tag()));

        assertThat(failed.status()).as(failed.body().toString()).isEqualTo(500);
        assertThat(failed.body().path("success").asBoolean(true)).isFalse();
        assertThat(dsl.fetchCount(SALES_ORDER, SALES_ORDER.BUYER_ID.eq(buyer.userId()))).isZero();
        assertThat(units(listing.id(), "LISTED")).isEqualTo(2);
        assertThat(units(listing.id(), "RESERVED")).isZero();
        assertThat(api.get("/api/v1/cart", token).data().path("totalQuantity").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("ownership and state guards: other buyers, own listings, re-verify and pay-after-cancel are refused")
    void negativeGuardsLeaveNoPartialData() {
        TestData.Seller seller = data.seller();
        TestData.Listing listing = data.onSale(seller, data.card(data.game("Pokemon"), "Guard " + data.tag(), "R"),
                CardCondition.NM, 1, "70.00");
        AuthPrincipal buyer = data.buyer();
        String token = data.bearer(buyer);
        String admin = data.bearer(data.admin());
        String other = data.bearer(data.buyer());

        // A seller cannot buy their own listing
        ApiClient.Response own = api.post("/api/v1/cart/items", data.bearer(seller.principal()),
                Map.of("listingId", listing.id(), "quantity", 1));
        assertThat(own.status()).as(own.body().toString()).isIn(400, 409, 422);

        // Missing idempotency key is a contract error, not an order
        api.post("/api/v1/cart/items", token, Map.of("listingId", listing.id(), "quantity", 1));
        ApiClient.Response noKey = api.post("/api/v1/checkout", token, Map.of());
        assertThat(noKey.status()).as(noKey.body().toString()).isEqualTo(400);
        assertThat(dsl.fetchCount(SALES_ORDER, SALES_ORDER.BUYER_ID.eq(buyer.userId()))).isZero();

        long orderId = api.post("/api/v1/checkout", token, Map.of(), Map.of("Idempotency-Key", "g-" + data.tag()))
                .data().path("orderId").asLong();
        // Another buyer cannot open a payment on this order
        ApiClient.Response foreign = api.post("/api/v1/payments", other,
                Map.of("salesOrderId", orderId, "method", "BANK_TRANSFER"), Map.of("Idempotency-Key", "f-" + data.tag()));
        assertThat(foreign.status()).as(foreign.body().toString()).isIn(403, 404);
        assertThat(dsl.fetchCount(PAYMENT, PAYMENT.SALES_ORDER_ID.eq(orderId))).isZero();

        ApiClient.Response noPayKey = api.post("/api/v1/payments", token,
                Map.of("salesOrderId", orderId, "method", "BANK_TRANSFER"));
        assertThat(noPayKey.status()).isEqualTo(400);
        assertThat(noPayKey.errorCode()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        long paymentId = api.post("/api/v1/payments", token, Map.of("salesOrderId", orderId, "method", "BANK_TRANSFER"),
                Map.of("Idempotency-Key", "p-" + data.tag())).data().path("id").asLong();
        // A slip key from someone else's prefix, or never uploaded, is refused
        ApiClient.Response wrongPrefix = api.post("/api/v1/payments/" + paymentId + "/slip", token,
                Map.of("slipImageKey", "avatars/not-a-slip.png"));
        assertThat(wrongPrefix.status()).isEqualTo(400);
        ApiClient.Response neverUploaded = api.post("/api/v1/payments/" + paymentId + "/slip", token,
                Map.of("slipImageKey", "payments/never-uploaded-" + data.tag() + ".png"));
        assertThat(neverUploaded.status()).isEqualTo(404);
        assertThat(neverUploaded.errorCode()).isEqualTo("FILE_NOT_FOUND");

        // Cancel, then try to verify: the order must not come back to life
        assertThat(api.post("/api/v1/orders/" + orderId + "/cancel", token, Map.of()).status()).isEqualTo(200);
        ApiClient.Response verifyCancelled = api.post("/api/v1/admin/payments/" + paymentId + "/verify", admin,
                Map.of("approved", true));
        assertThat(verifyCancelled.status()).as(verifyCancelled.body().toString()).isIn(409, 422, 400);
        assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
        assertThat(units(listing.id(), "LISTED")).isEqualTo(1);
        assertThat(dsl.fetchCount(ESCROW_HOLD, ESCROW_HOLD.SELLER_ORDER_ID.in(
                dsl.select(SELLER_ORDER.ID).from(SELLER_ORDER).where(SELLER_ORDER.SALES_ORDER_ID.eq(orderId))))).isZero();
    }

    private int units(long listingId, String status) {
        return dsl.fetchCount(LISTING_UNIT, LISTING_UNIT.LISTING_ID.eq(listingId).and(LISTING_UNIT.STATUS.eq(status)));
    }

    private String orderStatus(long orderId) {
        return dsl.select(SALES_ORDER.STATUS).from(SALES_ORDER).where(SALES_ORDER.ID.eq(orderId))
                .fetchSingle(SALES_ORDER.STATUS);
    }
}
