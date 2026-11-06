package com.pegasus.pegasustcgapi.service.auction;

import static com.pegasus.pegasustcgapi.jooq.tables.Auction.AUCTION;
import static com.pegasus.pegasustcgapi.jooq.tables.BidderStrike.BIDDER_STRIKE;
import static com.pegasus.pegasustcgapi.jooq.tables.BiddingBan.BIDDING_BAN;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingUnit.LISTING_UNIT;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.dto.auction.AdminAuctionViews.BanTab;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Strike counting must agree between bidding enforcement and the admin banned-bidder page. */
class AuctionPenaltyIntegrationTest extends PostgresIntegrationTest {

    @Autowired AuctionRepository auctions;
    @Autowired AdminAuctionReadService adminReads;

    private Seller seller;
    private long unitId;
    private OffsetDateTime now;
    private AuthPrincipal admin;

    @BeforeEach
    void stock() {
        // Postgres keeps microseconds; compare at that precision.
        now = OffsetDateTime.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        admin = data.admin();
        seller = data.seller();
        var card = data.card(data.game("Pokemon"), "Penalty " + data.tag(), "SAR");
        var listing = data.onSale(seller, card, CardCondition.NM, 1, "100.00");
        unitId = dsl.select(LISTING_UNIT.ID).from(LISTING_UNIT)
                .where(LISTING_UNIT.LISTING_ID.eq(listing.id())).limit(1).fetchSingle(LISTING_UNIT.ID);
    }

    @Test
    void strikesAfterALiftedBanStillCountOnceItsOriginalEndPasses() {
        AuthPrincipal buyer = data.buyer();
        // Ban would have run to yesterday, but an admin lifted it five days ago and cleared old strikes.
        ban(buyer, now.minusDays(10), now.minusDays(1), now.minusDays(5));
        strike(buyer, now.minusDays(8), now.minusDays(5));
        strike(buyer, now.minusDays(3), null);
        strike(buyer, now.minusDays(2), null);

        assertThat(auctions.countActiveStrikes(buyer.userId())).isEqualTo(2);
        assertThat(adminReads.bannedBidder(buyer.userId()).strikeCount()).isEqualTo(2);
    }

    @Test
    void servedBanResetsTheCounterForBiddingAndForTheAdminPage() {
        AuthPrincipal buyer = data.buyer();
        strike(buyer, now.minusDays(43), null);
        strike(buyer, now.minusDays(42), null);
        strike(buyer, now.minusDays(41), null);
        ban(buyer, now.minusDays(40), now.minusDays(10), null);

        assertThat(auctions.countActiveStrikes(buyer.userId())).isZero();
        var bidder = adminReads.bannedBidder(buyer.userId());
        assertThat(bidder.strikeCount()).isZero();
        assertThat(bidder.bannedUntil()).isNull();
        assertThat(bidder.strikes()).hasSize(3);

        // Before the fix this user had 3 strikes, no ban, and fell out of every tab.
        assertThat(idsIn(BanTab.HISTORY, buyer)).contains(buyer.userId());
        assertThat(idsIn(BanTab.BANNED, buyer)).doesNotContain(buyer.userId());
        assertThat(idsIn(BanTab.AT_RISK, buyer)).doesNotContain(buyer.userId());
    }

    @Test
    void bannedAndAtRiskUsersLandInTheirTabsWithTheirStrikes() {
        AuthPrincipal banned = data.buyer();
        strike(banned, now.minusDays(3), null);
        strike(banned, now.minusDays(2), null);
        strike(banned, now.minusDays(1), null);
        ban(banned, now.minusDays(1), now.plusDays(29), null);
        AuthPrincipal atRisk = data.buyer();
        strike(atRisk, now.minusDays(1), null);

        var bannedPage = adminReads.bannedBidders(BanTab.BANNED, banned.username(), 0, 20);
        assertThat(bannedPage.page().items()).filteredOn(b -> b.userId() == banned.userId())
                .singleElement().satisfies(b -> {
            assertThat(b.strikeCount()).isEqualTo(3);
            assertThat(b.bannedUntil()).isEqualTo(now.plusDays(29));
            assertThat(b.strikes()).hasSize(3);
        });
        var riskPage = adminReads.bannedBidders(BanTab.AT_RISK, atRisk.username(), 0, 20);
        assertThat(riskPage.page().items()).filteredOn(b -> b.userId() == atRisk.userId())
                .singleElement().satisfies(b -> assertThat(b.strikeCount()).isEqualTo(1));
        assertThat(idsIn(BanTab.AT_RISK, banned)).doesNotContain(banned.userId());
    }

    private java.util.List<Long> idsIn(BanTab tab, AuthPrincipal user) {
        return adminReads.bannedBidders(tab, user.username(), 0, 100).page().items().stream()
                .map(b -> b.userId()).toList();
    }

    private void ban(AuthPrincipal user, OffsetDateTime from, OffsetDateTime until, OffsetDateTime liftedAt) {
        dsl.insertInto(BIDDING_BAN)
                .set(BIDDING_BAN.USER_ID, user.userId())
                .set(BIDDING_BAN.BANNED_FROM, from)
                .set(BIDDING_BAN.BANNED_UNTIL, until)
                .set(BIDDING_BAN.LIFTED_AT, liftedAt)
                .set(BIDDING_BAN.LIFTED_BY, liftedAt == null ? null : admin.userId())
                .set(BIDDING_BAN.CREATED_AT, from)
                .execute();
    }

    private void strike(AuthPrincipal user, OffsetDateTime at, OffsetDateTime clearedAt) {
        dsl.insertInto(BIDDER_STRIKE)
                .set(BIDDER_STRIKE.USER_ID, user.userId())
                .set(BIDDER_STRIKE.AUCTION_ID, unsoldAuction(user, at))
                .set(BIDDER_STRIKE.STRUCK_AT, at)
                .set(BIDDER_STRIKE.CLEARED_AT, clearedAt)
                .set(BIDDER_STRIKE.CLEARED_BY, clearedAt == null ? null : admin.userId())
                .execute();
    }

    /** A closed auction the user won and did not pay for. */
    private long unsoldAuction(AuthPrincipal winner, OffsetDateTime struckAt) {
        var unit = dsl.selectFrom(LISTING_UNIT).where(LISTING_UNIT.ID.eq(unitId)).fetchSingle();
        OffsetDateTime closed = struckAt.minusDays(1);
        return dsl.insertInto(AUCTION)
                .set(AUCTION.AUCTION_NUMBER, "AUC-T" + data.tag())
                .set(AUCTION.SELLER_PROFILE_ID, seller.profileId())
                .set(AUCTION.LISTING_UNIT_ID, unitId)
                .set(AUCTION.CATALOG_VARIANT_ID, unit.getCatalogVariantId())
                .set(AUCTION.CONDITION_CODE, unit.getConditionCode())
                .set(AUCTION.STARTING_PRICE, new BigDecimal("100.00"))
                .set(AUCTION.BID_INCREMENT, new BigDecimal("10.00"))
                .set(AUCTION.CURRENT_PRICE, new BigDecimal("100.00"))
                .set(AUCTION.STARTED_AT, closed.minusDays(1))
                .set(AUCTION.ORIGINAL_ENDS_AT, closed)
                .set(AUCTION.ENDS_AT, closed)
                .set(AUCTION.WINNER_ID, winner.userId())
                .set(AUCTION.WINNING_AMOUNT, new BigDecimal("100.00"))
                .set(AUCTION.CLOSED_AT, closed)
                .set(AUCTION.STATUS, "UNSOLD")
                .returningResult(AUCTION.ID)
                .fetchSingle(AUCTION.ID);
    }
}
