package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogCategory.CATALOG_CATEGORY;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.Game.GAME;
import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingUnit.LISTING_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.pegasus.pegasustcgapi.dto.CancelOrderRequest;
import com.pegasus.pegasustcgapi.dto.CartItemRequest;
import com.pegasus.pegasustcgapi.dto.CheckoutResponse;
import com.pegasus.pegasustcgapi.dto.SellerListingResponse;
import com.pegasus.pegasustcgapi.dto.ShipOrderRequest;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.jooq.tables.records.ListingRecord;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.ListingStatus;
import com.pegasus.pegasustcgapi.model.ListingUnitStatus;
import com.pegasus.pegasustcgapi.model.MarketKey;
import com.pegasus.pegasustcgapi.model.PricingMode;
import com.pegasus.pegasustcgapi.model.RoleCode;
import com.pegasus.pegasustcgapi.port.LedgerPort;
import com.pegasus.pegasustcgapi.repository.ListingRepository.Details;
import com.pegasus.pegasustcgapi.repository.ListingRepository.Pricing;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.ListingUnitService.StockIn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
@DisplayName("CheckoutConcurrencyIntegrationTest — High Concurrency, Idempotency & Rollbacks")
class CheckoutConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private DSLContext dsl;

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private CartService cartService;

    @Autowired
    private OrderLifecycleService orderLifecycleService;

    @Autowired
    private ListingService listings;

    @Autowired
    private ListingUnitService units;

    @MockitoSpyBean
    private LedgerPort ledgerPort;

    private long sellerUserId;
    private long sellerProfileId;
    private long variantId;
    private AuthPrincipal sellerPrincipal;

    @BeforeEach
    void setUp() {
        String tag = Long.toString(System.nanoTime(), 36);

        sellerUserId = dsl.insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.EMAIL, "seller_" + tag + "@example.com")
                .set(USER_ACCOUNT.PASSWORD_HASH, "x")
                .set(USER_ACCOUNT.USERNAME, "seller_" + tag)
                .set(USER_ACCOUNT.DISPLAY_NAME, "Seller " + tag)
                .returningResult(USER_ACCOUNT.ID)
                .fetchSingle(USER_ACCOUNT.ID);

        sellerProfileId = dsl.insertInto(SELLER_PROFILE)
                .set(SELLER_PROFILE.USER_ID, sellerUserId)
                .set(SELLER_PROFILE.STATUS, "VERIFIED")
                .set(SELLER_PROFILE.VERIFIED_AT, OffsetDateTime.now())
                .returningResult(SELLER_PROFILE.ID)
                .fetchSingle(SELLER_PROFILE.ID);

        sellerPrincipal = new AuthPrincipal(sellerUserId, "seller_" + tag + "@example.com", "seller_" + tag, Set.of(RoleCode.SELLER));

        Short gameId = dsl.insertInto(GAME)
                .set(GAME.CODE, "G" + tag.toUpperCase())
                .set(GAME.NAME, "Game " + tag)
                .set(GAME.SLUG, "game-" + tag)
                .returningResult(GAME.ID)
                .fetchSingle(GAME.ID);

        Integer categoryId = dsl.insertInto(CATALOG_CATEGORY)
                .set(CATALOG_CATEGORY.GAME_ID, gameId)
                .set(CATALOG_CATEGORY.CODE, "SINGLES")
                .set(CATALOG_CATEGORY.NAME, "Singles")
                .set(CATALOG_CATEGORY.SLUG, "singles-" + tag)
                .returningResult(CATALOG_CATEGORY.ID)
                .fetchSingle(CATALOG_CATEGORY.ID);

        Long productId = dsl.insertInto(CATALOG_PRODUCT)
                .set(CATALOG_PRODUCT.GAME_ID, gameId)
                .set(CATALOG_PRODUCT.CATEGORY_ID, categoryId)
                .set(CATALOG_PRODUCT.NAME, "Charizard ex")
                .set(CATALOG_PRODUCT.SLUG, "charizard-ex-" + tag)
                .returningResult(CATALOG_PRODUCT.ID)
                .fetchSingle(CATALOG_PRODUCT.ID);

        variantId = dsl.insertInto(CATALOG_VARIANT)
                .set(CATALOG_VARIANT.CATALOG_PRODUCT_ID, productId)
                .set(CATALOG_VARIANT.SKU, "CHR-" + tag)
                .set(CATALOG_VARIANT.LANGUAGE_CODE, "EN")
                .set(CATALOG_VARIANT.FINISH, "FOIL")
                .returningResult(CATALOG_VARIANT.ID)
                .fetchSingle(CATALOG_VARIANT.ID);
    }

    @AfterEach
    void tearDown() {
        reset(ledgerPort);
    }

    // ---------- Helpers ----------

    private MarketKey market() {
        return new MarketKey(variantId, CardCondition.NM);
    }

    private static Details noDetails() {
        return new Details(null, null, null, null);
    }

    private long onSale(int cards, String price) {
        SellerListingResponse created = listings.create(sellerUserId, market(), noDetails(),
                new Pricing(PricingMode.MANUAL, new BigDecimal(price), null, null, null),
                List.of());
        units.stockIn(sellerUserId, new StockIn(null, null, created.id(), cards, new BigDecimal("100.00"), null, null, null));
        listings.changeStatus(sellerUserId, created.id(), ListingStatus.ACTIVE);
        return created.id();
    }

    private AuthPrincipal createBuyer(String tag, int index) {
        long buyerId = dsl.insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.EMAIL, "buyer_" + tag + "_" + index + "@example.com")
                .set(USER_ACCOUNT.PASSWORD_HASH, "x")
                .set(USER_ACCOUNT.USERNAME, "buyer_" + tag + "_" + index)
                .set(USER_ACCOUNT.DISPLAY_NAME, "Buyer " + index)
                .returningResult(USER_ACCOUNT.ID)
                .fetchSingle(USER_ACCOUNT.ID);

        return new AuthPrincipal(buyerId, "buyer_" + tag + "_" + index + "@example.com", "buyer_" + tag + "_" + index, Set.of(RoleCode.BUYER));
    }

    private void assertListingCountsMatchUnits(long listingId) {
        ListingRecord listing = dsl.selectFrom(LISTING).where(LISTING.ID.eq(listingId)).fetchSingle();
        int listed = dsl.fetchCount(LISTING_UNIT, LISTING_UNIT.LISTING_ID.eq(listingId)
                .and(LISTING_UNIT.STATUS.eq(ListingUnitStatus.LISTED.name())));
        int reserved = dsl.fetchCount(LISTING_UNIT, LISTING_UNIT.LISTING_ID.eq(listingId)
                .and(LISTING_UNIT.STATUS.eq(ListingUnitStatus.RESERVED.name())));

        assertThat(listing.getQuantityAvailable()).as("quantity_available").isEqualTo(listed);
        assertThat(listing.getQuantityReserved()).as("quantity_reserved").isEqualTo(reserved);
        assertThat(listing.getQuantityTotal()).as("quantity_total").isEqualTo(listed + reserved);
        assertThat(listing.getQuantityAvailable()).isGreaterThanOrEqualTo(0);
        assertThat(listing.getQuantityReserved()).isGreaterThanOrEqualTo(0);
    }

    // ---------- Tests ----------

    @Test
    @DisplayName("Regression #3: 20 concurrent threads, 1 card — exactly 1 succeeds, 19 fail predictably, no negative stock")
    void twentyConcurrentBuyers_OneCard_ZeroNegativeStock() throws Exception {
        long listingId = onSale(1, "999.00");
        String tag = Long.toString(System.nanoTime(), 36);

        int buyerCount = 20;
        List<AuthPrincipal> buyers = new ArrayList<>();
        List<Long> buyerIds = new ArrayList<>();

        for (int i = 0; i < buyerCount; i++) {
            AuthPrincipal buyer = createBuyer(tag, i);
            buyers.add(buyer);
            buyerIds.add(buyer.userId());
            cartService.addItem(buyer, null, new CartItemRequest(listingId, 1));
        }

        ExecutorService pool = Executors.newFixedThreadPool(buyerCount);
        CountDownLatch ready = new CountDownLatch(buyerCount);
        CountDownLatch start = new CountDownLatch(1);

        AtomicInteger successes = new AtomicInteger();
        AtomicInteger outOfStockFailures = new AtomicInteger();
        AtomicInteger otherFailures = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < buyerCount; i++) {
            final AuthPrincipal buyer = buyers.get(i);
            final String idempotencyKey = "key-" + tag + "-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    checkoutService.checkout(buyer, idempotencyKey, null, null);
                    successes.incrementAndGet();
                } catch (ConflictException e) {
                    if (e.errorCode() == ErrorCode.INSUFFICIENT_STOCK) {
                        outOfStockFailures.incrementAndGet();
                    } else {
                        otherFailures.incrementAndGet();
                    }
                } catch (Exception e) {
                    otherFailures.incrementAndGet();
                }
                return null;
            }));
        }

        ready.await();
        start.countDown();

        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // 1. Concurrency assertions
        assertThat(successes.get()).as("Exactly 1 buyer gets the card").isEqualTo(1);
        assertThat(outOfStockFailures.get()).as("Exactly 19 buyers fail with INSUFFICIENT_STOCK").isEqualTo(19);
        assertThat(otherFailures.get()).as("Zero other errors or 500s").isZero();

        // 2. Listing and physical unit stock assertions
        ListingRecord listing = dsl.selectFrom(LISTING).where(LISTING.ID.eq(listingId)).fetchSingle();
        assertThat(listing.getQuantityAvailable()).isZero();
        assertThat(listing.getQuantityReserved()).isEqualTo(1);
        assertThat(listing.getQuantityTotal()).isEqualTo(1);
        assertThat(listing.getStatus()).isEqualTo(ListingStatus.SOLD_OUT.name());

        int reservedUnits = dsl.fetchCount(LISTING_UNIT, LISTING_UNIT.LISTING_ID.eq(listingId)
                .and(LISTING_UNIT.STATUS.eq(ListingUnitStatus.RESERVED.name())));
        assertThat(reservedUnits).isEqualTo(1);

        int listedUnits = dsl.fetchCount(LISTING_UNIT, LISTING_UNIT.LISTING_ID.eq(listingId)
                .and(LISTING_UNIT.STATUS.eq(ListingUnitStatus.LISTED.name())));
        assertThat(listedUnits).isZero();

        // 3. Database order records: exactly 1 sales_order and 1 seller_order created, no orphaned rows
        int salesOrdersCreated = dsl.fetchCount(SALES_ORDER, SALES_ORDER.BUYER_ID.in(buyerIds));
        assertThat(salesOrdersCreated).isEqualTo(1);

        int sellerOrdersCreated = dsl.fetchCount(SELLER_ORDER, SELLER_ORDER.SELLER_PROFILE_ID.eq(sellerProfileId));
        assertThat(sellerOrdersCreated).isEqualTo(1);

        assertListingCountsMatchUnits(listingId);
    }

    @Test
    @DisplayName("Idempotency Under High Concurrency: 2 concurrent requests with same key resolve gracefully")
    void concurrentCheckout_SameIdempotencyKey_ResolvesGracefullyWithout500() throws Exception {
        long listingId = onSale(5, "850.00");
        String tag = Long.toString(System.nanoTime(), 36);
        AuthPrincipal buyer = createBuyer(tag, 1);
        cartService.addItem(buyer, null, new CartItemRequest(listingId, 1));

        String key = "idemp-key-" + tag;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<CheckoutResponse>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return checkoutService.checkout(buyer, key, null, null);
            }));
        }

        ready.await();
        start.countDown();

        CheckoutResponse r1 = futures.get(0).get(30, TimeUnit.SECONDS);
        CheckoutResponse r2 = futures.get(1).get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(r1).isNotNull();
        assertThat(r2).isNotNull();
        assertThat(r1.orderId()).isEqualTo(r2.orderId());

        // Exactly 1 sales_order row exists in DB
        int orderCount = dsl.fetchCount(SALES_ORDER, SALES_ORDER.IDEMPOTENCY_KEY.eq(key));
        assertThat(orderCount).isEqualTo(1);

        // One of them is replayed or both point to the same order
        assertThat(r1.replayed() || r2.replayed()).isTrue();
        assertListingCountsMatchUnits(listingId);
    }

    @Test
    @DisplayName("Atomic Rollback: Exception during checkout rolls back all DB changes completely")
    void midTransactionException_RollsBackAllChangesCompletely() {
        long listingId = onSale(1, "1500.00");
        String tag = Long.toString(System.nanoTime(), 36);
        AuthPrincipal buyer = createBuyer(tag, 1);
        cartService.addItem(buyer, null, new CartItemRequest(listingId, 1));

        String key = "rollback-key-" + tag;

        // Stub ledgerPort.quoteCommission to throw mid-transaction exception
        doThrow(new RuntimeException("Simulated mid-transaction failure in commission service"))
                .when(ledgerPort).quoteCommission(any());

        assertThatThrownBy(() -> checkoutService.checkout(buyer, key, null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated mid-transaction failure");

        // 1. Zero sales_orders or seller_orders created
        int salesOrderCount = dsl.fetchCount(SALES_ORDER, SALES_ORDER.IDEMPOTENCY_KEY.eq(key));
        assertThat(salesOrderCount).isZero();

        int sellerOrderCount = dsl.fetchCount(SELLER_ORDER, SELLER_ORDER.SELLER_PROFILE_ID.eq(sellerProfileId));
        assertThat(sellerOrderCount).isZero();

        // 2. Physical unit status rolled back / remains LISTED (no dangling RESERVED locks)
        int listedUnits = dsl.fetchCount(LISTING_UNIT, LISTING_UNIT.LISTING_ID.eq(listingId)
                .and(LISTING_UNIT.STATUS.eq(ListingUnitStatus.LISTED.name())));
        assertThat(listedUnits).isEqualTo(1);

        int reservedUnits = dsl.fetchCount(LISTING_UNIT, LISTING_UNIT.LISTING_ID.eq(listingId)
                .and(LISTING_UNIT.STATUS.eq(ListingUnitStatus.RESERVED.name())));
        assertThat(reservedUnits).isZero();

        // 3. Denormalized listing counts remain intact
        assertListingCountsMatchUnits(listingId);
    }

    @Test
    @DisplayName("Regression #2: Quantity consistency across operations (Checkout -> Cancel -> Ship)")
    void quantityConsistency_CheckoutCancelShip_MirrorsPhysicalUnitCounts() {
        // Start with 2 units on sale
        long listingId = onSale(2, "600.00");
        String tag = Long.toString(System.nanoTime(), 36);
        AuthPrincipal buyer1 = createBuyer(tag, 1);
        AuthPrincipal buyer2 = createBuyer(tag, 2);

        assertListingCountsMatchUnits(listingId);
        ListingRecord initialListing = dsl.selectFrom(LISTING).where(LISTING.ID.eq(listingId)).fetchSingle();
        assertThat(initialListing.getQuantityAvailable()).isEqualTo(2);
        assertThat(initialListing.getQuantityReserved()).isZero();
        assertThat(initialListing.getQuantityTotal()).isEqualTo(2);

        // Step 1: Buyer 1 checks out 1 card
        cartService.addItem(buyer1, null, new CartItemRequest(listingId, 1));
        CheckoutResponse checkout1 = checkoutService.checkout(buyer1, "key-c1-" + tag, null, null);

        assertListingCountsMatchUnits(listingId);
        ListingRecord afterCheckout1 = dsl.selectFrom(LISTING).where(LISTING.ID.eq(listingId)).fetchSingle();
        assertThat(afterCheckout1.getQuantityAvailable()).isEqualTo(1);
        assertThat(afterCheckout1.getQuantityReserved()).isEqualTo(1);
        assertThat(afterCheckout1.getQuantityTotal()).isEqualTo(2);

        // Step 2: Buyer 1 cancels the order -> card released back to LISTED
        orderLifecycleService.cancelBuyerOrder(buyer1, checkout1.orderId(), new CancelOrderRequest("Changed mind"));

        assertListingCountsMatchUnits(listingId);
        ListingRecord afterCancel = dsl.selectFrom(LISTING).where(LISTING.ID.eq(listingId)).fetchSingle();
        assertThat(afterCancel.getQuantityAvailable()).isEqualTo(2);
        assertThat(afterCancel.getQuantityReserved()).isZero();
        assertThat(afterCancel.getQuantityTotal()).isEqualTo(2);

        // Step 3: Buyer 2 checks out 1 card
        cartService.addItem(buyer2, null, new CartItemRequest(listingId, 1));
        CheckoutResponse checkout2 = checkoutService.checkout(buyer2, "key-c2-" + tag, null, null);

        assertListingCountsMatchUnits(listingId);
        ListingRecord afterCheckout2 = dsl.selectFrom(LISTING).where(LISTING.ID.eq(listingId)).fetchSingle();
        assertThat(afterCheckout2.getQuantityAvailable()).isEqualTo(1);
        assertThat(afterCheckout2.getQuantityReserved()).isEqualTo(1);
        assertThat(afterCheckout2.getQuantityTotal()).isEqualTo(2);

        // Step 4: Buyer 2 pays, Seller ships -> card committed to SOLD
        orderLifecycleService.markOrderPaid(buyer2, checkout2.orderId());

        long sellerOrderId = checkout2.sellerOrders().get(0).id();
        ShipOrderRequest shipRequest = new ShipOrderRequest("Kerry Express", "KE12345678", "KERRY", "proof/s.jpg", LocalDate.now().plusDays(2));
        orderLifecycleService.shipSellerOrder(sellerPrincipal, sellerOrderId, shipRequest);

        // After shipping: unit is SOLD, available=1, reserved=0, total=1
        assertListingCountsMatchUnits(listingId);
        ListingRecord afterShip = dsl.selectFrom(LISTING).where(LISTING.ID.eq(listingId)).fetchSingle();
        assertThat(afterShip.getQuantityAvailable()).isEqualTo(1);
        assertThat(afterShip.getQuantityReserved()).isZero();
        assertThat(afterShip.getQuantityTotal()).isEqualTo(1);

        int soldUnits = dsl.fetchCount(LISTING_UNIT, LISTING_UNIT.LISTING_ID.eq(listingId)
                .and(LISTING_UNIT.STATUS.eq(ListingUnitStatus.SOLD.name())));
        assertThat(soldUnits).isEqualTo(1);
    }
}
