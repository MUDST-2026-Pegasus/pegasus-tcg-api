package com.pegasus.pegasustcgapi.service.auction;

import static com.pegasus.pegasustcgapi.jooq.tables.Auction.AUCTION;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingUnit.LISTING_UNIT;
import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.dto.auction.AuctionRequests.AuctionCheckout;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Auction checkout against the real schema: the auctioned card is on no listing. */
class AuctionCheckoutIntegrationTest extends PostgresIntegrationTest {

    @Autowired AuctionCheckoutService checkout;
    @Autowired AuctionPaymentTimeoutService timeout;

    @Test
    void winnerChecksOutTheAuctionedCardAndAnExpiredOrderReturnsItToStock() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Seller seller = data.seller();
        var card = data.card(data.game("Pokemon"), "Auction Checkout " + data.tag(), "SAR");
        var listing = data.onSale(seller, card, CardCondition.NM, 1, "100.00");
        var unit = dsl.selectFrom(LISTING_UNIT).where(LISTING_UNIT.LISTING_ID.eq(listing.id()))
                .limit(1).fetchSingle();
        // Opening an auction takes the card off its listing.
        dsl.update(LISTING_UNIT).set(LISTING_UNIT.STATUS, "ON_AUCTION").set(LISTING_UNIT.LISTING_ID, (Long) null)
                .where(LISTING_UNIT.ID.eq(unit.getId())).execute();
        AuthPrincipal winner = data.buyer();
        long auctionId = dsl.insertInto(AUCTION)
                .set(AUCTION.AUCTION_NUMBER, "AUC-C" + data.tag())
                .set(AUCTION.SELLER_PROFILE_ID, seller.profileId())
                .set(AUCTION.LISTING_UNIT_ID, unit.getId())
                .set(AUCTION.CATALOG_VARIANT_ID, unit.getCatalogVariantId())
                .set(AUCTION.CONDITION_CODE, unit.getConditionCode())
                .set(AUCTION.STARTING_PRICE, new BigDecimal("100.00"))
                .set(AUCTION.BID_INCREMENT, new BigDecimal("10.00"))
                .set(AUCTION.CURRENT_PRICE, new BigDecimal("150.00"))
                .set(AUCTION.STARTED_AT, now.minusDays(2))
                .set(AUCTION.ORIGINAL_ENDS_AT, now.minusHours(1))
                .set(AUCTION.ENDS_AT, now.minusHours(1))
                .set(AUCTION.WINNER_ID, winner.userId())
                .set(AUCTION.WINNING_AMOUNT, new BigDecimal("150.00"))
                .set(AUCTION.CLOSED_AT, now.minusHours(1))
                .set(AUCTION.PAYMENT_DUE_AT, now.plusHours(23))
                .set(AUCTION.STATUS, "AWAITING_PAYMENT")
                .returningResult(AUCTION.ID)
                .fetchSingle(AUCTION.ID);

        var order = checkout.checkout(auctionId, winner, "auction-" + data.tag(),
                new AuctionCheckout(data.address(winner), null, null, null));

        assertThat(dsl.select(LISTING_UNIT.STATUS).from(LISTING_UNIT)
                .where(LISTING_UNIT.ID.eq(unit.getId())).fetchSingle(LISTING_UNIT.STATUS)).isEqualTo("RESERVED");
        assertThat(dsl.select(AUCTION.SALES_ORDER_ID).from(AUCTION).where(AUCTION.ID.eq(auctionId))
                .fetchSingle(AUCTION.SALES_ORDER_ID)).isEqualTo(order.orderId());
        assertThat(dsl.select(SALES_ORDER.SOURCE).from(SALES_ORDER).where(SALES_ORDER.ID.eq(order.orderId()))
                .fetchSingle(SALES_ORDER.SOURCE)).isEqualTo("AUCTION");

        // The winner never pays: the order is cancelled and the card goes back to the seller.
        clock.advance(Duration.ofHours(24));
        assertThat(timeout.expire(auctionId, OffsetDateTime.now(clock))).isTrue();
        var released = dsl.selectFrom(LISTING_UNIT).where(LISTING_UNIT.ID.eq(unit.getId())).fetchSingle();
        assertThat(released.getStatus()).isEqualTo("IN_STOCK");
        assertThat(released.getListingId()).isNull();
        assertThat(dsl.select(AUCTION.STATUS).from(AUCTION).where(AUCTION.ID.eq(auctionId))
                .fetchSingle(AUCTION.STATUS)).isEqualTo("UNSOLD");
    }
}
