package com.pegasus.pegasustcgapi.contract;

import static com.pegasus.pegasustcgapi.jooq.tables.EscrowHold.ESCROW_HOLD;
import static com.pegasus.pegasustcgapi.jooq.tables.LedgerEntry.LEDGER_ENTRY;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingUnit.LISTING_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.Payout.PAYOUT;
import static com.pegasus.pegasustcgapi.jooq.tables.Refund.REFUND;
import static com.pegasus.pegasustcgapi.jooq.tables.ReturnRequest.RETURN_REQUEST;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerVerification.SELLER_VERIFICATION;
import static com.pegasus.pegasustcgapi.jooq.tables.Wallet.WALLET;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.repository.finance.payout.PayoutAccountRepository;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.TestData;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** TCG-755 journey 4: Return → Refund → Wallet/Payout, over HTTP, with evidence in MinIO. */
@DisplayName("TCG-755 Return → Refund → Wallet / Payout")
class ReturnRefundPayoutJourneyIntegrationTest extends CrossModuleJourneyTest {

    static final FeShape REFUND_SHAPE = FeShape.of("Refund (finance.types.ts)")
            .field("id", "number").field("salesOrderId", "number").field("sellerOrderId", "number|null")
            .field("amount", "number").field("reason", "string").field("status", "string").field("method", "string")
            .field("bankName", "string|null").field("accountName", "string|null").field("accountNumber", "string|null")
            .field("transferReference", "string|null").field("requestedAt", "string").field("processedAt", "string|null");
    static final FeShape PAYOUT_SHAPE = FeShape.of("Payout (finance.types.ts)")
            .field("id", "number").field("sellerProfileId", "number").field("amount", "number")
            .field("currency", "string").field("status", "string").field("requestedAt", "string")
            .field("approvedAt", "string|null").field("paidAt", "string|null")
            .field("transferReference", "string|null").field("rejectionReason", "string|null");
    static final FeShape WALLET_SHAPE = FeShape.of("Wallet (finance.types.ts)")
            .field("balanceAvailable", "number").field("balancePending", "number")
            .field("reservedForPayout", "number").field("withdrawable", "number").field("entries", "object");

    @Autowired PayoutAccountRepository payoutAccounts;

    @Test
    @DisplayName("a partial return is refunded to the buyer, the rest is released to the seller and paid out")
    void returnRefundWalletAndPayoutEndToEnd() {
        TestData.Seller seller = data.seller();
        short game = data.game("Pokemon");
        TestData.Listing kept = data.onSale(seller, data.card(game, "Kept " + data.tag(), "R"), CardCondition.NM, 1, "800.00");
        TestData.Listing returned = data.onSale(seller, data.card(game, "Returned " + data.tag(), "R"), CardCondition.NM, 1, "200.00");
        AuthPrincipal buyer = data.buyer();
        String buyerToken = data.bearer(buyer);
        String sellerToken = data.bearer(seller.principal());
        var admin = data.admin();
        // Upload purposes check roles stored in the DB, so give this admin a real ADMIN role row.
        dsl.insertInto(com.pegasus.pegasustcgapi.jooq.tables.UserRole.USER_ROLE)
                .set(com.pegasus.pegasustcgapi.jooq.tables.UserRole.USER_ROLE.USER_ID, admin.userId())
                .set(com.pegasus.pegasustcgapi.jooq.tables.UserRole.USER_ROLE.ROLE_ID,
                        dsl.select(com.pegasus.pegasustcgapi.jooq.tables.AppRole.APP_ROLE.ID)
                                .from(com.pegasus.pegasustcgapi.jooq.tables.AppRole.APP_ROLE)
                                .where(com.pegasus.pegasustcgapi.jooq.tables.AppRole.APP_ROLE.CODE.eq("ADMIN")))
                .execute();
        String adminToken = data.bearer(admin);

        // A paid (bank transfer + slip, so the refund goes back by transfer), shipped order
        api.post("/api/v1/cart/items", buyerToken, Map.of("listingId", kept.id(), "quantity", 1));
        api.post("/api/v1/cart/items", buyerToken, Map.of("listingId", returned.id(), "quantity", 1));
        JsonNode order = expect(201, "POST", "/api/v1/checkout", api.post("/api/v1/checkout", buyerToken, Map.of(),
                Map.of("Idempotency-Key", "rrp-" + data.tag()))).data();
        long orderId = order.path("orderId").asLong();
        JsonNode sellerOrder = only(order.path("sellerOrders"));
        long sellerOrderId = sellerOrder.path("id").asLong();
        BigDecimal commission = sellerOrder.path("commissionAmount").decimalValue();
        payBySlip(orderId, buyerToken, adminToken);
        expect(200, "POST", "/api/v1/sellers/me/orders/" + sellerOrderId + "/ship",
                api.post("/api/v1/sellers/me/orders/" + sellerOrderId + "/ship", sellerToken,
                        Map.of("carrierName", "Thailand Post", "trackingNumber", "TH" + data.tag(), "carrierCode", "TH_POST")));
        long returnedItemId = 0;
        for (JsonNode item : sellerOrder.path("items")) {
            if (item.path("listingId").asLong() == returned.id()) {
                returnedItemId = item.path("id").asLong();
            }
        }

        // Return request with photo evidence stored in MinIO
        byte[] photo = png(42);
        String evidenceKey = upload("RETURN_EVIDENCE", buyerToken, photo, "image/png");
        ApiClient.Response requested = expect(200, "POST", "/api/v1/orders/" + sellerOrderId + "/returns",
                api.post("/api/v1/orders/" + sellerOrderId + "/returns", buyerToken, Map.of(
                        "reasonCode", "DAMAGED", "description", "Bent corner",
                        "evidenceKeys", List.of(evidenceKey),
                        "items", List.of(Map.of("orderItemId", returnedItemId, "quantity", 1)))));
        long returnId = requested.data().path("id").asLong();
        assertThat(dsl.fetchCount(RETURN_REQUEST, RETURN_REQUEST.ID.eq(returnId))).isEqualTo(1);

        // Only the shop sees the evidence, and the signed link serves the uploaded bytes
        ApiClient.Response evidence = expect(200, "GET", "/api/v1/sellers/me/returns/" + returnId + "/evidence",
                api.get("/api/v1/sellers/me/returns/" + returnId + "/evidence", sellerToken));
        assertThat(download(only(evidence.data()).path("url").asString())).isEqualTo(photo);
        String otherSeller = data.bearer(data.seller().principal());
        assertThat(api.get("/api/v1/sellers/me/returns/" + returnId + "/evidence", otherSeller).status()).isIn(403, 404);

        // Seller approves, buyer ships back, seller receives and restocks
        expect(200, "POST", "/api/v1/sellers/me/returns/" + returnId + "/approve",
                api.post("/api/v1/sellers/me/returns/" + returnId + "/approve", sellerToken,
                        Map.of("resolution", "PARTIAL_REFUND", "refundAmount", 200.00, "requireShipBack", true)));
        expect(200, "POST", "/api/v1/orders/" + sellerOrderId + "/returns/" + returnId + "/ship",
                api.post("/api/v1/orders/" + sellerOrderId + "/returns/" + returnId + "/ship", buyerToken,
                        Map.of("trackingNumber", "RT" + data.tag())));
        expect(200, "POST", "/api/v1/sellers/me/returns/" + returnId + "/receive",
                api.post("/api/v1/sellers/me/returns/" + returnId + "/receive", sellerToken, Map.of("restock", true)));
        // Restock puts the card back in the shop's inventory as RETURNED, off the old listing.
        var unit = dsl.select(LISTING_UNIT.STATUS, LISTING_UNIT.LISTING_ID).from(LISTING_UNIT)
                .where(LISTING_UNIT.PUBLIC_UID.eq(returned.units().getFirst().publicUid())).fetchSingle();
        assertThat(unit.value1()).isEqualTo("RETURNED");
        assertThat(unit.value2()).isNull();

        // Refund: buyer gives a destination, admin transfers
        ApiClient.Response refunds = expect(200, "GET", "/api/v1/refunds", api.get("/api/v1/refunds", buyerToken));
        JsonNode refund = only(refunds.data());
        REFUND_SHAPE.check(refund);
        long refundId = refund.path("id").asLong();
        assertThat(refund.path("amount").decimalValue()).isEqualByComparingTo("200.00");
        REFUND_SHAPE.check(expect(200, "PUT", "/api/v1/refunds/" + refundId + "/destination",
                api.put("/api/v1/refunds/" + refundId + "/destination", buyerToken,
                        Map.of("bankName", "Test Bank", "accountName", "Test Buyer", "accountNumber", "1234567890"))).data());
        assertThat(api.get("/api/v1/refunds/" + refundId, data.bearer(data.buyer())).status())
                .as("another buyer cannot read the refund").isIn(403, 404);
        expect(200, "POST", "/api/v1/admin/refunds/" + refundId + "/processing",
                api.post("/api/v1/admin/refunds/" + refundId + "/processing", adminToken, Map.of("accountNumber", "1234567890")));
        expect(200, "POST", "/api/v1/admin/refunds/" + refundId + "/processed",
                api.post("/api/v1/admin/refunds/" + refundId + "/processed", adminToken, Map.of("transferReference", "RF-" + data.tag())));
        assertThat(dsl.select(REFUND.STATUS).from(REFUND).where(REFUND.ID.eq(refundId)).fetchSingle(REFUND.STATUS))
                .isEqualTo("PROCESSED");

        // A partial refund closes the return: the seller order completes and the kept part's escrow is
        // released. Confirming receipt afterwards is an invalid transition and changes nothing.
        ApiClient.Response lateConfirm = api.post("/api/v1/orders/" + orderId + "/confirm-received", buyerToken, null);
        assertThat(lateConfirm.status()).isEqualTo(409);
        assertThat(lateConfirm.errorCode()).isEqualTo("ORDER_STATUS_TRANSITION");
        assertThat(dsl.select(SELLER_ORDER.STATUS).from(SELLER_ORDER).where(SELLER_ORDER.ID.eq(sellerOrderId))
                .fetchSingle(SELLER_ORDER.STATUS)).isEqualTo("COMPLETED");
        BigDecimal escrowLeft = dsl.select(ESCROW_HOLD.AMOUNT.subtract(ESCROW_HOLD.RELEASED_AMOUNT).subtract(ESCROW_HOLD.REFUNDED_AMOUNT))
                .from(ESCROW_HOLD).where(ESCROW_HOLD.SELLER_ORDER_ID.eq(sellerOrderId)).fetchSingle(0, BigDecimal.class);
        assertThat(escrowLeft).isEqualByComparingTo("0");

        ApiClient.Response wallet = expect(200, "GET", "/api/v1/me/wallet", api.get("/api/v1/me/wallet?page=0&size=20", sellerToken));
        WALLET_SHAPE.check(wallet.data());
        BigDecimal available = wallet.data().path("balanceAvailable").decimalValue();
        // What the shop keeps: the kept card minus its share of commission. Commission on the
        // returned card is recomputed by the platform, so compare with the ledger, not a constant.
        assertThat(available).isPositive().isLessThanOrEqualTo(new BigDecimal("800.00"));
        assertThat(available).isGreaterThanOrEqualTo(new BigDecimal("800.00").subtract(commission));
        long walletId = dsl.select(WALLET.ID).from(WALLET).where(WALLET.USER_ID.eq(seller.userId())).fetchSingle(WALLET.ID);
        assertThat(ledgerTotal(walletId)).isEqualByComparingTo(available.add(wallet.data().path("balancePending").decimalValue()));

        // Payout: needs a verified bank account; too much is refused, the rest is paid with a slip
        long verificationId = dsl.insertInto(SELLER_VERIFICATION)
                .set(SELLER_VERIFICATION.SELLER_PROFILE_ID, seller.profileId())
                .set(SELLER_VERIFICATION.STATUS, "APPROVED")
                .set(SELLER_VERIFICATION.LEGAL_FIRST_NAME, "Journey")
                .set(SELLER_VERIFICATION.LEGAL_LAST_NAME, "Seller")
                .set(SELLER_VERIFICATION.BANK_CODE, "TEST")
                .set(SELLER_VERIFICATION.BANK_NAME, "Test Bank")
                .set(SELLER_VERIFICATION.BANK_ACCOUNT_NUMBER, "acct-" + data.tag())
                .returningResult(SELLER_VERIFICATION.ID).fetchSingle(SELLER_VERIFICATION.ID);
        payoutAccounts.upsert(seller.profileId(), verificationId, "TEST", "Test Bank", "Journey Seller", "123456");

        ApiClient.Response tooMuch = api.post("/api/v1/sellers/me/payouts", sellerToken, Map.of("amount", available.add(BigDecimal.ONE)));
        assertThat(tooMuch.status()).as(tooMuch.body().toString()).isIn(400, 409, 422);
        assertThat(dsl.fetchCount(PAYOUT, PAYOUT.SELLER_PROFILE_ID.eq(seller.profileId()))).isZero();

        BigDecimal amount = new BigDecimal("300.00").min(available);
        ApiClient.Response requestedPayout = expect(200, "POST", "/api/v1/sellers/me/payouts",
                api.post("/api/v1/sellers/me/payouts", sellerToken, Map.of("amount", amount)));
        PAYOUT_SHAPE.check(requestedPayout.data());
        long payoutId = requestedPayout.data().path("id").asLong();
        assertThat(api.post("/api/v1/uploads/presign", sellerToken,
                Map.of("purpose", "PAYOUT_SLIP", "contentType", "image/png", "sizeBytes", 100)).status())
                .as("only admins may upload payout slips").isEqualTo(403);
        expect(200, "POST", "/api/v1/admin/payouts/" + payoutId + "/approve",
                api.post("/api/v1/admin/payouts/" + payoutId + "/approve", adminToken, null));
        expect(200, "POST", "/api/v1/admin/payouts/" + payoutId + "/processing",
                api.post("/api/v1/admin/payouts/" + payoutId + "/processing", adminToken, null));
        String slipKey = upload("PAYOUT_SLIP", adminToken, png(7), "image/png");
        expect(200, "POST", "/api/v1/admin/payouts/" + payoutId + "/paid",
                api.post("/api/v1/admin/payouts/" + payoutId + "/paid", adminToken,
                        Map.of("transferReference", "PO-" + data.tag(), "slipImageKey", slipKey)));
        assertThat(dsl.select(PAYOUT.STATUS).from(PAYOUT).where(PAYOUT.ID.eq(payoutId)).fetchSingle(PAYOUT.STATUS))
                .isEqualTo("PAID");
        JsonNode after = api.get("/api/v1/me/wallet?page=0&size=20", sellerToken).data();
        assertThat(after.path("balanceAvailable").decimalValue()).isEqualByComparingTo(available.subtract(amount));
        assertThat(ledgerTotal(walletId)).isEqualByComparingTo(
                after.path("balanceAvailable").decimalValue().add(after.path("balancePending").decimalValue()));
    }

    @Test
    @DisplayName("returns are refused for orders not yet shipped, for other buyers' orders and for keys outside returns/")
    void returnGuards() {
        TestData.Seller seller = data.seller();
        TestData.Listing listing = data.onSale(seller, data.card(data.game("Pokemon"), "RG " + data.tag(), "R"), CardCondition.NM, 1, "100.00");
        AuthPrincipal buyer = data.buyer();
        String token = data.bearer(buyer);
        api.post("/api/v1/cart/items", token, Map.of("listingId", listing.id(), "quantity", 1));
        JsonNode order = api.post("/api/v1/checkout", token, Map.of(), Map.of("Idempotency-Key", "rg-" + data.tag())).data();
        long orderId = order.path("orderId").asLong();
        JsonNode sellerOrder = only(order.path("sellerOrders"));
        long sellerOrderId = sellerOrder.path("id").asLong();
        long itemId = only(sellerOrder.path("items")).path("id").asLong();
        api.post("/api/v1/orders/" + orderId + "/pay", token, null);
        Map<String, Object> body = Map.of("reasonCode", "DAMAGED", "evidenceKeys", List.of(),
                "items", List.of(Map.of("orderItemId", itemId, "quantity", 1)));

        ApiClient.Response notShipped = api.post("/api/v1/orders/" + sellerOrderId + "/returns", token, body);
        assertThat(notShipped.status()).as(notShipped.body().toString()).isIn(400, 409, 422);

        api.post("/api/v1/sellers/me/orders/" + sellerOrderId + "/ship", data.bearer(seller.principal()),
                Map.of("carrierName", "Thailand Post", "trackingNumber", "TH" + data.tag(), "carrierCode", "TH_POST"));
        ApiClient.Response stranger = api.post("/api/v1/orders/" + sellerOrderId + "/returns", data.bearer(data.buyer()), body);
        assertThat(stranger.status()).isIn(403, 404);
        ApiClient.Response foreignKey = api.post("/api/v1/orders/" + sellerOrderId + "/returns", token, Map.of(
                "reasonCode", "DAMAGED", "evidenceKeys", List.of("payments/someone-elses-slip.png"),
                "items", List.of(Map.of("orderItemId", itemId, "quantity", 1))));
        assertThat(foreignKey.status()).as(foreignKey.body().toString()).isEqualTo(400);
        ApiClient.Response tooMany = api.post("/api/v1/orders/" + sellerOrderId + "/returns", token, Map.of(
                "reasonCode", "DAMAGED", "evidenceKeys", List.of(),
                "items", List.of(Map.of("orderItemId", itemId, "quantity", 2))));
        assertThat(tooMany.status()).as(tooMany.body().toString()).isIn(400, 409, 422);
        assertThat(dsl.fetchCount(RETURN_REQUEST, RETURN_REQUEST.SELLER_ORDER_ID.eq(sellerOrderId))).isZero();
        assertThat(dsl.fetchCount(REFUND, REFUND.SELLER_ORDER_ID.eq(sellerOrderId))).isZero();
    }

    private BigDecimal ledgerTotal(long walletId) {
        return dsl.select(DSL.coalesce(DSL.sum(DSL.when(LEDGER_ENTRY.DIRECTION.eq("CREDIT"), LEDGER_ENTRY.AMOUNT)
                        .otherwise(LEDGER_ENTRY.AMOUNT.neg())), BigDecimal.ZERO))
                .from(LEDGER_ENTRY).where(LEDGER_ENTRY.WALLET_ID.eq(walletId)).fetchSingle(0, BigDecimal.class);
    }
}
