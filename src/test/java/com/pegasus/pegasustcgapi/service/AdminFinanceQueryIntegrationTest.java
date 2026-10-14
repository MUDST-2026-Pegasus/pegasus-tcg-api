package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.jooq.tables.Refund.REFUND;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerVerification.SELLER_VERIFICATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pegasus.pegasustcgapi.dto.AdminFinanceViews.AdminPayout;
import com.pegasus.pegasustcgapi.dto.AdminFinanceViews.AdminRefund;
import com.pegasus.pegasustcgapi.dto.AdminFinanceViews.PaymentReview;
import com.pegasus.pegasustcgapi.dto.CancelOrderRequest;
import com.pegasus.pegasustcgapi.dto.CartItemRequest;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.repository.PaymentRepository;
import com.pegasus.pegasustcgapi.repository.PayoutAccountRepository;
import com.pegasus.pegasustcgapi.service.WalletLedgerService.Bucket;
import com.pegasus.pegasustcgapi.service.WalletLedgerService.Direction;
import com.pegasus.pegasustcgapi.storage.StorageService;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

class AdminFinanceQueryIntegrationTest extends PostgresIntegrationTest {

    @Autowired AdminFinanceQueryService finance;
    @Autowired CartService cart;
    @Autowired CheckoutService checkout;
    @Autowired OrderLifecycleService orders;
    @Autowired PaymentService payments;
    @Autowired PaymentRepository paymentRows;
    @Autowired RefundService refunds;
    @Autowired PayoutService payouts;
    @Autowired PayoutAccountRepository accounts;
    @Autowired WalletLedgerService ledger;
    @Autowired TransactionTemplate transactions;
    @Autowired WebApplicationContext context;
    @MockitoBean StorageService storage;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        given(storage.presignDownload(anyString())).willReturn("https://slips.test/slip.png");
    }

    @Test
    void slipQueueCarriesBuyerOrderAndPerShopSplit() throws Exception {
        var seller = data.seller();
        var otherSeller = data.seller();
        var buyer = data.buyer();
        var game = data.game("Pokemon");
        var first = data.onSale(seller, data.card(game, "Queue A " + data.tag(), "AR"), CardCondition.NM, 1, "120.00");
        var second = data.onSale(otherSeller, data.card(game, "Queue B " + data.tag(), "AR"), CardCondition.NM, 1, "80.00");
        cart.addItem(buyer, null, new CartItemRequest(first.id(), 1));
        cart.addItem(buyer, null, new CartItemRequest(second.id(), 1));
        var order = checkout.checkout(buyer, "queue-" + data.tag(), null, null);
        var payment = payments.create(buyer, order.orderId(), "BANK_TRANSFER", "queue-pay-" + data.tag());
        paymentRows.attachSlip(payment.id(), "payments/queue-" + data.tag() + ".png", "REF-QUEUE");
        long approvedBefore = finance.paymentSummary().approvedToday();

        PaymentReview row = finance.payments(null).stream()
                .filter(p -> p.id() == payment.id()).findFirst().orElseThrow();
        assertThat(row.orderNumber()).isNotBlank();
        assertThat(row.buyer().id()).isEqualTo(buyer.userId());
        assertThat(row.buyer().username()).isNotBlank();
        assertThat(row.unpaidTotal()).isEqualByComparingTo(row.amount());
        assertThat(row.slipUrl()).isEqualTo("https://slips.test/slip.png");
        assertThat(row.expiresAt()).isAfter(row.createdAt().minusMinutes(1));
        assertThat(row.splits()).hasSize(2);
        assertThat(row.splits().stream().map(s -> s.seller().id()))
                .containsExactlyInAnyOrder(seller.profileId(), otherSeller.profileId());
        assertThat(row.splits().stream().map(s -> s.grandTotal()).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(row.amount());
        assertThat(finance.paymentSummary().awaitingVerification()).isPositive();

        payments.verify(data.admin().userId(), payment.id(), true, null);
        assertThat(finance.paymentSummary().approvedToday()).isEqualTo(approvedBefore + 1);

        mvc.perform(get("/api/v1/admin/payments/summary")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(data.admin().userId())))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approvedToday").isNumber());
    }

    @Test
    void refundQueueCarriesPartiesAndEscrowHold() {
        var seller = data.seller();
        var buyer = data.buyer();
        var listing = data.onSale(seller, data.card(data.game("Pokemon"), "Refund queue " + data.tag(), "AR"),
                CardCondition.NM, 1, "100.00");
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 1));
        var order = checkout.checkout(buyer, "refund-queue-" + data.tag(), null, null);
        var payment = payments.create(buyer, order.orderId(), "BANK_TRANSFER", "refund-pay-" + data.tag());
        paymentRows.attachSlip(payment.id(), "payments/refund-" + data.tag() + ".png", "REF-REFUND");
        payments.verify(data.admin().userId(), payment.id(), true, null);
        orders.cancelBuyerOrder(buyer, order.orderId(), new CancelOrderRequest("Changed my mind"));
        long refundId = dsl.select(REFUND.ID).from(REFUND)
                .where(REFUND.SELLER_ORDER_ID.eq(order.sellerOrders().getFirst().id()))
                .fetchSingle(REFUND.ID);

        AdminRefund ready = finance.refunds(null).stream()
                .filter(r -> r.id() == refundId).findFirst().orElseThrow();
        assertThat(ready.status()).isEqualTo("APPROVED");
        assertThat(ready.buyer().id()).isEqualTo(buyer.userId());
        assertThat(ready.seller().id()).isEqualTo(seller.profileId());
        assertThat(ready.sellerOrderNumber()).isNotBlank();
        assertThat(ready.escrowAmount()).isEqualByComparingTo(ready.amount());
        assertThat(ready.escrowRefunded()).isEqualByComparingTo("0");
        assertThat(ready.accountNumber()).isNull();
        assertThat(ready.failedAttempts()).isZero();
        assertThat(finance.refunds(AdminFinanceQueryService.ALL)).extracting(AdminRefund::id).contains(refundId);
        assertThat(finance.refunds("PROCESSED")).extracting(AdminRefund::id).doesNotContain(refundId);
        assertThat(finance.refundSummary().ready()).isPositive();

        long failedBefore = finance.refundSummary().failedAwaitingBuyer();
        refunds.setDestination(refundId, buyer.userId(), "Test Bank", "Buyer", "1234567890");
        refunds.startTransfer(refundId, data.admin().userId(), "1234567890");
        refunds.transferFailed(refundId, data.admin().userId(), "Account closed");
        AdminRefund failed = finance.refunds(null).stream()
                .filter(r -> r.id() == refundId).findFirst().orElseThrow();
        assertThat(failed.accountNumber()).isEqualTo("1234567890");
        assertThat(failed.failedAttempts()).isEqualTo(1);
        assertThat(failed.lastFailureReason()).isEqualTo("Account closed");
        assertThat(finance.refundSummary().failedAwaitingBuyer()).isEqualTo(failedBefore + 1);
    }

    @Test
    void payoutQueueCarriesAccountWalletAndOrderPreview() {
        var seller = data.seller();
        long verificationId = dsl.insertInto(SELLER_VERIFICATION)
                .set(SELLER_VERIFICATION.SELLER_PROFILE_ID, seller.profileId())
                .set(SELLER_VERIFICATION.STATUS, "APPROVED")
                .set(SELLER_VERIFICATION.LEGAL_FIRST_NAME, "Test")
                .set(SELLER_VERIFICATION.LEGAL_LAST_NAME, "Seller")
                .set(SELLER_VERIFICATION.BANK_CODE, "TEST")
                .set(SELLER_VERIFICATION.BANK_NAME, "Test Bank")
                .set(SELLER_VERIFICATION.BANK_ACCOUNT_NUMBER, "test-" + data.tag())
                .returningResult(SELLER_VERIFICATION.ID).fetchSingle(SELLER_VERIFICATION.ID);
        accounts.upsert(seller.profileId(), verificationId, "TEST", "Test Bank", "Test Seller", "123456");
        long walletId = transactions.execute(status -> ledger.ensureSellerWallet(seller.userId()));
        transactions.executeWithoutResult(status -> ledger.post(walletId, Bucket.AVAILABLE,
                "SELLER_EARNING", Direction.CREDIT, new BigDecimal("300.00"),
                // far above any real seller_order id, so it cannot collide with another test's earning
                "SELLER_ORDER", verificationId + 3_000_000_000L));
        long requestedBefore = finance.payoutSummary().requested();

        var request = payouts.request(seller.userId(), new BigDecimal("120.00"));

        AdminPayout row = finance.payouts("REQUESTED").stream()
                .filter(p -> p.id() == request.getId()).findFirst().orElseThrow();
        assertThat(row.sellerProfileId()).isEqualTo(seller.profileId());
        assertThat(row.seller().id()).isEqualTo(seller.profileId());
        assertThat(row.account().bankName()).isEqualTo("Test Bank");
        assertThat(row.account().accountNumber()).isEqualTo("123456");
        assertThat(row.wallet().available()).isEqualByComparingTo("300.00");
        assertThat(row.wallet().reserved()).isEqualByComparingTo("120.00");
        assertThat(row.ordersLinked()).isFalse();
        assertThat(finance.payoutSummary().requested()).isEqualTo(requestedBefore + 1);

        payouts.approve(request.getId(), data.admin().userId());
        AdminPayout approved = finance.payouts(null).stream()
                .filter(p -> p.id() == request.getId()).findFirst().orElseThrow();
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.approvedAt()).isNotNull();
        assertThat(approved.approvedBy()).isNotBlank();
    }
}
