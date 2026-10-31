package com.pegasus.pegasustcgapi.model;

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
 * @param winnerId        whoever must pay now: the top bidder, a buy-now buyer or an accepting runner-up
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
        OffsetDateTime decisionDueAt,
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
        return status == AuctionStatus.LIVE || status == AuctionStatus.AWAITING_PAYMENT
                || status == AuctionStatus.AWAITING_SELLER_DECISION || status == AuctionStatus.OFFERED_TO_SECOND;
    }
}
