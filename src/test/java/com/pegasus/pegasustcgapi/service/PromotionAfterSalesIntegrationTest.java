package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.jooq.tables.CommissionCharge.COMMISSION_CHARGE;
import static com.pegasus.pegasustcgapi.jooq.tables.EscrowHold.ESCROW_HOLD;
import static com.pegasus.pegasustcgapi.jooq.tables.LedgerEntry.LEDGER_ENTRY;
import static com.pegasus.pegasustcgapi.jooq.tables.Refund.REFUND;
import static com.pegasus.pegasustcgapi.jooq.tables.Wallet.WALLET;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.dto.AppliedPromotion;
import com.pegasus.pegasustcgapi.dto.CartItemRequest;
import com.pegasus.pegasustcgapi.dto.CheckoutRequest;
import com.pegasus.pegasustcgapi.dto.CheckoutResponse;
import com.pegasus.pegasustcgapi.dto.CommissionRuleRequest;
import com.pegasus.pegasustcgapi.dto.OrderDetailsResponse;
import com.pegasus.pegasustcgapi.dto.OrderItemDetailsResponse;
import com.pegasus.pegasustcgapi.dto.SellerOrderDetailsResponse;
import com.pegasus.pegasustcgapi.dto.ShipOrderRequest;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.Promotion.DiscountType;
import com.pegasus.pegasustcgapi.model.Promotion.OwnerType;
import com.pegasus.pegasustcgapi.model.Promotion.ScopeType;
import com.pegasus.pegasustcgapi.model.Promotion.Visibility;
import com.pegasus.pegasustcgapi.repository.PaymentRepository;
import com.pegasus.pegasustcgapi.repository.PromotionRepository;
import com.pegasus.pegasustcgapi.repository.PromotionRepository.NewPromotion;
import com.pegasus.pegasustcgapi.repository.ShippingOptionRepository.OptionFields;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.storage.StorageService;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Listing;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Figma PRM-O1 after the sale: minmin_tcg (Pikachu ฿450, Gengar ฿380, ship ฿45, 5%
 * commission) with its own MINMIN50, and PEGASUS10 spread over minmin and CardVault BKK.
 */
class PromotionAfterSalesIntegrationTest extends PostgresIntegrationTest {

    @Autowired CartService cart;
    @Autowired CheckoutService checkout;
    @Autowired ShippingOptionService shipping;
    @Autowired CommissionRuleService rules;
    @Autowired PaymentService payments;
    @Autowired PaymentRepository paymentRows;
    @Autowired OrderLifecycleService orders;
    @Autowired ReturnService returns;
    @Autowired RefundService refunds;
    @Autowired WalletLedgerService wallets;
    @Autowired PromotionRepository promotions;
    @MockitoBean StorageService storage;

    private Seller minmin;
    private Seller cardVault;
    private Listing pikachu;
    private Listing gengar;
    private Listing luffy;
    private long adminId;
    private AuthPrincipal buyer;
    private CheckoutResponse order;
    private SellerOrderDetailsResponse minminPart;

    @BeforeEach
    void placeTheFigmaOrder() {
        minmin = data.seller();
        cardVault = data.seller();
        adminId = data.admin().userId();
        long minminShipping = shipping.create(minmin.userId(), new OptionFields("Kerry Express", "KERRY",
                money("45.00"), BigDecimal.ZERO, null, (short) 1, (short) 3, true, (short) 0)).id();
        long cardVaultShipping = shipping.create(cardVault.userId(), new OptionFields("Free", "KERRY",
                BigDecimal.ZERO, BigDecimal.ZERO, null, (short) 1, (short) 3, true, (short) 0)).id();
        for (Seller s : List.of(minmin, cardVault)) {
            rules.create(new CommissionRuleRequest("SELLER", null, null, s.profileId(), money("5"),
                    BigDecimal.ZERO, null, null, (short) 0, OffsetDateTime.now(clock).minusDays(1), null, true),
                    adminId);
        }
        short pokemon = data.game("Pokemon");
        pikachu = data.onSale(minmin, data.card(pokemon, "Pikachu " + data.tag(), "SAR"), CardCondition.NM, 1,
                "450.00");
        gengar = data.onSale(minmin, data.card(pokemon, "Gengar " + data.tag(), "HR"), CardCondition.LP, 1,
                "380.00");
        luffy = data.onSale(cardVault, data.card(data.game("One Piece"), "Luffy " + data.tag(), "SEC"),
                CardCondition.NM, 1, "2400.00");
        String pegasus10 = code("PEGASUS10");
        String minmin50 = code("MINMIN50");
        insert(pegasus10, OwnerType.PLATFORM, null, DiscountType.PERCENT, "10", "200", "0");
        insert(minmin50, OwnerType.SELLER, minmin.profileId(), DiscountType.FIXED, "50", null, "500");

        buyer = data.buyer();
        for (Listing l : List.of(pikachu, gengar, luffy)) cart.addItem(buyer, null, new CartItemRequest(l.id(), 1));
        order = checkout.checkout(buyer, "after-sales-" + data.tag(), null, new CheckoutRequest(null,
                Map.of(minmin.profileId(), minminShipping, cardVault.profileId(), cardVaultShipping), null, null,
                pegasus10, Map.of(minmin.profileId(), minmin50), money("3025.00")));
        minminPart = order.sellerOrders().stream().filter(s -> s.sellerProfileId() == minmin.profileId())
                .map(s -> orders.toSellerOrderDetailsResponse(s.id())).findFirst().orElseThrow();
    }

    @Test
    void orderPagesShowTheSplitAndTheRefundEachCardWouldGet() {
        OrderDetailsResponse unpaid = orders.toOrderDetailsResponse(order.orderId());
        assertThat(unpaid.sellerDiscountTotal()).isEqualByComparingTo("50.00");
        assertThat(unpaid.platformDiscountTotal()).isEqualByComparingTo("200.00");
        assertThat(unpaid.promotions()).extracting(AppliedPromotion::ownerType)
                .containsExactlyInAnyOrder(OwnerType.PLATFORM, OwnerType.SELLER);
        assertThat(unpaid.promotionsRestoredOnCancel()).isTrue();

        assertThat(minminPart.sellerDiscount()).isEqualByComparingTo("50.00");
        assertThat(minminPart.platformDiscount()).isEqualByComparingTo("49.06");
        assertThat(minminPart.platformSubsidy()).isEqualByComparingTo("49.06");
        assertThat(minminPart.commissionBase()).isEqualByComparingTo("780.00");
        assertThat(minminPart.sellerNetAmount()).isEqualByComparingTo("786.00");
        assertThat(minminPart.promotions()).anySatisfy(p -> {
            assertThat(p.ownerType()).isEqualTo(OwnerType.PLATFORM);
            assertThat(p.amount()).isEqualByComparingTo("49.06");
        });
        OrderItemDetailsResponse gengarLine = line(minminPart, gengar);
        assertThat(gengarLine.discountAllocated()).isEqualByComparingTo("45.35");
        assertThat(gengarLine.sellerDiscountAllocated()).isEqualByComparingTo("22.89");
        assertThat(gengarLine.platformDiscountAllocated()).isEqualByComparingTo("22.46");
        assertThat(gengarLine.refundEstimatePerUnit()).isEqualByComparingTo("334.65");

        pay();
        assertThat(orders.toOrderDetailsResponse(order.orderId()).promotionsRestoredOnCancel()).isFalse();
    }

    @Test
    void releaseMatchesPrmO1Ledger() {
        pay();
        orders.shipSellerOrder(minmin.principal(), minminPart.id(),
                new ShipOrderRequest("Kerry Express", "PRM-O1", "KERRY", null, null));
        orders.autoCompleteSellerOrder(minminPart.id());

        assertThat(ledger(minminPart.id())).containsExactlyInAnyOrderEntriesOf(Map.of(
                "ESCROW_RELEASE", money("775.94"),
                "PROMO_SUBSIDY", money("49.06"),
                "COMMISSION_FEE", money("39.00"),
                "SELLER_EARNING", money("786.00")));
        assertThat(commissionBase(minminPart.id())).isEqualByComparingTo("780.00");
        assertLedgerBalances();
    }

    @Test
    void returningGengarRefundsWhatWasPaidAndShrinksTheSubsidy() {
        pay();
        orders.shipSellerOrder(minmin.principal(), minminPart.id(),
                new ShipOrderRequest("Kerry Express", "PRM-RET", "KERRY", null, null));
        var request = returns.request(buyer.userId(), minminPart.id(), "DAMAGED", "Bent corner", List.of(),
                List.of(new ReturnService.Item(line(minminPart, gengar).id(), 1)));
        assertThat(returns.sellerReturns(minmin.userId(), null).getFirst().refundEstimate())
                .isEqualByComparingTo("334.65");

        // The shop takes the suggestion by leaving the amount out.
        returns.approve(minmin.userId(), request.getId(), "PARTIAL_REFUND", null, true);
        returns.ship(buyer.userId(), minminPart.id(), request.getId(), "RETURN-PRM");
        returns.receive(minmin.userId(), request.getId(), true);
        long refundId = dsl.select(REFUND.ID).from(REFUND)
                .where(REFUND.RETURN_REQUEST_ID.eq(request.getId())).fetchSingle(REFUND.ID);
        assertThat(dsl.select(REFUND.AMOUNT).from(REFUND).where(REFUND.ID.eq(refundId)).fetchSingle(REFUND.AMOUNT))
                .isEqualByComparingTo("334.65");
        refunds.setDestination(refundId, buyer.userId(), "Test Bank", "Test Buyer", "1234567890");
        refunds.startTransfer(refundId, adminId, "1234567890");
        refunds.processed(refundId, adminId, "REF-PRM");
        orders.autoCompleteSellerOrder(minminPart.id());

        // Pikachu and the postage stay: 775.94 - 334.65 released, Pegasus's 49.06 less
        // Gengar's 22.46, commission on 780 less Gengar's 357.11.
        assertThat(ledger(minminPart.id())).containsAllEntriesOf(Map.of(
                "ESCROW_RELEASE", money("441.29"),
                "PROMO_SUBSIDY", money("26.60"),
                "COMMISSION_FEE", money("21.14"),
                "SELLER_EARNING", money("446.75")));
        assertThat(commissionBase(minminPart.id())).isEqualByComparingTo("422.89");
        assertLedgerBalances();
    }

    private void pay() {
        var payment = payments.create(buyer, order.orderId(), "BANK_TRANSFER", "prm-pay-" + data.tag());
        paymentRows.attachSlip(payment.id(), "payments/prm-" + data.tag() + ".png", "BANK-PRM-" + data.tag());
        payments.verify(adminId, payment.id(), true, null);
    }

    /** Ledger lines posted at release for this order part, by type. */
    private Map<String, BigDecimal> ledger(long sellerOrderId) {
        return dsl.select(LEDGER_ENTRY.ENTRY_TYPE, LEDGER_ENTRY.AMOUNT).from(LEDGER_ENTRY)
                .where(LEDGER_ENTRY.REFERENCE_TYPE.eq("SELLER_ORDER"))
                .and(LEDGER_ENTRY.REFERENCE_ID.eq(sellerOrderId))
                .and(LEDGER_ENTRY.ENTRY_TYPE.in("ESCROW_RELEASE", "PROMO_SUBSIDY", "COMMISSION_FEE",
                        "SELLER_EARNING"))
                .fetchMap(LEDGER_ENTRY.ENTRY_TYPE, LEDGER_ENTRY.AMOUNT);
    }

    private BigDecimal commissionBase(long sellerOrderId) {
        return dsl.select(COMMISSION_CHARGE.BASE_AMOUNT).from(COMMISSION_CHARGE)
                .where(COMMISSION_CHARGE.SELLER_ORDER_ID.eq(sellerOrderId))
                .fetchSingle(COMMISSION_CHARGE.BASE_AMOUNT);
    }

    /** Every wallet still equals its own ledger, and Pegasus's pending equals the escrow it holds. */
    private void assertLedgerBalances() {
        for (long walletId : dsl.select(WALLET.ID).from(WALLET).fetch(WALLET.ID)) {
            BigDecimal ledgerTotal = dsl.select(DSL.coalesce(DSL.sum(
                            DSL.when(LEDGER_ENTRY.DIRECTION.eq("CREDIT"), LEDGER_ENTRY.AMOUNT)
                                    .otherwise(LEDGER_ENTRY.AMOUNT.neg())), BigDecimal.ZERO))
                    .from(LEDGER_ENTRY).where(LEDGER_ENTRY.WALLET_ID.eq(walletId))
                    .fetchSingle(0, BigDecimal.class);
            BigDecimal balance = dsl.select(WALLET.BALANCE_AVAILABLE.add(WALLET.BALANCE_PENDING))
                    .from(WALLET).where(WALLET.ID.eq(walletId)).fetchSingle(0, BigDecimal.class);
            assertThat(balance).as("wallet %d", walletId).isEqualByComparingTo(ledgerTotal);
        }
        BigDecimal platformPending = dsl.select(WALLET.BALANCE_PENDING).from(WALLET)
                .where(WALLET.KIND.eq("PLATFORM")).fetchSingle(WALLET.BALANCE_PENDING);
        BigDecimal remainingEscrow = dsl.select(DSL.coalesce(DSL.sum(ESCROW_HOLD.AMOUNT
                        .subtract(ESCROW_HOLD.RELEASED_AMOUNT).subtract(ESCROW_HOLD.REFUNDED_AMOUNT)),
                        BigDecimal.ZERO))
                .from(ESCROW_HOLD).fetchSingle(0, BigDecimal.class);
        assertThat(platformPending).isEqualByComparingTo(remainingEscrow);
    }

    private static OrderItemDetailsResponse line(SellerOrderDetailsResponse part, Listing listing) {
        return part.items().stream().filter(i -> i.listingId() == listing.id()).findFirst().orElseThrow();
    }

    private void insert(String code, OwnerType owner, Long shop, DiscountType type, String value, String max,
            String minSpend) {
        promotions.insert(new NewPromotion(code, code, null, owner, shop, type, money(value),
                max == null ? null : money(max), money(minSpend), ScopeType.ALL, List.of(), Visibility.PRIVATE,
                false, null, 1, false, false, null, false, null,
                OffsetDateTime.now(clock).minusDays(1), OffsetDateTime.now(clock).plusDays(30), null));
    }

    private String code(String prefix) {
        return (prefix + data.tag()).toUpperCase(Locale.ROOT);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
