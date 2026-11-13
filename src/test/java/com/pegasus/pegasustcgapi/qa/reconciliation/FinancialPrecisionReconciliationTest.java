package com.pegasus.pegasustcgapi.qa.reconciliation;

import static com.pegasus.pegasustcgapi.jooq.tables.CommissionRule.COMMISSION_RULE;
import static com.pegasus.pegasustcgapi.jooq.tables.EscrowHold.ESCROW_HOLD;
import static com.pegasus.pegasustcgapi.jooq.tables.LedgerEntry.LEDGER_ENTRY;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItem.ORDER_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.Promotion.PROMOTION;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.Wallet.WALLET;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.dto.cart.CartItemRequest;
import com.pegasus.pegasustcgapi.dto.order.CheckoutRequest;
import com.pegasus.pegasustcgapi.dto.shipping.ShipOrderRequest;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.promotion.Promotion.DiscountType;
import com.pegasus.pegasustcgapi.model.promotion.Promotion.OwnerType;
import com.pegasus.pegasustcgapi.model.promotion.Promotion.ScopeType;
import com.pegasus.pegasustcgapi.model.promotion.Promotion.Visibility;
import com.pegasus.pegasustcgapi.repository.promotion.PromotionRepository;
import com.pegasus.pegasustcgapi.repository.promotion.PromotionRepository.NewPromotion;
import com.pegasus.pegasustcgapi.service.cart.CartService;
import com.pegasus.pegasustcgapi.service.finance.commission.CommissionCalculator;
import com.pegasus.pegasustcgapi.service.finance.wallet.WalletLedgerService;
import com.pegasus.pegasustcgapi.service.order.CheckoutService;
import com.pegasus.pegasustcgapi.service.order.OrderLifecycleService;
import com.pegasus.pegasustcgapi.service.payment.PaymentService;
import com.pegasus.pegasustcgapi.service.promotion.PromotionCalculator;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * [QA-03-B] Currency Precision, Rounding, Discount Allocation &amp; Conservation Tests.
 */
@DisplayName("[QA-03-B] Currency Precision & Financial Conservation")
class FinancialPrecisionReconciliationTest extends PostgresIntegrationTest {

    @Autowired private CheckoutService checkout;
    @Autowired private CartService cart;
    @Autowired private PaymentService payments;
    @Autowired private OrderLifecycleService orders;
    @Autowired private PromotionRepository promotionRepo;
    @Autowired private WalletLedgerService walletLedgerService;

    private ReconciliationOracle oracle() {
        return new ReconciliationOracle(dsl);
    }

    @Test
    @DisplayName("Precision 1: Odd-Penny Discount Allocation Across Multiple Order Items (Zero Satang Drift)")
    void oddPennyDiscountAllocation_SplitsExactTotalWithZeroDrift() {
        var oracle = oracle();
        var seller = data.seller();
        var buyer = data.buyer();
        var game = data.game("Pokemon");
        var card1 = data.card(game, "Bulbasaur " + data.tag(), "C");
        var card2 = data.card(game, "Ivysaur " + data.tag(), "U");
        var card3 = data.card(game, "Venusaur " + data.tag(), "R");

        // Pure algorithmic validation first: 50.00 THB split across 3 equal weights of 100.00
        var p = com.pegasus.pegasustcgapi.support.PromotionFixture.seller("SHOP50", seller.profileId())
                .fixed("50.00").minSpend("100.00").build();
        var basket = new PromotionCalculator.Basket(
                List.of(
                        new PromotionCalculator.Line(1L, seller.profileId(), game, 1L, new BigDecimal("100.00")),
                        new PromotionCalculator.Line(2L, seller.profileId(), game, 1L, new BigDecimal("100.00")),
                        new PromotionCalculator.Line(3L, seller.profileId(), game, 1L, new BigDecimal("100.00"))
                ),
                Map.of(seller.profileId(), BigDecimal.ZERO)
        );
        var calcResult = PromotionCalculator.calculate(basket, List.of(p));
        BigDecimal sumShares = calcResult.discountByLine().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumShares)
                .as("Algorithmic split of 50.00 THB across 3 lines must sum exactly to 50.00 with zero drift")
                .isEqualByComparingTo("50.00");

        // Database end-to-end checkout with seller promotion
        var l1 = data.onSale(seller, card1, CardCondition.NM, 2, "100.00");
        var l2 = data.onSale(seller, card2, CardCondition.NM, 2, "100.00");
        var l3 = data.onSale(seller, card3, CardCondition.NM, 2, "100.00");

        String promoCode = "SHOP" + data.tag().toUpperCase();
        if (promoCode.length() > 20) promoCode = promoCode.substring(0, 20);
        promotionRepo.insert(new NewPromotion(
                promoCode, "50 Off Shop", null,
                OwnerType.SELLER, seller.profileId(),
                DiscountType.FIXED, new BigDecimal("50.00"), null,
                new BigDecimal("100.00"), ScopeType.ALL, List.of(),
                Visibility.PUBLIC, false, 100, 1,
                false, false, null, false, null,
                OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(30), null));

        cart.addItem(buyer, null, new CartItemRequest(l1.id(), 1));
        cart.addItem(buyer, null, new CartItemRequest(l2.id(), 1));
        cart.addItem(buyer, null, new CartItemRequest(l3.id(), 1));

        var checkoutReq = new CheckoutRequest(null, null, null, null, null, Map.of(seller.profileId(), promoCode), null);
        var response = checkout.checkout(buyer, "odd-penny-" + data.tag(), null, checkoutReq);

        long salesOrderId = response.orderId();
        oracle.assertOrderFinancialConservation(salesOrderId);

        // Verify allocated discount per order_item in DB sums to exactly 50.00
        var items = dsl.selectFrom(ORDER_ITEM)
                .where(ORDER_ITEM.SELLER_ORDER_ID.eq(response.sellerOrders().getFirst().id()))
                .fetch();
        assertThat(items).hasSize(3);

        BigDecimal sumAllocated = BigDecimal.ZERO;
        for (var item : items) {
            assertThat(item.getDiscountAllocated()).isGreaterThan(BigDecimal.ZERO);
            sumAllocated = sumAllocated.add(item.getDiscountAllocated());
        }

        assertThat(sumAllocated)
                .as("Sum of persisted discount_allocated across all order items must equal exactly 50.00 THB")
                .isEqualByComparingTo("50.00");

        oracle.assertGlobalReferentialIntegrity();
    }

    @Test
    @DisplayName("Precision 2: Recurring Decimals in Commission Calculation (13.333%) — Net Derived by Subtraction")
    void recurringDecimalCommissionRate_ExactConservationWithoutDrift() {
        var oracle = oracle();
        var seller = data.seller();
        var buyer = data.buyer();
        var card = data.card(data.game("Pokemon"), "Jirachi " + data.tag(), "PR");

        // 1. CommissionCalculator mathematical proof:
        // Base: 99.99 THB, Rate: 13.333% -> 99.99 * 13.333 / 100 = 13.3316667 -> 13.33 (HALF_UP)
        BigDecimal base = new BigDecimal("99.99");
        BigDecimal rate = new BigDecimal("13.333");
        BigDecimal calculatedFee = CommissionCalculator.fee(base, rate, BigDecimal.ZERO, null, null);
        assertThat(calculatedFee).isEqualByComparingTo("13.33");

        BigDecimal sellerNet = base.subtract(calculatedFee);
        assertThat(sellerNet.add(calculatedFee))
                .as("Conservation of money: sellerNet + fee must equal base exactly")
                .isEqualByComparingTo(base);

        // 2. Database integration with custom commission rule
        dsl.insertInto(COMMISSION_RULE)
                .set(COMMISSION_RULE.SCOPE, "SELLER")
                .set(COMMISSION_RULE.SELLER_PROFILE_ID, seller.profileId())
                .set(COMMISSION_RULE.RATE_PERCENT, new BigDecimal("13.333"))
                .set(COMMISSION_RULE.FIXED_FEE, BigDecimal.ZERO)
                .set(COMMISSION_RULE.PRIORITY, (short) 100)
                .set(COMMISSION_RULE.IS_ACTIVE, true)
                .set(COMMISSION_RULE.EFFECTIVE_FROM, OffsetDateTime.now().minusDays(1))
                .execute();

        var listing = data.onSale(seller, card, CardCondition.NM, 1, "99.99");
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 1));
        var order = checkout.checkout(buyer, "recurring-comm-" + data.tag(), null, null);

        var suborder = dsl.selectFrom(SELLER_ORDER).where(SELLER_ORDER.SALES_ORDER_ID.eq(order.orderId())).fetchSingle();
        assertThat(suborder.getCommissionRatePercent()).isEqualByComparingTo("13.333");
        assertThat(suborder.getCommissionAmount()).isEqualByComparingTo("13.33");
        assertThat(suborder.getSellerNetAmount()).isEqualByComparingTo("86.66"); // 99.99 - 13.33

        // Pay, ship and complete to verify ledger entries
        payments.payMock(buyer, order.orderId());
        orders.shipSellerOrder(seller.principal(), suborder.getId(),
                new ShipOrderRequest("Kerry", "KR-" + data.tag(), "KERRY", null, null));
        orders.confirmBuyerOrderReceived(buyer, order.orderId());

        oracle.assertEscrowReconciliation(suborder.getId());
        oracle.assertWalletEquation(walletLedgerService.platformWalletId());
        long sellerWalletId = dsl.select(WALLET.ID).from(WALLET)
                .where(WALLET.USER_ID.eq(seller.userId())).fetchSingle(WALLET.ID);
        oracle.assertWalletEquation(sellerWalletId);
        oracle.assertOrderFinancialConservation(order.orderId());
        oracle.assertGlobalReferentialIntegrity();

        // Direct ledger verification: COMMISSION_FEE + SELLER_EARNING == ESCROW_RELEASE
        BigDecimal escrowReleased = dsl.select(LEDGER_ENTRY.AMOUNT).from(LEDGER_ENTRY)
                .where(LEDGER_ENTRY.ENTRY_TYPE.eq("ESCROW_RELEASE")
                        .and(LEDGER_ENTRY.REFERENCE_ID.eq(suborder.getId())))
                .fetchSingle(LEDGER_ENTRY.AMOUNT);
        BigDecimal commissionCredited = dsl.select(LEDGER_ENTRY.AMOUNT).from(LEDGER_ENTRY)
                .where(LEDGER_ENTRY.ENTRY_TYPE.eq("COMMISSION_FEE")
                        .and(LEDGER_ENTRY.REFERENCE_ID.eq(suborder.getId())))
                .fetchSingle(LEDGER_ENTRY.AMOUNT);
        BigDecimal sellerCredited = dsl.select(LEDGER_ENTRY.AMOUNT).from(LEDGER_ENTRY)
                .where(LEDGER_ENTRY.ENTRY_TYPE.eq("SELLER_EARNING")
                        .and(LEDGER_ENTRY.REFERENCE_ID.eq(suborder.getId())))
                .fetchSingle(LEDGER_ENTRY.AMOUNT);

        assertThat(escrowReleased).isEqualByComparingTo("99.99");
        assertThat(commissionCredited).isEqualByComparingTo("13.33");
        assertThat(sellerCredited).isEqualByComparingTo("86.66");
        assertThat(commissionCredited.add(sellerCredited)).isEqualByComparingTo(escrowReleased);
    }

    @Test
    @DisplayName("Precision 3: Platform Promotion Subsidy & Commission Base Integrity")
    void platformPromoSubsidy_PreservesCommissionBaseAndConservesMoney() {
        var oracle = oracle();
        var seller = data.seller();
        var buyer = data.buyer();
        var card = data.card(data.game("Pokemon"), "Dialga " + data.tag(), "UR");

        // Seed Platform code: PEGASUS30 (30.00 THB off, min spend 100.00)
        String promoCode = "PEG" + data.tag().toUpperCase();
        if (promoCode.length() > 20) promoCode = promoCode.substring(0, 20);
        promotionRepo.insert(new NewPromotion(
                promoCode, "Pegasus 30 Off", null,
                OwnerType.PLATFORM, null,
                DiscountType.FIXED, new BigDecimal("30.00"), null,
                new BigDecimal("100.00"), ScopeType.ALL, List.of(),
                Visibility.PUBLIC, false, 50, 1,
                false, false, null, false, null,
                OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(30), null));

        var listing = data.onSale(seller, card, CardCondition.NM, 1, "100.00");
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 1));

        var checkoutReq = new CheckoutRequest(null, null, null, null, promoCode, null, null);
        var order = checkout.checkout(buyer, "promo-sub-" + data.tag(), null, checkoutReq);

        // Buyer pays: 100 - 30 = 70.00 THB
        assertThat(order.grandTotal()).isEqualByComparingTo("70.00");

        var suborder = dsl.selectFrom(SELLER_ORDER).where(SELLER_ORDER.SALES_ORDER_ID.eq(order.orderId())).fetchSingle();
        assertThat(suborder.getGrandTotal()).isEqualByComparingTo("70.00");
        assertThat(suborder.getPlatformDiscountAmount()).isEqualByComparingTo("30.00");
        assertThat(suborder.getSellerDiscountAmount()).isEqualByComparingTo("0.00");

        // Commission base is items subtotal (100.00), commission is 5% = 5.00 THB
        // Seller Net Amount = grand_total (70.00) + platform_discount (30.00) - commission (5.00) = 95.00 THB
        assertThat(suborder.getCommissionAmount()).isEqualByComparingTo("5.00");
        assertThat(suborder.getSellerNetAmount()).isEqualByComparingTo("95.00");

        // Pay and complete order
        payments.payMock(buyer, order.orderId());
        orders.shipSellerOrder(seller.principal(), suborder.getId(),
                new ShipOrderRequest("Kerry", "KR-" + data.tag(), "KERRY", null, null));
        orders.confirmBuyerOrderReceived(buyer, order.orderId());

        // Reconcile Escrow and Wallets
        oracle.assertEscrowReconciliation(suborder.getId());
        oracle.assertWalletEquation(walletLedgerService.platformWalletId());
        long sellerWalletId = dsl.select(WALLET.ID).from(WALLET)
                .where(WALLET.USER_ID.eq(seller.userId())).fetchSingle(WALLET.ID);
        oracle.assertWalletEquation(sellerWalletId);

        // Check ledger rows for subsidy and release:
        // ESCROW_RELEASE (70.00 DEBIT from pending)
        // PROMO_SUBSIDY (30.00 DEBIT from platform available)
        // COMMISSION_FEE (5.00 CREDIT to platform available)
        // SELLER_EARNING (95.00 CREDIT to seller available)
        BigDecimal promoSubsidyLedger = dsl.select(LEDGER_ENTRY.AMOUNT).from(LEDGER_ENTRY)
                .where(LEDGER_ENTRY.ENTRY_TYPE.eq("PROMO_SUBSIDY")
                        .and(LEDGER_ENTRY.REFERENCE_ID.eq(suborder.getId())))
                .fetchSingle(LEDGER_ENTRY.AMOUNT);
        BigDecimal sellerEarningLedger = dsl.select(LEDGER_ENTRY.AMOUNT).from(LEDGER_ENTRY)
                .where(LEDGER_ENTRY.ENTRY_TYPE.eq("SELLER_EARNING")
                        .and(LEDGER_ENTRY.REFERENCE_ID.eq(suborder.getId())))
                .fetchSingle(LEDGER_ENTRY.AMOUNT);

        assertThat(promoSubsidyLedger).isEqualByComparingTo("30.00");
        assertThat(sellerEarningLedger).isEqualByComparingTo("95.00");

        // Conservation equation across entire order:
        // buyer_paid (70.00) + platform_subsidy (30.00) == seller_earning (95.00) + platform_commission (5.00)
        assertThat(order.grandTotal().add(promoSubsidyLedger))
                .isEqualByComparingTo(sellerEarningLedger.add(suborder.getCommissionAmount()));

        oracle.assertGlobalReferentialIntegrity();
    }
}
