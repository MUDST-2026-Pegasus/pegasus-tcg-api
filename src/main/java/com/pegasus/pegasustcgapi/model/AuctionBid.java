package com.pegasus.pegasustcgapi.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One row of {@code auction_bid}. A bid is never withdrawn; only a suspension cancels it [TCG-614]. */
public record AuctionBid(
        long id,
        long auctionId,
        long bidderId,
        BigDecimal amount,
        BidStatus status,
        String idempotencyKey,
        OffsetDateTime createdAt) {
}
