package com.pegasus.pegasustcgapi.service.auction;

import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.AuctionWin;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.model.auction.AuctionStatus;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for instant auction purchase (Buy-Now) [TCG-613, TCG-618].
 * Executes under a pessimistic row lock, transitions status to AWAITING_PAYMENT,
 * and grants a 24-hour payment window to the buyer.
 */
@Service
public class AuctionBuyNowService {

    private static final BigDecimal DEFAULT_SHIPPING = new BigDecimal("50.00");
    private static final Duration DEFAULT_PAYMENT_WINDOW = Duration.ofHours(24);

    private final AuctionRepository auctions;
    private final AuctionEligibilityValidator validator;
    private final Clock clock;

    public AuctionBuyNowService(
            AuctionRepository auctions,
            AuctionEligibilityValidator validator,
            Clock clock) {
        this.auctions = auctions;
        this.validator = validator;
        this.clock = clock;
    }

    /**
     * Executes buy-now for an active auction without prior bids.
     *
     * @param auctionId      the auction to buy
     * @param userId         the purchasing buyer's ID
     * @param idempotencyKey optional idempotency key
     * @return the win summary with price, shipping, and payment deadline
     */
    @Transactional
    public AuctionWin buyNow(long auctionId, long userId, String idempotencyKey) {
        OffsetDateTime now = OffsetDateTime.now(clock);

        Auction auction = auctions.lock(auctionId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.AUCTION_NOT_FOUND, "Auction not found: " + auctionId));

        // 1. Idempotency: if caller already won this auction via buy-now
        if (auction.status() == AuctionStatus.AWAITING_PAYMENT && Objects.equals(auction.winnerId(), userId)) {
            BigDecimal price = auction.winningAmount() != null ? auction.winningAmount() : auction.buyNowPrice();
            BigDecimal total = price.add(DEFAULT_SHIPPING);
            return new AuctionWin(
                    auction.id(),
                    auction.auctionNumber(),
                    price,
                    DEFAULT_SHIPPING,
                    total,
                    auction.paymentDueAt(),
                    now);
        }

        // 2. Expiration check
        if (!now.isBefore(auction.endsAt())) {
            throw new ConflictException(ErrorCode.AUCTION_CLOSED, "Auction has already closed");
        }

        // 3. Status check: must be LIVE
        if (auction.status() != AuctionStatus.LIVE) {
            throw new ConflictException(ErrorCode.BUY_NOW_UNAVAILABLE, "Buy-now is not available");
        }

        // 4. Buy-now configured check
        if (auction.buyNowPrice() == null) {
            throw new ConflictException(ErrorCode.BUY_NOW_UNAVAILABLE, "Buy-now is not enabled for this auction");
        }

        // 5. Must have zero bids
        if (auction.bidCount() > 0) {
            throw new ConflictException(ErrorCode.BUY_NOW_UNAVAILABLE, "Buy-now is no longer available after bids have been placed");
        }

        // 6. Buyer Eligibility validation (shipping address, verified email, ban/penalties, own auction)
        validator.validateCanBid(auction, userId, now);

        // 7. Transition auction to AWAITING_PAYMENT
        OffsetDateTime paymentDueAt = now.plus(DEFAULT_PAYMENT_WINDOW);
        boolean updated = auctions.recordBuyNowWin(auctionId, userId, auction.buyNowPrice(), now, paymentDueAt);
        if (!updated) {
            throw new ConflictException(ErrorCode.BUY_NOW_UNAVAILABLE, "Auction could not be bought now");
        }

        BigDecimal price = auction.buyNowPrice();
        BigDecimal total = price.add(DEFAULT_SHIPPING);

        return new AuctionWin(
                auction.id(),
                auction.auctionNumber(),
                price,
                DEFAULT_SHIPPING,
                total,
                paymentDueAt,
                now);
    }
}
