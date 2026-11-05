package com.pegasus.pegasustcgapi.service.auction;

import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import com.pegasus.pegasustcgapi.service.setting.PlatformSettingService;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BidderStrikeService {
    private final AuctionRepository auctions;
    private final PlatformSettingService settings;

    public BidderStrikeService(AuctionRepository auctions, PlatformSettingService settings) {
        this.auctions = auctions;
        this.settings = settings;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordUnpaidWin(long userId, long auctionId, Long salesOrderId, OffsetDateTime now) {
        auctions.lockBidder(userId);
        if (!auctions.insertStrike(userId, auctionId, salesOrderId, now)) return;
        int limit = settings.getInt(PlatformSettingService.AUCTION_STRIKE_LIMIT);
        if (limit < 1) throw new IllegalStateException("Auction strike limit must be positive");
        if (auctions.countActiveStrikes(userId) == limit) {
            auctions.insertBan(userId, now, now.plus(settings.getDays(PlatformSettingService.AUCTION_BAN_DAYS)));
        }
    }

    public PenaltyStatus status(long userId, OffsetDateTime now) {
        return new PenaltyStatus(auctions.countActiveStrikes(userId),
                settings.getInt(PlatformSettingService.AUCTION_STRIKE_LIMIT),
                auctions.findActiveBanUntil(userId, now).orElse(null));
    }

    public record PenaltyStatus(int strikeCount, int strikeLimit, OffsetDateTime bannedUntil) {}
}
