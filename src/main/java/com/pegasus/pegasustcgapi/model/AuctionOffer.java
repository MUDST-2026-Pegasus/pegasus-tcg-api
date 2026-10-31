package com.pegasus.pegasustcgapi.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One row of {@code auction_offer}: the runner-up offered the card at their own bid
 * after the winner did not pay [TCG-614].
 *
 * @param bidId       the runner-up's bid the price comes from
 * @param respondedAt set once the buyer accepted or declined
 */
public record AuctionOffer(
        long id,
        long auctionId,
        long recipientId,
        long bidId,
        BigDecimal amount,
        AuctionOfferStatus status,
        OffsetDateTime expiresAt,
        OffsetDateTime respondedAt,
        OffsetDateTime createdAt) {
}
