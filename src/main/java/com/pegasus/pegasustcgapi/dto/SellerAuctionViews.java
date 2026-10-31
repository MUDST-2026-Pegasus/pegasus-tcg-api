package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.AuctionViews.AuctionItem;
import com.pegasus.pegasustcgapi.dto.AuctionViews.Bid;
import com.pegasus.pegasustcgapi.model.AuctionOfferStatus;
import com.pegasus.pegasustcgapi.model.AuctionStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What a seller reads about their own auctions [TCG-613] (Figma AUC-S1, S2a, S2, S3, S4, S4b). Money is THB. */
public final class SellerAuctionViews {

    private SellerAuctionViews() {
    }

    /**
     * The KPI row on the seller's auction list (S1).
     *
     * @param liveWithBids          live auctions that already have a bid
     * @param nextPaymentDueAt      the soonest payment deadline among AWAITING_PAYMENT; null when none
     * @param soldLast30Days        what auctions sold for in the last 30 days
     * @param closedLast30Days      auctions that ended in the last 30 days, sold or not ("จาก 6 รายการ")
     * @param closeVsMarketPercent  average closing price against the market price, as a signed percent;
     *                              null without enough sales
     */
    public record Kpis(
            long liveCount,
            long liveWithBids,
            long awaitingPaymentCount,
            OffsetDateTime nextPaymentDueAt,
            BigDecimal soldLast30Days,
            long closedLast30Days,
            BigDecimal closeVsMarketPercent) {
    }

    /**
     * One row of the seller's auction table (S1).
     *
     * @param unitLabel       the stock card's short code, e.g. "U-0771"
     * @param currentPrice    the highest bid, or the starting price with no bids
     * @param buyNowPrice     null when the seller set none
     * @param buyNowAvailable true only while LIVE with no bids
     * @param deadline        payment due (AWAITING_PAYMENT), the seller's choice due (AWAITING_SELLER_DECISION)
     *                        or the runner-up's answer due (OFFERED_TO_SECOND); else null
     * @param orderId         set once an order exists, for "ดูคำสั่งซื้อ"
     */
    public record SellerAuctionRow(
            long id,
            String auctionNumber,
            AuctionItem item,
            String unitLabel,
            BigDecimal startingPrice,
            BigDecimal currentPrice,
            int bidCount,
            BigDecimal buyNowPrice,
            boolean buyNowAvailable,
            AuctionStatus status,
            OffsetDateTime endsAt,
            OffsetDateTime deadline,
            Long orderId) {
    }

    /** @param statusCounts every status, for the tabs */
    public record SellerAuctionPage(
            PageResponse<SellerAuctionRow> page,
            Map<AuctionStatus, Long> statusCounts,
            Kpis kpis,
            OffsetDateTime serverTime) {
    }

    /** Whether a stock card can go up for auction (S2a). */
    public enum UnitAvailability {
        /** In hand and on no listing. */
        AVAILABLE,
        /** On a live listing; opening an auction takes it off the listing. Still selectable. */
        LISTED,
        /** Already in a live or unfinished auction ({@code heldBy} names it). */
        IN_AUCTION,
        /** Held by an order ({@code heldBy} names it). */
        RESERVED
    }

    /**
     * What the market charges for this printing and condition, to help set prices (S2).
     *
     * @param listingCount live listings it is based on
     */
    public record MarketGuide(int listingCount, BigDecimal min, BigDecimal median, BigDecimal max) {
    }

    /**
     * A stock card shown in the "เลือกการ์ดจากสต็อก" step (S2a). Cards that are not
     * selectable are still listed, with the reason.
     *
     * @param unitCost   what the seller paid; null when not recorded
     * @param marketGuide null when nothing comparable is on sale
     * @param heldBy     "#AUC-2026-0151" or "#ORD-10377" with IN_AUCTION or RESERVED; else null
     */
    public record EligibleUnit(
            UUID unitUid,
            String unitLabel,
            AuctionItem item,
            BigDecimal unitCost,
            MarketGuide marketGuide,
            UnitAvailability availability,
            String heldBy) {
    }

    /** @param totalInStock every card in hand, for "แสดง 5 จาก 37 ใบในสต็อก" */
    public record EligibleUnitPage(PageResponse<EligibleUnit> page, long totalInStock) {
    }

    /**
     * The runner-up, while the seller decides (S4b).
     *
     * @param estimatedPayout what the seller gets if the runner-up buys, after commission
     */
    public record SecondBidder(String bidderAlias, BigDecimal amount, BigDecimal estimatedPayout) {
    }

    /**
     * The winner did not pay and the seller has to choose (S3-H, S4b). Not choosing by
     * {@code decideBy} puts the card back in stock.
     *
     * @param cancelledOrderNumber the winner's cancelled order, e.g. "ORD-10351"
     * @param secondBidder         null when only one person bid; OFFER_TO_SECOND is then disabled
     */
    public record PendingDecision(
            String winnerAlias,
            BigDecimal winningBid,
            String cancelledOrderNumber,
            OffsetDateTime decideBy,
            SecondBidder secondBidder) {
    }

    /** The offer sent to the runner-up (S3-C, S3-D). */
    public record SentOffer(String bidderAlias, BigDecimal amount, AuctionOfferStatus status,
            OffsetDateTime expiresAt) {
    }

    /**
     * One auction from the seller's side (S4, S4b).
     *
     * @param estimatedPayout   the current price after commission
     * @param commissionPercent the same rate as an ordinary sale, e.g. 5
     * @param editable          whether buy-now and the note may still change (LIVE with no bids)
     * @param cancellable       same rule as {@code editable}
     * @param firstBidAt        when buy-now disappeared; null with no bids
     * @param winnerAlias       set once the auction ended with a winner
     * @param paymentDueAt      set with AWAITING_PAYMENT
     * @param decision          set with AWAITING_SELLER_DECISION
     * @param offer             set once an offer was sent to the runner-up
     * @param bids              newest first, bidders masked
     */
    public record SellerAuctionDetail(
            long id,
            String auctionNumber,
            AuctionStatus status,
            AuctionItem item,
            String unitLabel,
            BigDecimal startingPrice,
            BigDecimal bidIncrement,
            BigDecimal currentPrice,
            int bidCount,
            int bidderCount,
            BigDecimal estimatedPayout,
            BigDecimal commissionPercent,
            BigDecimal buyNowPrice,
            boolean buyNowAvailable,
            String sellerNote,
            boolean editable,
            boolean cancellable,
            OffsetDateTime startedAt,
            OffsetDateTime originalEndsAt,
            OffsetDateTime endsAt,
            int extensionCount,
            OffsetDateTime firstBidAt,
            String winnerAlias,
            OffsetDateTime paymentDueAt,
            PendingDecision decision,
            SentOffer offer,
            Long orderId,
            List<Bid> bids,
            OffsetDateTime serverTime) {
    }
}
