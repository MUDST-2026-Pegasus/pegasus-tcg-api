package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.jooq.tables.CommissionCharge.COMMISSION_CHARGE;
import static com.pegasus.pegasustcgapi.jooq.tables.EscrowHold.ESCROW_HOLD;
import static com.pegasus.pegasustcgapi.jooq.tables.LedgerEntry.LEDGER_ENTRY;
import static com.pegasus.pegasustcgapi.jooq.tables.Refund.REFUND;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerVerification.SELLER_VERIFICATION;
import static com.pegasus.pegasustcgapi.jooq.tables.Wallet.WALLET;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.dto.CartItemRequest;
import com.pegasus.pegasustcgapi.dto.CommissionRuleRequest;
import com.pegasus.pegasustcgapi.dto.ShipOrderRequest;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.repository.PaymentRepository;
import com.pegasus.pegasustcgapi.repository.PayoutAccountRepository;
import com.pegasus.pegasustcgapi.repository.ShippingOptionRepository.OptionFields;
import com.pegasus.pegasustcgapi.storage.StorageService;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class FinanceGoldenFlowIntegrationTest extends PostgresIntegrationTest {

    @Autowired CartService cart;
    @Autowired CheckoutService checkout;
    @Autowired ShippingOptionService shipping;
    @Autowired CommissionRuleService rules;
    @Autowired PaymentService payments;
    @Autowired PaymentRepository paymentRows;
    @Autowired OrderLifecycleService orders;
    @Autowired ReturnService returns;
    @Autowired RefundService refunds;
    @Autowired PayoutAccountRepository accounts;
    @Autowired PayoutService payouts;
    @Autowired WalletLedgerService wallets;
    @MockitoBean StorageService storage;

    @Test
    void twoSellersPartialReturnAndPayoutKeepEverySatang() {
        var seller1 = data.seller();
        var seller2 = data.seller();
        var buyer = data.buyer();
        long adminId = data.admin().userId();
        shipping.create(seller1.userId(), new OptionFields("Standard", "TH_POST",
                money("50.00"), BigDecimal.ZERO, null, (short) 1, (short) 3, true, (short) 0));
        shipping.create(seller2.userId(), new OptionFields("Standard", "TH_POST",
                money("40.00"), BigDecimal.ZERO, null, (short) 1, (short) 3, true, (short) 0));
        rules.create(new CommissionRuleRequest("SELLER", null, null, seller2.profileId(),
                money("5.5"), BigDecimal.ZERO, null, null, (short) 0,
                OffsetDateTime.now(clock).minusDays(1), null, true), adminId);
        var first = data.onSale(seller1, data.card(data.game("Pokemon"), "S1 " + data.tag(), "AR"),
                CardCondition.NM, 1, "1000.00");
        short game2 = data.game("Pokemon");
        var kept = data.onSale(seller2, data.card(game2, "Kept " + data.tag(), "AR"),
                CardCondition.NM, 1, "1000.00");
        var returned = data.onSale(seller2, data.card(game2, "Returned " + data.tag(), "AR"),
                CardCondition.NM, 1, "234.57");
        cart.addItem(buyer, null, new CartItemRequest(first.id(), 1));
        cart.addItem(buyer, null, new CartItemRequest(kept.id(), 1));
        cart.addItem(buyer, null, new CartItemRequest(returned.id(), 1));

        BigDecimal platformBefore = balance(wallets.platformWalletId());
        var order = checkout.checkout(buyer, "golden-" + data.tag(), null, null);
        assertThat(order.grandTotal()).isEqualByComparingTo("2324.57");
        var s1 = order.sellerOrders().stream().filter(s -> s.sellerProfileId() == seller1.profileId())
                .findFirst().orElseThrow();
        var s2 = order.sellerOrders().stream().filter(s -> s.sellerProfileId() == seller2.profileId())
                .findFirst().orElseThrow();
        assertThat(s1.commissionAmount()).isEqualByComparingTo("50.00");
        assertThat(s2.commissionAmount()).isEqualByComparingTo("67.90");
        var payment = payments.create(buyer, order.orderId(), "BANK_TRANSFER", "golden-pay-" + data.tag());
        paymentRows.attachSlip(payment.id(), "payments/golden-" + data.tag() + ".png", "BANK-GOLDEN");
        payments.verify(adminId, payment.id(), true, null);

        orders.shipSellerOrder(seller1.principal(), s1.id(),
                new ShipOrderRequest("Thailand Post", "GOLDEN-1", "TH_POST", null, null));
        orders.autoCompleteSellerOrder(s1.id());
        orders.shipSellerOrder(seller2.principal(), s2.id(),
                new ShipOrderRequest("Thailand Post", "GOLDEN-2", "TH_POST", null, null));
        long returnedItemId = s2.items().stream().filter(i -> i.listingId() == returned.id())
                .findFirst().orElseThrow().id();
        var request = returns.request(buyer.userId(), s2.id(), "DAMAGED", "Damaged card",
                List.of(), List.of(new ReturnService.Item(returnedItemId, 1)));
        returns.approve(seller2.userId(), request.getId(), "PARTIAL_REFUND", money("234.57"), true);
        returns.ship(buyer.userId(), s2.id(), request.getId(), "RETURN-GOLDEN");
        returns.receive(seller2.userId(), request.getId(), true);
        long refundId = dsl.select(REFUND.ID).from(REFUND)
                .where(REFUND.RETURN_REQUEST_ID.eq(request.getId())).fetchSingle(REFUND.ID);
        refunds.processed(refundId, adminId);

        long verificationId = dsl.insertInto(SELLER_VERIFICATION)
                .set(SELLER_VERIFICATION.SELLER_PROFILE_ID, seller1.profileId())
                .set(SELLER_VERIFICATION.STATUS, "APPROVED")
                .set(SELLER_VERIFICATION.LEGAL_FIRST_NAME, "Golden")
                .set(SELLER_VERIFICATION.LEGAL_LAST_NAME, "Seller")
                .set(SELLER_VERIFICATION.BANK_CODE, "TEST")
                .set(SELLER_VERIFICATION.BANK_NAME, "Test Bank")
                .set(SELLER_VERIFICATION.BANK_ACCOUNT_NUMBER, "golden-" + data.tag())
                .returningResult(SELLER_VERIFICATION.ID).fetchSingle(SELLER_VERIFICATION.ID);
        accounts.upsert(seller1.profileId(), verificationId, "TEST", "Test Bank", "Golden Seller", "123456");
        var payout = payouts.request(seller1.userId(), money("500.00"));
        payouts.approve(payout.getId(), adminId);
        payouts.processing(payout.getId());
        payouts.paid(payout.getId(), "BANK-PAYOUT", "payouts/golden.png");

        assertThat(balance(wallets.platformWalletId()).subtract(platformBefore)).isEqualByComparingTo("105.00");
        long wallet1 = dsl.select(WALLET.ID).from(WALLET).where(WALLET.USER_ID.eq(seller1.userId()))
                .fetchSingle(WALLET.ID);
        long wallet2 = dsl.select(WALLET.ID).from(WALLET).where(WALLET.USER_ID.eq(seller2.userId()))
                .fetchSingle(WALLET.ID);
        assertThat(balance(wallet1)).isEqualByComparingTo("500.00");
        assertThat(balance(wallet2)).isEqualByComparingTo("985.00");
        assertThat(dsl.select(COMMISSION_CHARGE.CHARGED_AMOUNT).from(COMMISSION_CHARGE)
                .where(COMMISSION_CHARGE.SELLER_ORDER_ID.eq(s1.id()))
                .fetchSingle(COMMISSION_CHARGE.CHARGED_AMOUNT)).isEqualByComparingTo("50.00");
        assertThat(dsl.select(COMMISSION_CHARGE.CHARGED_AMOUNT).from(COMMISSION_CHARGE)
                .where(COMMISSION_CHARGE.SELLER_ORDER_ID.eq(s2.id()))
                .fetchSingle(COMMISSION_CHARGE.CHARGED_AMOUNT)).isEqualByComparingTo("55.00");
        for (long walletId : List.of(wallets.platformWalletId(), wallet1, wallet2)) {
            BigDecimal ledgerTotal = dsl.select(DSL.coalesce(DSL.sum(
                    DSL.when(LEDGER_ENTRY.DIRECTION.eq("CREDIT"), LEDGER_ENTRY.AMOUNT)
                            .otherwise(LEDGER_ENTRY.AMOUNT.neg())), BigDecimal.ZERO))
                    .from(LEDGER_ENTRY).where(LEDGER_ENTRY.WALLET_ID.eq(walletId))
                    .fetchSingle(0, BigDecimal.class);
            assertThat(balance(walletId)).isEqualByComparingTo(ledgerTotal);
        }
        BigDecimal platformPending = dsl.select(WALLET.BALANCE_PENDING).from(WALLET)
                .where(WALLET.KIND.eq("PLATFORM")).fetchSingle(WALLET.BALANCE_PENDING);
        BigDecimal remainingEscrow = dsl.select(DSL.coalesce(DSL.sum(ESCROW_HOLD.AMOUNT
                .subtract(ESCROW_HOLD.RELEASED_AMOUNT).subtract(ESCROW_HOLD.REFUNDED_AMOUNT)), BigDecimal.ZERO))
                .from(ESCROW_HOLD).fetchSingle(0, BigDecimal.class);
        assertThat(platformPending).isEqualByComparingTo(remainingEscrow);
    }

    private BigDecimal balance(long walletId) {
        return dsl.select(WALLET.BALANCE_AVAILABLE.add(WALLET.BALANCE_PENDING))
                .from(WALLET).where(WALLET.ID.eq(walletId)).fetchSingle(0, BigDecimal.class);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
