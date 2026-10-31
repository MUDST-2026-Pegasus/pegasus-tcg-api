package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.AuctionViews.AuctionItem;
import com.pegasus.pegasustcgapi.model.AuctionStatus;
import com.pegasus.pegasustcgapi.model.BidStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** What admins read about auctions and barred bidders [TCG-613] (Figma AUC-A1, A2, A3, A4). Money is THB. */
public final class AdminAuctionViews {

    private AdminAuctionViews() {
    }

    /** Tabs on the admin auction list (A1). */
    public enum Tab {
        ALL,
        /** Reported by users or showing a {@link Signal}. */
        FLAGGED,
        LIVE,
        /** AWAITING_PAYMENT, AWAITING_SELLER_DECISION or OFFERED_TO_SECOND. */
        AWAITING_PAYMENT,
        /** SOLD, UNSOLD or CANCELLED. */
        ENDED,
        SUSPENDED
    }

    /** Something worth a look before the auction ends (A1, A3). */
    public enum Signal {
        /** A young account placing many of the bids ("บัญชีใหม่บิดถี่"). */
        NEW_ACCOUNT_RAPID_BIDS,
        /** A bidder ships to the seller's address ("ที่อยู่ตรงผู้ขาย"). */
        SHARED_ADDRESS_WITH_SELLER,
        /** Reported by users. */
        REPORTED
    }

    /**
     * The KPI row (A1).
     *
     * @param suspendedLast30Days auctions suspended in the last 30 days
     */
    public record Kpis(
            long live,
            long endingWithinHour,
            long awaitingPayment,
            long offeredToSecond,
            long suspendedLast30Days) {
    }

    /**
     * One row of the admin auction table (A1).
     *
     * @param deadline    payment, decision or offer deadline, as on the seller's list; else null
     * @param reportCount user reports, shown as "ถูกรายงาน 3"
     * @param suspendedAt set with SUSPENDED
     */
    public record AdminAuctionRow(
            long id,
            String auctionNumber,
            String cardName,
            String shopName,
            String sellerUsername,
            BigDecimal currentPrice,
            int bidCount,
            int bidderCount,
            AuctionStatus status,
            OffsetDateTime endsAt,
            OffsetDateTime deadline,
            List<Signal> signals,
            int reportCount,
            OffsetDateTime suspendedAt) {
    }

    /** @param flaggedCount auctions with a signal, for the banner above the table */
    public record AdminAuctionPage(
            PageResponse<AdminAuctionRow> page,
            Map<Tab, Long> tabCounts,
            Kpis kpis,
            long flaggedCount,
            OffsetDateTime serverTime) {
    }

    /**
     * The shop behind the auction (A3).
     *
     * @param verified        whether the shop passed verification
     * @param auctionCount    every auction the shop has opened
     * @param suspensionCount times an admin suspended one of its auctions
     */
    public record AdminSeller(long sellerProfileId, String shopName, String username, boolean verified,
            long auctionCount, long suspensionCount) {
    }

    /** A signal with the sentence that explains it (A3 "สัญญาณที่ควรตรวจสอบ"). */
    public record SignalDetail(Signal signal, String message) {
    }

    /**
     * One bid with the bidder's full name, for admins only (A3).
     *
     * @param signals what this bid or bidder triggered
     */
    public record AdminBid(
            long bidId,
            long userId,
            String username,
            OffsetDateTime memberSince,
            long orderCount,
            BigDecimal amount,
            OffsetDateTime placedAt,
            BidStatus status,
            List<Signal> signals) {
    }

    /** Something an admin did, kept on the auction and in the activity log. */
    public record AdminAction(String action, String adminName, String reason, OffsetDateTime at) {
    }

    /**
     * One auction for admins (A3).
     *
     * @param sellerNoteUpdatedAt when the note last changed; null when there is none
     * @param bids                newest first, full names
     */
    public record AdminAuctionDetail(
            long id,
            String auctionNumber,
            AuctionStatus status,
            AuctionItem item,
            AdminSeller seller,
            BigDecimal startingPrice,
            BigDecimal bidIncrement,
            BigDecimal currentPrice,
            int bidCount,
            int bidderCount,
            BigDecimal buyNowPrice,
            OffsetDateTime originalEndsAt,
            OffsetDateTime endsAt,
            int extensionCount,
            String sellerNote,
            OffsetDateTime sellerNoteUpdatedAt,
            List<SignalDetail> signals,
            int reportCount,
            List<AdminBid> bids,
            List<AdminAction> actions,
            OffsetDateTime serverTime) {
    }

    /** Tabs on the barred-bidder page (A4). */
    public enum BanTab {
        /** Barred right now. */
        BANNED,
        /** Two strikes; one more bars them ("ใกล้ถูกห้าม"). */
        AT_RISK,
        /** Lifted by an admin or served out. */
        HISTORY
    }

    /** One unpaid win (A2-B). */
    public record Strike(long auctionId, String auctionNumber, OffsetDateTime at) {
    }

    /**
     * A user with unpaid-win strikes (A4).
     *
     * @param maskedEmail  e.g. "k***a@gmail.com"
     * @param strikeCount  strikes that still count; the counter resets once a ban is served
     * @param strikeLimit  strikes that bar bidding (3)
     * @param bannedUntil  set while barred
     * @param liftedAt     set when an admin lifted the ban
     * @param strikes      newest first
     */
    public record BannedBidder(
            long userId,
            String username,
            String maskedEmail,
            OffsetDateTime memberSince,
            int strikeCount,
            int strikeLimit,
            OffsetDateTime bannedUntil,
            OffsetDateTime liftedAt,
            List<Strike> strikes) {
    }

    /**
     * The barred-bidder list (A4).
     *
     * @param liftedLast30Days   bans an admin lifted in the last 30 days
     * @param expiredLast30Days  bans served out in the last 30 days
     */
    public record BannedBidderPage(
            PageResponse<BannedBidder> page,
            Map<BanTab, Long> tabCounts,
            long liftedLast30Days,
            long expiredLast30Days) {
    }
}
