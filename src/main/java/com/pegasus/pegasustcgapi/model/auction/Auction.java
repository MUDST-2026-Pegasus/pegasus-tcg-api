package com.pegasus.pegasustcgapi.model.auction;

import com.pegasus.pegasustcgapi.model.catalog.CardCondition;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One row of {@code auction}: a card from a seller's stock up for auction [TCG-614].
 *
 * @param currentPrice    the leading bid, or {@code startingPrice} while {@code bidCount} is 0
 * @param buyNowPrice     null when the seller set none; usable only while LIVE with no bids
 * @param leadingBidId    null while there are no bids
 * @param originalEndsAt  the closing time the seller picked
 * @param endsAt          the closing time now, after any 2-minute extensions
 * @param winnerId        whoever must pay now: the top bidder or a buy-now buyer
 * @param salesOrderId    the order placed by the auction checkout; null until then
 * @param relistedFromId  the auction this one was reopened from
 * @param version         bumped on every change
 */
public record Auction(
        long id,
        String auctionNumber,
        long sellerProfileId,
        long listingUnitId,
        long catalogVariantId,
        CardCondition condition,
        BigDecimal startingPrice,
        BigDecimal bidIncrement,
        BigDecimal buyNowPrice,
        BigDecimal currentPrice,
        Long leadingBidId,
        int bidCount,
        int bidderCount,
        OffsetDateTime startedAt,
        OffsetDateTime originalEndsAt,
        OffsetDateTime endsAt,
        int extensionCount,
        OffsetDateTime firstBidAt,
        String sellerNote,
        OffsetDateTime sellerNoteUpdatedAt,
        Long winnerId,
        BigDecimal winningAmount,
        OffsetDateTime closedAt,
        OffsetDateTime paymentDueAt,
        Long salesOrderId,
        AuctionStatus status,
        OffsetDateTime cancelledAt,
        String suspendedReason,
        Long suspendedBy,
        OffsetDateTime suspendedAt,
        Long relistedFromId,
        int version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * The price to show: what the card sold for while a winner holds it (AWAITING_PAYMENT or
     * SOLD), else the leading bid (or the starting price with no bids). A buy-now win has no
     * bids, so {@code currentPrice} stays at the starting price while {@code winningAmount}
     * holds the buy-now price [TCG-638]. An unpaid win keeps {@code winningAmount} but did
     * not sell, so it falls back to {@code currentPrice} [TCG-639].
     */
    public BigDecimal closingOrCurrentPrice() {
        boolean sold = status == AuctionStatus.AWAITING_PAYMENT || status == AuctionStatus.SOLD;
        return sold && winningAmount != null ? winningAmount : currentPrice;
    }

    /**
     * When it closes while live, when it really stopped once ended. A buy-now, a cancel or a
     * suspension ends it before the time the seller picked [TCG-638, TCG-639].
     */
    public OffsetDateTime closedOrEndsAt() {
        if (status == AuctionStatus.LIVE) return endsAt;
        if (closedAt != null) return closedAt;
        if (cancelledAt != null) return cancelledAt;
        if (suspendedAt != null) return suspendedAt;
        return endsAt;
    }

    /** Whether buy-now can still be used: LIVE, no bids yet and a price set. */
    public boolean buyNowAvailable() {
        return status == AuctionStatus.LIVE && bidCount == 0 && buyNowPrice != null;
    }

    /** The least the next bid may be: the starting price with no bids, else the leading bid plus the increment. */
    public BigDecimal minNextBid() {
        return bidCount == 0 ? startingPrice : currentPrice.add(bidIncrement);
    }

    /** Whether the seller may still change buy-now and the note, or cancel: LIVE with no bids. */
    public boolean sellerEditable() {
        return status == AuctionStatus.LIVE && bidCount == 0;
    }

    /** Whether the card is still locked by this auction. */
    public boolean open() {
        return status == AuctionStatus.LIVE || status == AuctionStatus.AWAITING_PAYMENT;
    }
}
