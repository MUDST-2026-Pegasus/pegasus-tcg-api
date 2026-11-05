package com.pegasus.pegasustcgapi.service.auction;

import com.pegasus.pegasustcgapi.dto.auction.AuctionViews.BidPlaced;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.model.auction.AuctionBid;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service handling auction bidding with pessimistic locking, anti-sniping and idempotency [TCG-614, TCG-617].
 */
@Service
public class AuctionBidService {

    private final AuctionRepository auctions;
    private final AuctionEligibilityValidator validator;
    private final Clock clock;

    public AuctionBidService(
            AuctionRepository auctions,
            AuctionEligibilityValidator validator,
            Clock clock) {
        this.auctions = auctions;
        this.validator = validator;
        this.clock = clock;
    }

    /**
     * Places a bid on an active auction under a pessimistic row lock.
     *
     * @param auctionId      the auction being bid on
     * @param userId         the caller's user account ID
     * @param amount         bid amount in THB
     * @param idempotencyKey optional idempotency key from header
     * @return summary of the accepted bid
     */
    @Transactional
    public BidPlaced placeBid(long auctionId, long userId, BigDecimal amount, String idempotencyKey) {
        OffsetDateTime now = OffsetDateTime.now(clock);

        Auction auction = auctions.lock(auctionId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.AUCTION_NOT_FOUND, "Auction not found: " + auctionId));

        // 1. Idempotency check if key provided
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<AuctionBid> existingBid = auctions.findBidByIdempotencyKey(auctionId, idempotencyKey);
            if (existingBid.isPresent()) {
                AuctionBid existing = existingBid.get();
                if (existing.bidderId() != userId || existing.amount().compareTo(amount) != 0) {
                    throw new ConflictException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, "Idempotency key was used with different parameters");
                }
                boolean leading = Objects.equals(auction.leadingBidId(), existing.id());
                return new BidPlaced(
                        auctionId,
                        existing.amount(),
                        leading,
                        false,
                        auction.currentPrice(),
                        auction.minNextBid(),
                        auction.bidCount(),
                        auction.endsAt(),
                        now);
            }
        }

        // Prevent self-outbidding: current leading bidder cannot bid against themselves
        if (auction.leadingBidId() != null) {
            auctions.findBidById(auction.leadingBidId()).ifPresent(leadingBid -> {
                if (leadingBid.bidderId() == userId) {
                    throw new ConflictException(ErrorCode.ALREADY_LEADING, "You are already the highest bidder");
                }
            });
        }

        // 2. Buyer Eligibility & auction live status validation
        validator.validateCanBid(auction, userId, now);

        // 3. Bid amount validation: first bid >= startingPrice, subsequent bids >= currentPrice + bidIncrement
        BigDecimal minNextBid = auction.minNextBid();
        if (amount.compareTo(minNextBid) < 0) {
            throw new ConflictException(
                    ErrorCode.BID_TOO_LOW,
                    "Bid must be at least " + minNextBid,
                    Map.of("minNextBid", minNextBid, "currentPrice", auction.currentPrice()));
        }

        // 4. Check if caller is a new bidder for this auction
        boolean isNewBidder = !auctions.hasBid(auctionId, userId);

        // 5. Anti-sniping check: if within last 120s, push endsAt to now + 120s
        Duration remaining = Duration.between(now, auction.endsAt());
        boolean extended = !remaining.isNegative() && remaining.getSeconds() < AuctionPolicy.ANTI_SNIPING_WINDOW_SECONDS;
        OffsetDateTime newEndsAt = extended ? now.plusSeconds(AuctionPolicy.ANTI_SNIPING_WINDOW_SECONDS) : auction.endsAt();

        // 6. Insert new bid record (guarded against duplicate idempotency key)
        long bidId;
        try {
            bidId = auctions.insertBid(auctionId, userId, amount, idempotencyKey);
        } catch (DuplicateKeyException e) {
            throw new ConflictException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, "Duplicate idempotency key");
        }

        // 7. Update auction row with the new bid state
        OffsetDateTime firstBidAt = auction.firstBidAt() == null ? now : null;
        auctions.recordBid(auctionId, bidId, amount, isNewBidder, firstBidAt, newEndsAt, extended, now);

        int newBidCount = auction.bidCount() + 1;
        BigDecimal nextMinBid = amount.add(auction.bidIncrement());

        return new BidPlaced(
                auctionId,
                amount,
                true,
                extended,
                amount,
                nextMinBid,
                newBidCount,
                newEndsAt,
                now);
    }
}
