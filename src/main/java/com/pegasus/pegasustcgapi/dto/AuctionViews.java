package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.AuctionOfferStatus;
import com.pegasus.pegasustcgapi.model.AuctionStatus;
import com.pegasus.pegasustcgapi.model.AuctionViewerState;
import com.pegasus.pegasustcgapi.model.CardCondition;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * What buyers and guests read about auctions [TCG-613] (Figma AUC-B1, B2, B3, W1).
 *
 * <p>Every response that shows a countdown carries {@code serverTime}, so the browser
 * can correct its own clock before counting down to {@code endsAt} or a deadline.
 * Money is THB.
 */
public final class AuctionViews {

    private AuctionViews() {
    }

    /** Quick filters on the auction list, one per chip (B1). */
    public enum Filter {
        /** Closes within the next hour. */
        ENDING_SOON,
        /** Buy-now is still available (no bids yet and the seller set a price). */
        BUY_NOW,
        /** Nobody has bid yet. */
        NO_BIDS,
        /** Opened within the last 24 hours. */
        NEW
    }

    /** Sort orders on the auction list (B1). */
    public enum Sort {
        ENDING_SOON,
        NEWEST,
        PRICE_ASC,
        PRICE_DESC,
        MOST_BIDS
    }

    /** Tabs on the buyer's "การประมูลของฉัน" page (W1). */
    public enum MyTab {
        ALL,
        /** LEADING or OUTBID. */
        BIDDING,
        /** WON_AWAITING_PAYMENT. */
        TO_PAY,
        /** SECOND_CHANCE. */
        SECOND_CHANCE,
        /** WON. */
        WON,
        /** LOST. */
        LOST
    }

    /**
     * The card being auctioned: one physical card from the seller's stock.
     *
     * @param card       catalogue identity; the attributes and attacks come from the catalogue by {@code card.productSlug}
     * @param gameName   e.g. "Pokémon TCG"
     * @param setName    e.g. "Sword & Shield — Darkness Ablaze"
     * @param cardNumber e.g. "020/189"
     * @param rarity     e.g. "Ultra Rare"; null when the catalogue has none
     */
    public record AuctionItem(
            ListingCard card,
            CardCondition condition,
            String gameName,
            String setName,
            String cardNumber,
            String rarity) {
    }

    /**
     * The shop running the auction.
     *
     * @param rating    average review score, 0–5; null before the first review
     * @param soldCount orders the shop has completed, shown as "1.2k ขาย"
     */
    public record Seller(long sellerProfileId, String shopName, String username, BigDecimal rating, long soldCount) {
    }

    /**
     * One bid as the public sees it. Bidders are masked ("n***t"); only admins see full names.
     *
     * @param bidderInitials two letters for the avatar circle
     * @param mine           whether the caller placed it; always false for a guest
     */
    public record Bid(String bidderAlias, String bidderInitials, boolean mine, BigDecimal amount,
            OffsetDateTime placedAt) {
    }

    /**
     * One auction card on the list page (B1).
     *
     * @param currentPrice the highest bid, or the starting price while {@code bidCount} is 0
     * @param buyNowPrice  null unless buy-now is still available
     */
    public record AuctionSummary(
            long id,
            String auctionNumber,
            AuctionItem item,
            String shopName,
            BigDecimal startingPrice,
            BigDecimal currentPrice,
            int bidCount,
            BigDecimal buyNowPrice,
            OffsetDateTime endsAt) {
    }

    /**
     * The auction list with a count for every chip.
     *
     * @param filterCounts live auctions matching each chip, ignoring the chip that is selected
     * @param totalLive    every live auction, for the "ทั้งหมด" chip
     */
    public record AuctionPage(
            PageResponse<AuctionSummary> page,
            Map<Filter, Long> filterCounts,
            long totalLive,
            OffsetDateTime serverTime) {
    }

    /**
     * The runner-up offer a seller sent the caller (B3-G).
     *
     * @param total amount plus shipping
     */
    public record SecondChanceOffer(
            BigDecimal amount,
            BigDecimal shippingFee,
            BigDecimal total,
            AuctionOfferStatus status,
            OffsetDateTime expiresAt) {
    }

    /**
     * Where the signed-in caller stands on this auction (B3-D, B3-E, B3-F). Null for a guest.
     *
     * @param state          null when the caller has not bid
     * @param myHighestBid   null when the caller has not bid
     * @param ownAuction     true when the caller's shop runs it; bidding then fails with OWN_AUCTION
     * @param blockers       why the caller cannot bid right now, empty when they can:
     *                       EMAIL_NOT_VERIFIED, NO_SHIPPING_ADDRESS, BIDDING_BANNED or OWN_AUCTION
     * @param bannedUntil    set with BIDDING_BANNED
     * @param paymentDueAt   set with WON_AWAITING_PAYMENT
     * @param offer          set with SECOND_CHANCE
     */
    public record Viewer(
            AuctionViewerState state,
            BigDecimal myHighestBid,
            boolean ownAuction,
            List<ErrorCode> blockers,
            OffsetDateTime bannedUntil,
            OffsetDateTime paymentDueAt,
            SecondChanceOffer offer) {
    }

    /**
     * One auction page (B2).
     *
     * @param photoUrls       the seller's own photos of this card, signed; the official art is on {@code item.card}
     * @param sellerNote      shown under the seller's name; null when the seller wrote none
     * @param currentPrice    the highest bid, or the starting price while {@code bidCount} is 0
     * @param minNextBid      the least the next bid may be: the starting price while there are no bids,
     *                        else the highest bid plus {@code bidIncrement}
     * @param buyNowPrice     the price the seller set; null when they set none
     * @param buyNowAvailable true only while LIVE with no bids and a buy-now price
     * @param originalEndsAt  the closing time the seller picked
     * @param endsAt          the closing time now, after any 2-minute extensions
     * @param shippingFee     what the winner pays for delivery, for the "ยอดที่ต้องชำระถ้าชนะ" line
     * @param recentBids      newest first, at most five; the full list is under /bids
     * @param viewer          null for a guest
     */
    public record AuctionDetail(
            long id,
            String auctionNumber,
            AuctionStatus status,
            AuctionItem item,
            List<String> photoUrls,
            Seller seller,
            String sellerNote,
            BigDecimal startingPrice,
            BigDecimal bidIncrement,
            BigDecimal currentPrice,
            BigDecimal minNextBid,
            int bidCount,
            int bidderCount,
            BigDecimal buyNowPrice,
            boolean buyNowAvailable,
            OffsetDateTime startedAt,
            OffsetDateTime originalEndsAt,
            OffsetDateTime endsAt,
            int extensionCount,
            BigDecimal shippingFee,
            List<Bid> recentBids,
            Viewer viewer,
            OffsetDateTime serverTime) {
    }

    /** Every bid on one auction, newest first. */
    public record BidHistory(PageResponse<Bid> page, OffsetDateTime serverTime) {
    }

    /**
     * What changed after the caller's bid was accepted.
     *
     * @param extended true when the bid landed in the last 2 minutes and pushed {@code endsAt} back to 2 minutes away
     */
    public record BidPlaced(
            long auctionId,
            BigDecimal amount,
            boolean leading,
            boolean extended,
            BigDecimal currentPrice,
            BigDecimal minNextBid,
            int bidCount,
            OffsetDateTime endsAt,
            OffsetDateTime serverTime) {
    }

    /**
     * The caller now owns the right to buy the card and must pay within 24 hours
     * through {@code POST /auctions/{id}/checkout}. Returned by buy-now and by accepting a runner-up offer.
     *
     * @param total price plus shipping
     */
    public record AuctionWin(
            long auctionId,
            String auctionNumber,
            BigDecimal price,
            BigDecimal shippingFee,
            BigDecimal total,
            OffsetDateTime paymentDueAt,
            OffsetDateTime serverTime) {
    }

    /**
     * One row on the buyer's "การประมูลของฉัน" page (W1).
     *
     * @param currentPrice the highest bid while live, the closing price once ended
     * @param myHighestBid the caller's best bid; for SECOND_CHANCE this is the offer price
     * @param totalDue     price plus shipping; set with WON_AWAITING_PAYMENT and SECOND_CHANCE
     * @param minNextBid   set while live, for the "บิด ฿X" button
     * @param extended     true while live and at least one extension has happened ("ต่อเวลาอยู่")
     * @param deadline     paymentDueAt with WON_AWAITING_PAYMENT, the offer's expiry with SECOND_CHANCE,
     *                     else null
     * @param orderId      set once the auction checkout placed an order
     */
    public record MyAuction(
            long id,
            String auctionNumber,
            AuctionItem item,
            String shopName,
            int bidCount,
            AuctionViewerState state,
            BigDecimal currentPrice,
            BigDecimal myHighestBid,
            BigDecimal shippingFee,
            BigDecimal totalDue,
            BigDecimal minNextBid,
            boolean extended,
            OffsetDateTime endsAt,
            OffsetDateTime deadline,
            Long orderId) {
    }

    /**
     * The buyer's auctions with tab counts and their unpaid-win record.
     *
     * @param unpaidStrikes wins not paid in time that still count, shown as "ไม่ชำระหลังชนะ 0 / 3 ครั้ง"
     * @param strikeLimit   strikes that bar bidding (3)
     * @param bannedUntil   set while barred from bidding
     */
    public record MyAuctionPage(
            PageResponse<MyAuction> page,
            Map<MyTab, Long> tabCounts,
            int unpaidStrikes,
            int strikeLimit,
            OffsetDateTime bannedUntil,
            OffsetDateTime serverTime) {
    }
}
