package com.pegasus.pegasustcgapi.controller;

import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingUnit.LISTING_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItem.ORDER_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.OrderItemUnit.ORDER_ITEM_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.jooq.tables.records.ListingRecord;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.ListingUnitStatus;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.ApiClient.Response;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Card;
import com.pegasus.pegasustcgapi.support.TestData.Listing;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;

/**
 * [CRITICAL] Single-card stock race [CR-4 US-17, RQ-9], over real HTTP.
 *
 * <p>Buyers who each have the last card in their basket press "checkout" in the same
 * instant: every request is parked on a {@link CountDownLatch} until all are ready,
 * then released together, so they reach {@code POST /checkout} within the same
 * millisecond. Exactly one may get the card (201); the rest are refused with 409 and
 * the stock never goes below zero.
 *
 * <p>The guard in the code is a pessimistic one — {@code SELECT ... FOR UPDATE SKIP
 * LOCKED} on the cards — rather than optimistic versioning, so a loser is told
 * {@code INSUFFICIENT_STOCK} (it found nothing to lock) or, when it reads the listing
 * after the winner committed, {@code LISTING_NOT_PURCHASABLE} (sold out). Both are 409.
 *
 * <p>Interleavings differ from run to run and machine to machine, so each scenario is
 * a {@link RepeatedTest}: a stress loop that has to pass every time, not once.
 */
@DisplayName("Checkout race condition (integration, HTTP) — last card, many buyers")
class CheckoutRaceConditionIntegrationTest extends PostgresIntegrationTest {

    private static final List<String> SOLD_OUT_CODES =
            List.of(ErrorCode.INSUFFICIENT_STOCK.name(), ErrorCode.LISTING_NOT_PURCHASABLE.name());

    private ApiClient api;
    private Seller seller;
    private Card card;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
        seller = data.seller();
        card = data.card(data.game("Pokemon"), "Charizard ex", "SAR");
    }

    /** A buyer who already has {@code quantity} of the listing in their cart, with a token ready. */
    private record Contender(AuthPrincipal who, String bearer) {
    }

    private List<Contender> contenders(int count, Listing listing) {
        List<Contender> contenders = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            AuthPrincipal buyer = data.buyer();
            String bearer = data.bearer(buyer);
            Response added = api.post(ApiPaths.CART_ITEMS, bearer, Map.of("listingId", listing.id(), "quantity", 1));
            assertThat(added.status()).as("setup: add to cart").isEqualTo(201);
            contenders.add(new Contender(buyer, bearer));
        }
        return contenders;
    }

    /** Fires every request at once and waits for all of them. */
    private List<Response> race(List<Callable<Response>> requests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Response>> futures = new ArrayList<>();
            for (Callable<Response> request : requests) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return request.call();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).as("all threads ready").isTrue();
            go.countDown();

            List<Response> responses = new ArrayList<>();
            for (Future<Response> future : futures) {
                responses.add(future.get(60, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Response> checkout(Contender contender, String idempotencyKey) {
        return () -> api.post(ApiPaths.CHECKOUT, contender.bearer(), null, Map.of("Idempotency-Key", idempotencyKey));
    }

    private ListingRecord listingRow(Listing listing) {
        return dsl.selectFrom(LISTING).where(LISTING.ID.eq(listing.id())).fetchSingle();
    }

    private int unitsIn(Listing listing, ListingUnitStatus status) {
        return dsl.fetchCount(LISTING_UNIT,
                LISTING_UNIT.LISTING_ID.eq(listing.id()).and(LISTING_UNIT.STATUS.eq(status.name())));
    }

    private int ordersOf(List<Contender> contenders) {
        return dsl.fetchCount(SALES_ORDER,
                SALES_ORDER.BUYER_ID.in(contenders.stream().map(c -> c.who().userId()).toList()));
    }

    private int orderLinesOn(Listing listing) {
        return dsl.fetchCount(ORDER_ITEM_UNIT, ORDER_ITEM_UNIT.ORDER_ITEM_ID.in(
                dsl.select(ORDER_ITEM.ID).from(ORDER_ITEM).where(ORDER_ITEM.LISTING_ID.eq(listing.id()))));
    }

    @RepeatedTest(value = 20, name = "round {currentRepetition}/{totalRepetitions}")
    @DisplayName("2 buyers, 1 card, same instant: one 201, one 409, stock never negative")
    void twoBuyersRaceForTheLastCard() throws Exception {
        Listing listing = data.onSale(seller, card, CardCondition.NM, 1, "1500.00");
        List<Contender> buyers = contenders(2, listing);

        List<Response> responses = race(List.of(
                checkout(buyers.get(0), "race-a-" + data.tag()),
                checkout(buyers.get(1), "race-b-" + data.tag())));

        assertThat(responses).extracting(Response::status).as("statuses")
                .containsExactlyInAnyOrder(201, 409);
        Response loser = responses.stream().filter(r -> r.status() == 409).findFirst().orElseThrow();
        assertThat(loser.errorCode()).isIn(SOLD_OUT_CODES);

        ListingRecord row = listingRow(listing);
        assertThat(row.getQuantityAvailable()).as("available").isZero();
        assertThat(row.getQuantityReserved()).as("reserved").isEqualTo(1);
        assertThat(row.getQuantityAvailable()).isNotNegative();
        assertThat(unitsIn(listing, ListingUnitStatus.RESERVED)).isEqualTo(1);
        assertThat(unitsIn(listing, ListingUnitStatus.LISTED)).isZero();
        assertThat(ordersOf(buyers)).as("orders placed").isEqualTo(1);
        assertThat(orderLinesOn(listing)).as("cards promised to buyers").isEqualTo(1);
    }

    @RepeatedTest(value = 5, name = "round {currentRepetition}/{totalRepetitions}")
    @DisplayName("8 buyers, 3 cards: exactly 3 orders, 5 refusals, never oversold")
    void moreBuyersThanCards() throws Exception {
        Listing listing = data.onSale(seller, card, CardCondition.NM, 3, "400.00");
        List<Contender> buyers = contenders(8, listing);

        List<Callable<Response>> requests = new ArrayList<>();
        for (Contender buyer : buyers) {
            requests.add(checkout(buyer, "race-" + data.tag()));
        }
        List<Response> responses = race(requests);

        assertThat(responses).filteredOn(r -> r.status() == 201).hasSize(3);
        assertThat(responses).filteredOn(r -> r.status() == 409).hasSize(5)
                .allSatisfy(r -> assertThat(r.errorCode()).isIn(SOLD_OUT_CODES));

        ListingRecord row = listingRow(listing);
        assertThat(row.getQuantityAvailable()).isZero();
        assertThat(row.getQuantityReserved()).isEqualTo(3);
        assertThat(ordersOf(buyers)).isEqualTo(3);
        assertThat(orderLinesOn(listing)).isEqualTo(3);
    }

    @RepeatedTest(value = 5, name = "round {currentRepetition}/{totalRepetitions}")
    @DisplayName("one buyer double-submits with the same Idempotency-Key: one order, the other call replays it")
    void doubleSubmitWithTheSameKey() throws Exception {
        Listing listing = data.onSale(seller, card, CardCondition.NM, 5, "250.00");
        Contender buyer = contenders(1, listing).getFirst();
        String key = "double-" + data.tag();

        List<Response> responses = race(List.of(checkout(buyer, key), checkout(buyer, key)));

        assertThat(responses).extracting(Response::status).containsExactlyInAnyOrder(201, 200);
        assertThat(responses).extracting(r -> r.data().path("orderId").asLong()).containsOnly(
                responses.getFirst().data().path("orderId").asLong());
        assertThat(ordersOf(List.of(buyer))).isEqualTo(1);
        // The losing request's reservation rolled back with it: one card held, not two.
        assertThat(listingRow(listing).getQuantityReserved()).isEqualTo(1);
    }
}
