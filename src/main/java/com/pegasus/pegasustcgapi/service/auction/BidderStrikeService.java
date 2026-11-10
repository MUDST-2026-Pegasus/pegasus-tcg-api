package com.pegasus.pegasustcgapi.service.auction;

import com.pegasus.pegasustcgapi.repository.auction.BidderStrikeRepository;
import com.pegasus.pegasustcgapi.service.setting.PlatformSettingService;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BidderStrikeService {
    private final BidderStrikeRepository bidderStrikes;
    private final PlatformSettingService settings;

    public BidderStrikeService(BidderStrikeRepository bidderStrikes, PlatformSettingService settings) {
        this.bidderStrikes = bidderStrikes;
        this.settings = settings;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordUnpaidWin(long userId, long auctionId, Long salesOrderId, OffsetDateTime now) {
        bidderStrikes.lockBidder(userId);
        if (!bidderStrikes.insertStrike(userId, auctionId, salesOrderId, now)) return;
        int limit = settings.getInt(PlatformSettingService.AUCTION_STRIKE_LIMIT);
        if (limit < 1) throw new IllegalStateException("Auction strike limit must be positive");
        if (bidderStrikes.countActiveStrikes(userId) >= limit
                && bidderStrikes.findActiveBanUntil(userId, now).isEmpty()) {
            bidderStrikes.insertBan(userId, now, now.plus(settings.getDays(PlatformSettingService.AUCTION_BAN_DAYS)));
        }
    }

    public PenaltyStatus status(long userId, OffsetDateTime now) {
        return new PenaltyStatus(bidderStrikes.countActiveStrikes(userId),
                settings.getInt(PlatformSettingService.AUCTION_STRIKE_LIMIT),
                bidderStrikes.findActiveBanUntil(userId, now).orElse(null));
    }

    public record PenaltyStatus(int strikeCount, int strikeLimit, OffsetDateTime bannedUntil) {}
}
