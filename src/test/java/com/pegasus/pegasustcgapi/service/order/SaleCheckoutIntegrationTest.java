package com.pegasus.pegasustcgapi.service.order;

import static com.pegasus.pegasustcgapi.jooq.tables.CommissionCharge.COMMISSION_CHARGE;
import static com.pegasus.pegasustcgapi.jooq.tables.EscrowHold.ESCROW_HOLD;
import static com.pegasus.pegasustcgapi.jooq.tables.LedgerEntry.LEDGER_ENTRY;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingSalePrice.LISTING_SALE_PRICE;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItem.ORDER_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.PriceSale.PRICE_SALE;
import static com.pegasus.pegasustcgapi.jooq.tables.PriceSaleListing.PRICE_SALE_LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.Refund.REFUND;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.Wallet.WALLET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pegasus.pegasustcgapi.dto.cart.CartItemRequest;
import com.pegasus.pegasustcgapi.dto.finance.commission.CommissionRuleRequest;
import com.pegasus.pegasustcgapi.dto.order.CheckoutRequest;
import com.pegasus.pegasustcgapi.dto.order.CheckoutResponse;
import com.pegasus.pegasustcgapi.dto.order.OrderItemDetailsResponse;
import com.pegasus.pegasustcgapi.dto.order.SellerOrderDetailsResponse;
import com.pegasus.pegasustcgapi.dto.shipping.ShipOrderRequest;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.jooq.tables.records.OrderItemRecord;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.promotion.Promotion.DiscountType;
import com.pegasus.pegasustcgapi.model.promotion.Promotion.OwnerType;
import com.pegasus.pegasustcgapi.model.promotion.Promotion.ScopeType;
import com.pegasus.pegasustcgapi.model.promotion.Promotion.Visibility;
import com.pegasus.pegasustcgapi.repository.payment.PaymentRepository;
import com.pegasus.pegasustcgapi.repository.promotion.PromotionRepository;
import com.pegasus.pegasustcgapi.repository.promotion.PromotionRepository.NewPromotion;
import com.pegasus.pegasustcgapi.repository.shipping.ShippingOptionRepository.OptionFields;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.aftersales.ReturnService;
import com.pegasus.pegasustcgapi.service.cart.CartService;
import com.pegasus.pegasustcgapi.service.finance.commission.CommissionRuleService;
import com.pegasus.pegasustcgapi.service.finance.refund.RefundService;
import com.pegasus.pegasustcgapi.service.payment.PaymentService;
import com.pegasus.pegasustcgapi.service.shipping.ShippingOptionService;
import com.pegasus.pegasustcgapi.storage.StorageService;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.ApiClient.Response;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Card;
import com.pegasus.pegasustcgapi.support.TestData.Listing;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checkout at sale prices and the money after it [TCG-680], on the epic's table:
 * a ฿1,000 listing, 5% commission, 20% off.
 *
 * <pre>
 *                     shop's own sale   Pegasus campaign
 *   buyer pays              800               800
 *   commission base         800 → 40        1,000 → 50
 *   shop gets               760               950
 *   SALE_SUBSIDY              0               200
 * </pre>
 *
 * Each test scopes its campaign to a game of its own, so it reprices nothing else.
 */
class SaleCheckoutIntegrationTest extends PostgresIntegrationTest {

    private static final String ADMIN_SALES = "/api/v1/admin/sales";
    private static final String SHOP_SALES = "/api/v1/sellers/me/sales";

    @Autowired CartService cart;
    @Autowired CheckoutService checkout;
    @Autowired ShippingOptionService shipping;
    @Autowired CommissionRuleService rules;
    @Autowired PaymentService payments;
    @Autowired PaymentRepository paymentRows;
    @Autowired OrderLifecycleService orders;
    @Autowired ReturnService returns;
    @Autowired RefundService refunds;
    @Autowired PromotionRepository promotions;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean StorageService storage;

    private ApiClient api;
    private String admin;
    private long adminId;
    private Seller seller;
    private long shippingOption;
    private short game;
    private Card card;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
        admin = data.bearer(data.admin());
        adminId = data.admin().userId();
        seller = data.seller();
        shippingOption = shipping.create(seller.userId(), new OptionFields("Free", "KERRY",
                BigDecimal.ZERO, BigDecimal.ZERO, null, (short) 1, (short) 3, true, (short) 0)).id();
        rules.create(new CommissionRuleRequest("SELLER", null, null, seller.profileId(), money("5"),
                BigDecimal.ZERO, null, null, (short) 0, OffsetDateTime.now(clock).minusDays(1), null, true), adminId);
        game = data.game("Sale checkout");
        card = data.card(game, "Sale checkout card " + data.tag(), "R");
    }

    // ---------- the epic's table ----------

    @Test
    void pegasusCampaignPaysTheShopTheDiscountAtRelease() {
        Listing l = data.onSale(seller, card, CardCondition.NM, 1, "1000");
        long saleId = create(ADMIN_SALES, admin, campaign("peg-" + data.tag(), 20));
        AuthPrincipal buyer = data.buyer();

        CheckoutResponse order = buy(buyer, l, 1, null, null);
        assertThat(order.grandTotal()).isEqualByComparingTo("800");
        OrderItemRecord item = onlyItem(order);
        assertThat(item.getUnitPrice()).isEqualByComparingTo("800");
        assertThat(item.getListPrice()).isEqualByComparingTo("1000");
        assertThat(item.getSaleDiscount()).isEqualByComparingTo("200");
        assertThat(item.getSaleId()).isEqualTo(saleId);
        assertThat(item.getSaleFundedBy()).isEqualTo("PLATFORM");

        SellerOrderDetailsResponse part = part(order);
        assertThat(part.commissionAmount()).isEqualByComparingTo("50");
        assertThat(part.sellerNetAmount()).isEqualByComparingTo("950");
        assertThat(part.platformSubsidy()).isEqualByComparingTo("200");
        assertThat(part.commissionBase()).isEqualByComparingTo("1000");
        OrderItemDetailsResponse line = part.items().getFirst();
        assertThat(line.listPrice()).isEqualByComparingTo("1000");
        assertThat(line.saleDiscount().platform()).isEqualByComparingTo("200");
        assertThat(line.saleDiscount().seller()).isEqualByComparingTo("0");
        assertThat(order.sellerOrders().getFirst().items().getFirst().saleDiscount().platform())
                .isEqualByComparingTo("200");

        payShipAndRelease(buyer, order);
        assertThat(ledger(part.id())).containsExactlyInAnyOrderEntriesOf(Map.of(
                "ESCROW_RELEASE", money("800.00"),
                "COMMISSION_FEE", money("50.00"),
                "SALE_SUBSIDY", money("200.00"),
                "SELLER_EARNING", money("950.00")));
        assertThat(commissionBase(part.id())).isEqualByComparingTo("1000");
        assertLedgerBalances();
    }

    @Test
    void shopSaleIsPaidForByTheShop() {
        Listing l = data.onSale(seller, card, CardCondition.NM, 1, "1000");
        String shop = data.bearer(seller.principal());
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("name", "Shop 20%");
        form.put("scopeType", "SHOP");
        form.put("discountType", "PERCENT");
        form.put("discountValue", 20);
        form.put("startsAt", iso(now().minusHours(1)));
        form.put("endsAt", iso(now().plusDays(1)));
        create(SHOP_SALES, shop, form);
        AuthPrincipal buyer = data.buyer();

        CheckoutResponse order = buy(buyer, l, 1, null, null);
        assertThat(order.grandTotal()).isEqualByComparingTo("800");
        assertThat(onlyItem(order).getSaleFundedBy()).isEqualTo("SELLER");
        SellerOrderDetailsResponse part = part(order);
        assertThat(part.commissionAmount()).isEqualByComparingTo("40");
        assertThat(part.sellerNetAmount()).isEqualByComparingTo("760");
        assertThat(part.items().getFirst().saleDiscount().seller()).isEqualByComparingTo("200");

        payShipAndRelease(buyer, order);
        assertThat(ledger(part.id())).containsExactlyInAnyOrderEntriesOf(Map.of(
                "ESCROW_RELEASE", money("800.00"),
                "COMMISSION_FEE", money("40.00"),
                "SELLER_EARNING", money("760.00")));
        assertLedgerBalances();
    }

    @Test
    void codesStackOnTheSalePriceShopCodeFirst() {
        Listing l = data.onSale(seller, card, CardCondition.NM, 1, "1000");
        create(ADMIN_SALES, admin, campaign("stack-" + data.tag(), 20));
        String shopCode = code("SHOP50");
        String pegasusCode = code("PEG10");
        insertCode(shopCode, OwnerType.SELLER, seller.profileId(), DiscountType.FIXED, "50", null, "800");
        insertCode(pegasusCode, OwnerType.PLATFORM, null, DiscountType.PERCENT, "10", null, "0");
        AuthPrincipal buyer = data.buyer();

        // 800 at the sale price, the shop's 50 off that, then Pegasus's 10% off the 750 left.
        CheckoutResponse order = buy(buyer, l, 1, pegasusCode, shopCode);
        assertThat(order.itemsSubtotal()).isEqualByComparingTo("800");
        assertThat(order.discountTotal()).isEqualByComparingTo("125");
        assertThat(order.grandTotal()).isEqualByComparingTo("675");
        SellerOrderDetailsResponse part = part(order);
        assertThat(part.sellerDiscount()).isEqualByComparingTo("50");
        assertThat(part.platformDiscount()).isEqualByComparingTo("75");
        // Commission on the asking price less the shop's own code: 950 → 47.50.
        assertThat(part.commissionBase()).isEqualByComparingTo("950");
        assertThat(part.commissionAmount()).isEqualByComparingTo("47.50");
        // 675 paid + 75 code + 200 campaign - 47.50.
        assertThat(part.sellerNetAmount()).isEqualByComparingTo("902.50");

        payShipAndRelease(buyer, order);
        assertThat(ledger(part.id())).containsExactlyInAnyOrderEntriesOf(Map.of(
                "ESCROW_RELEASE", money("675.00"),
                "PROMO_SUBSIDY", money("75.00"),
                "SALE_SUBSIDY", money("200.00"),
                "COMMISSION_FEE", money("47.50"),
                "SELLER_EARNING", money("902.50")));
        assertLedgerBalances();
    }

    // ---------- flash quota, limits and budget ----------

    @Test
    void twoBuyersRaceForTheLastFlashCardAndOneWins() throws Exception {
        Listing l = data.onSale(seller, card, CardCondition.NM, 2, "1000");
        create(ADMIN_SALES, admin, flash("race-" + data.tag(), 1, null));
        AuthPrincipal first = data.buyer();
        AuthPrincipal second = data.buyer();
        cart.addItem(first, null, new CartItemRequest(l.id(), 1));
        cart.addItem(second, null, new CartItemRequest(l.id(), 1));

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (AuthPrincipal buyer : List.of(first, second)) {
                Callable<String> attempt = () -> {
                    go.await();
                    try {
                        checkout.checkout(buyer, "race-" + buyer.userId(), null, request(null, null));
                        return "OK";
                    } catch (ApiException e) {
                        return e.errorCode().name();
                    }
                };
                results.add(pool.submit(attempt));
            }
            go.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> f : results) outcomes.add(f.get());
            assertThat(outcomes).containsExactlyInAnyOrder("OK", "SALE_QUOTA_REACHED");
        } finally {
            pool.shutdownNow();
        }
        assertThat(soldCount(l)).isEqualTo(1);
        assertThat(salePriceRow(l)).isNull();
    }

    @Test
    void anUnpaidOrderTimingOutGivesQuotaAndBudgetBack() {
        Listing l = data.onSale(seller, card, CardCondition.NM, 3, "1000");
        long saleId = create(ADMIN_SALES, admin, flash("expire-" + data.tag(), 2, "1000"));
        AuthPrincipal buyer = data.buyer();

        CheckoutResponse order = buy(buyer, l, 1, null, null);
        assertThat(order.grandTotal()).isEqualByComparingTo("700");
        assertThat(soldCount(l)).isEqualTo(1);
        assertThat(subsidyReserved(saleId)).isEqualByComparingTo("300");
        assertThat(salePriceRow(l).getQuotaLeft()).isEqualTo(1);

        orders.expireUnpaidSellerOrder(order.sellerOrders().getFirst().id());
        assertThat(soldCount(l)).isZero();
        assertThat(subsidyReserved(saleId)).isEqualByComparingTo("0");
        assertThat(salePriceRow(l).getQuotaLeft()).isEqualTo(2);
        OrderItemRecord item = onlyItem(order);
        assertThat(item.getSaleQuotaHeld()).isZero();
        assertThat(item.getSaleSubsidyHeld()).isEqualByComparingTo("0");

        // A second roll-up gives nothing back twice.
        new TransactionTemplate(transactions).executeWithoutResult(
                status -> orders.rollupSalesOrderStatus(order.orderId()));
        assertThat(subsidyReserved(saleId)).isEqualByComparingTo("0");
        assertLedgerBalances();
    }

    @Test
    void paidOrdersKeepTheirQuotaWhenCancelledLater() {
        Listing l = data.onSale(seller, card, CardCondition.NM, 2, "1000");
        create(ADMIN_SALES, admin, flash("paid-" + data.tag(), 2, null));
        AuthPrincipal buyer = data.buyer();
        CheckoutResponse order = buy(buyer, l, 1, null, null);
        pay(buyer, order);
        orders.cancelSellerOrderBySeller(seller.principal(), order.sellerOrders().getFirst().id(), "Gone");
        assertThat(soldCount(l)).isEqualTo(1);
    }

    @Test
    void cardsPastTheBuyerLimitSellAtTheAskingPriceOnTheirOwnLine() {
        Listing l = data.onSale(seller, card, CardCondition.NM, 5, "1000");
        create(ADMIN_SALES, admin, flash("limit-" + data.tag(), 5, null, 1));
        AuthPrincipal buyer = data.buyer();

        CheckoutResponse order = buy(buyer, l, 3, null, null);
        assertThat(order.grandTotal()).isEqualByComparingTo("2700");
        List<OrderItemRecord> items = items(order);
        assertThat(items).hasSize(2);
        assertThat(items).anySatisfy(i -> {
            assertThat(i.getQuantity()).isEqualTo(1);
            assertThat(i.getUnitPrice()).isEqualByComparingTo("700");
            assertThat(i.getSaleId()).isNotNull();
        });
        assertThat(items).anySatisfy(i -> {
            assertThat(i.getQuantity()).isEqualTo(2);
            assertThat(i.getUnitPrice()).isEqualByComparingTo("1000");
            assertThat(i.getSaleId()).isNull();
        });
        assertThat(soldCount(l)).isEqualTo(1);

        cart.addItem(buyer, null, new CartItemRequest(l.id(), 1));
        assertThatThrownBy(() -> checkout.checkout(buyer, "limit-again-" + data.tag(), null, request(null, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SALE_BUYER_LIMIT_REACHED));
    }

    @Test
    void aBudgetTooSmallForTheOrderEndsTheCampaignAndShowsTheNewPrice() {
        Listing l = data.onSale(seller, card, CardCondition.NM, 2, "1000");
        Map<String, Object> form = campaign("budget-" + data.tag(), 20);
        form.put("budgetCap", 250);
        long saleId = create(ADMIN_SALES, admin, form);

        AuthPrincipal first = data.buyer();
        buy(first, l, 1, null, null);
        assertThat(subsidyReserved(saleId)).isEqualByComparingTo("200");

        AuthPrincipal second = data.buyer();
        cart.addItem(second, null, new CartItemRequest(l.id(), 1));
        assertThatThrownBy(() -> checkout.checkout(second, "budget-" + data.tag(), null, request(null, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CART_PRICE_CHANGED));

        assertThat(api.get(ADMIN_SALES + "/" + saleId, admin).data().path("status").asString())
                .isEqualTo("BUDGET_EXHAUSTED");
        assertThat(subsidyReserved(saleId)).isEqualByComparingTo("200");
        assertThat(salePriceRow(l)).isNull();
        var item = cart.getCart(second, null).items().getFirst();
        assertThat(item.priceChanged()).isTrue();
        assertThat(item.currentPrice()).isEqualByComparingTo("1000");
    }

    @Test
    void checkoutRefusesAPriceTheCacheStillShowsAfterTheSaleEnded() {
        Listing l = data.onSale(seller, card, CardCondition.NM, 1, "1000");
        long saleId = create(ADMIN_SALES, admin, campaign("stale-" + data.tag(), 20));
        AuthPrincipal buyer = data.buyer();
        cart.addItem(buyer, null, new CartItemRequest(l.id(), 1));
        // Ended behind the cache's back: the row stays until the next refresh.
        dsl.update(PRICE_SALE).set(PRICE_SALE.PAUSED_AT, OffsetDateTime.now(clock))
                .where(PRICE_SALE.ID.eq(saleId)).execute();
        assertThat(salePriceRow(l)).isNotNull();

        assertThatThrownBy(() -> checkout.checkout(buyer, "stale-" + data.tag(), null, request(null, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CART_PRICE_CHANGED));
    }

    // ---------- after-sales ----------

    @Test
    void returningACardOnSaleRefundsWhatWasPaidAndDropsItsSubsidy() {
        Listing l = data.onSale(seller, card, CardCondition.NM, 2, "1000");
        create(ADMIN_SALES, admin, campaign("return-" + data.tag(), 20));
        AuthPrincipal buyer = data.buyer();
        CheckoutResponse order = buy(buyer, l, 2, null, null);
        SellerOrderDetailsResponse part = part(order);
        OrderItemDetailsResponse line = part.items().getFirst();
        assertThat(line.refundEstimatePerUnit()).isEqualByComparingTo("800");

        pay(buyer, order);
        orders.shipSellerOrder(seller.principal(), part.id(),
                new ShipOrderRequest("Kerry Express", "SAL-RET", "KERRY", null, null));
        var request = returns.request(buyer.userId(), part.id(), "DAMAGED", "Bent corner", List.of(),
                List.of(new ReturnService.Item(line.id(), 1)));
        returns.approve(seller.userId(), request.getId(), "PARTIAL_REFUND", null, true);
        returns.ship(buyer.userId(), part.id(), request.getId(), "RETURN-SAL");
        returns.receive(seller.userId(), request.getId(), true);
        long refundId = dsl.select(REFUND.ID).from(REFUND)
                .where(REFUND.RETURN_REQUEST_ID.eq(request.getId())).fetchSingle(REFUND.ID);
        assertThat(dsl.select(REFUND.AMOUNT).from(REFUND).where(REFUND.ID.eq(refundId)).fetchSingle(REFUND.AMOUNT))
                .isEqualByComparingTo("800");
        refunds.setDestination(refundId, buyer.userId(), "Test Bank", "Test Buyer", "1234567890");
        refunds.startTransfer(refundId, adminId, "1234567890");
        refunds.processed(refundId, adminId, "REF-SAL");
        orders.autoCompleteSellerOrder(part.id());

        // One card stays: 800 released, its 200 subsidy, commission on its 1,000.
        assertThat(ledger(part.id())).containsAllEntriesOf(Map.of(
                "ESCROW_RELEASE", money("800.00"),
                "SALE_SUBSIDY", money("200.00"),
                "COMMISSION_FEE", money("50.00"),
                "SELLER_EARNING", money("950.00")));
        assertThat(commissionBase(part.id())).isEqualByComparingTo("1000");
        assertLedgerBalances();
    }

    // ---------- helpers ----------

    private CheckoutResponse buy(AuthPrincipal buyer, Listing l, int qty, String platformCode, String shopCode) {
        cart.addItem(buyer, null, new CartItemRequest(l.id(), qty));
        return checkout.checkout(buyer, "sale-" + data.tag() + "-" + buyer.userId(), null,
                request(platformCode, shopCode));
    }

    private CheckoutRequest request(String platformCode, String shopCode) {
        return new CheckoutRequest(null, Map.of(seller.profileId(), shippingOption), null, null, platformCode,
                shopCode == null ? null : Map.of(seller.profileId(), shopCode), null);
    }

    private SellerOrderDetailsResponse part(CheckoutResponse order) {
        return orders.toSellerOrderDetailsResponse(order.sellerOrders().getFirst().id());
    }

    private List<OrderItemRecord> items(CheckoutResponse order) {
        return dsl.selectFrom(ORDER_ITEM)
                .where(ORDER_ITEM.SELLER_ORDER_ID.eq(order.sellerOrders().getFirst().id()))
                .orderBy(ORDER_ITEM.ID).fetch();
    }

    private OrderItemRecord onlyItem(CheckoutResponse order) {
        List<OrderItemRecord> items = items(order);
        assertThat(items).hasSize(1);
        return items.getFirst();
    }

    private void pay(AuthPrincipal buyer, CheckoutResponse order) {
        var payment = payments.create(buyer, order.orderId(), "BANK_TRANSFER", "sal-pay-" + data.tag());
        paymentRows.attachSlip(payment.id(), "payments/sal-" + data.tag() + ".png", "BANK-SAL-" + data.tag());
        payments.verify(adminId, payment.id(), true, null);
    }

    private void payShipAndRelease(AuthPrincipal buyer, CheckoutResponse order) {
        pay(buyer, order);
        long part = order.sellerOrders().getFirst().id();
        orders.shipSellerOrder(seller.principal(), part, new ShipOrderRequest("Kerry Express", "SAL-" + part,
                "KERRY", null, null));
        orders.autoCompleteSellerOrder(part);
        assertThat(dsl.select(SELLER_ORDER.STATUS).from(SELLER_ORDER).where(SELLER_ORDER.ID.eq(part))
                .fetchSingle(SELLER_ORDER.STATUS)).isEqualTo("COMPLETED");
    }

    private Map<String, BigDecimal> ledger(long sellerOrderId) {
        return dsl.select(LEDGER_ENTRY.ENTRY_TYPE, LEDGER_ENTRY.AMOUNT).from(LEDGER_ENTRY)
                .where(LEDGER_ENTRY.REFERENCE_TYPE.eq("SELLER_ORDER"))
                .and(LEDGER_ENTRY.REFERENCE_ID.eq(sellerOrderId))
                .and(LEDGER_ENTRY.ENTRY_TYPE.in("ESCROW_RELEASE", "PROMO_SUBSIDY", "SALE_SUBSIDY",
                        "COMMISSION_FEE", "SELLER_EARNING"))
                .fetchMap(LEDGER_ENTRY.ENTRY_TYPE, LEDGER_ENTRY.AMOUNT);
    }

    private BigDecimal commissionBase(long sellerOrderId) {
        return dsl.select(COMMISSION_CHARGE.BASE_AMOUNT).from(COMMISSION_CHARGE)
                .where(COMMISSION_CHARGE.SELLER_ORDER_ID.eq(sellerOrderId))
                .fetchSingle(COMMISSION_CHARGE.BASE_AMOUNT);
    }

    private int soldCount(Listing l) {
        Integer n = dsl.select(DSL.sum(PRICE_SALE_LISTING.SOLD_COUNT)).from(PRICE_SALE_LISTING)
                .where(PRICE_SALE_LISTING.LISTING_ID.eq(l.id())).fetchOne(0, Integer.class);
        return n == null ? 0 : n;
    }

    private BigDecimal subsidyReserved(long saleId) {
        return dsl.select(PRICE_SALE.SUBSIDY_RESERVED).from(PRICE_SALE).where(PRICE_SALE.ID.eq(saleId))
                .fetchSingle(PRICE_SALE.SUBSIDY_RESERVED);
    }

    private com.pegasus.pegasustcgapi.jooq.tables.records.ListingSalePriceRecord salePriceRow(Listing l) {
        return dsl.selectFrom(LISTING_SALE_PRICE).where(LISTING_SALE_PRICE.LISTING_ID.eq(l.id())).fetchOne();
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

    private long create(String path, String bearer, Map<String, Object> form) {
        Response r = api.post(path, bearer, form);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
        return r.data().path("id").asLong();
    }

    private Map<String, Object> campaign(String slug, int percent) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("name", percent + "% off");
        form.put("slug", slug);
        form.put("saleType", "CATEGORY_SALE");
        form.put("discountType", "PERCENT");
        form.put("discountValue", percent);
        form.put("scopeType", "GAME");
        form.put("scopeIds", List.of(game));
        form.put("startsAt", iso(now().minusHours(1)));
        form.put("endsAt", iso(now().plusDays(1)));
        return form;
    }

    /** 30% off for two hours. */
    private Map<String, Object> flash(String slug, int quota, String budget) {
        return flash(slug, quota, budget, null);
    }

    private Map<String, Object> flash(String slug, int quota, String budget, Integer perBuyer) {
        Map<String, Object> form = campaign(slug, 30);
        form.put("saleType", "FLASH_SALE");
        form.put("perListingQuota", quota);
        if (perBuyer != null) form.put("perBuyerLimit", perBuyer);
        if (budget != null) form.put("budgetCap", new BigDecimal(budget));
        form.put("endsAt", iso(now().plusHours(2)));
        return form;
    }

    private void insertCode(String code, OwnerType owner, Long shop, DiscountType type, String value, String max,
            String minSpend) {
        promotions.insert(new NewPromotion(code, code, null, owner, shop, type, money(value),
                max == null ? null : money(max), money(minSpend), ScopeType.ALL, List.of(), Visibility.PRIVATE,
                false, null, 1, false, false, null, false, null,
                OffsetDateTime.now(clock).minusDays(1), OffsetDateTime.now(clock).plusDays(30), null));
    }

    private String code(String prefix) {
        return (prefix + data.tag()).toUpperCase(Locale.ROOT);
    }

    private static String iso(OffsetDateTime at) {
        return at.withNano(0).toString();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
