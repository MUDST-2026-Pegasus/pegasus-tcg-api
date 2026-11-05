package com.pegasus.pegasustcgapi.service.auction;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Shared business policy constants for auctions [ISSUE-010].
 */
public final class AuctionPolicy {

    public static final BigDecimal DEFAULT_SHIPPING = new BigDecimal("50.00");
    public static final Duration DEFAULT_PAYMENT_WINDOW = Duration.ofHours(24);
    public static final long ANTI_SNIPING_WINDOW_SECONDS = 120;
    public static final Duration MIN_AUCTION_DURATION = Duration.ofHours(1);
    public static final Duration MAX_AUCTION_DURATION = Duration.ofDays(30);
    public static final int MAX_STRIKES_ALLOWED = 3;

    private AuctionPolicy() {
    }
}
