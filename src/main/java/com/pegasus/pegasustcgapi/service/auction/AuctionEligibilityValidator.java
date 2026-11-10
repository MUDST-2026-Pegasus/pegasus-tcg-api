package com.pegasus.pegasustcgapi.service.auction;

import com.pegasus.pegasustcgapi.dto.auction.BidEligibility;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.ForbiddenException;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.model.auction.AuctionStatus;
import com.pegasus.pegasustcgapi.model.seller.SellerProfile;
import com.pegasus.pegasustcgapi.port.UserVerificationPort;
import com.pegasus.pegasustcgapi.port.seller.SellerPort;
import com.pegasus.pegasustcgapi.repository.auction.BidderStrikeRepository;
import com.pegasus.pegasustcgapi.repository.user.AddressRepository;
import com.pegasus.pegasustcgapi.service.setting.PlatformSettingService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Validates buyer eligibility for bidding and buy-now actions [TCG-616, TCG-617, TCG-618].
 */
@Component
public class AuctionEligibilityValidator {

    private final AddressRepository addresses;
    private final UserVerificationPort userVerification;
    private final BidderStrikeRepository bidderStrikes;
    private final SellerPort sellers;
    private final PlatformSettingService settings;

    @Autowired
    public AuctionEligibilityValidator(
            AddressRepository addresses,
            UserVerificationPort userVerification,
            BidderStrikeRepository bidderStrikes,
            SellerPort sellers,
            PlatformSettingService settings) {
        this.addresses = addresses;
        this.userVerification = userVerification;
        this.bidderStrikes = bidderStrikes;
        this.sellers = sellers;
        this.settings = settings;
    }

    private int strikeLimit() {
        return settings.getInt(PlatformSettingService.AUCTION_STRIKE_LIMIT);
    }

    /**
     * Asserts that the caller is eligible to place a bid or buy now; throws the specific
     * standard API exception on rejection.
     */
    public void validateCanBid(Auction auction, long userId, OffsetDateTime now) {
        if (auction.status() != AuctionStatus.LIVE || !now.isBefore(auction.endsAt())) {
            throw new ConflictException(ErrorCode.AUCTION_CLOSED, "Auction is no longer open for bidding");
        }

        if (addresses.findByUserId(userId).isEmpty()) {
            throw new BadRequestException(ErrorCode.NO_SHIPPING_ADDRESS, "Shipping address is required to place a bid");
        }

        if (!userVerification.isEmailVerified(userId)) {
            throw new ForbiddenException(ErrorCode.EMAIL_NOT_VERIFIED, "Email verification is required to place a bid");
        }

        Optional<OffsetDateTime> banUntil = bidderStrikes.findActiveBanUntil(userId, now);
        if (banUntil.isPresent()) {
            throw new ForbiddenException(
                    ErrorCode.BIDDING_BANNED,
                    "User is temporarily banned from bidding until " + banUntil.get(),
                    Map.of("bannedUntil", banUntil.get()));
        }

        if (bidderStrikes.countActiveStrikes(userId) >= strikeLimit()) {
            throw new ForbiddenException(ErrorCode.BIDDING_BANNED, "User is banned from bidding due to unpaid wins");
        }

        if (isOwnAuction(auction.sellerProfileId(), userId)) {
            throw new ForbiddenException(ErrorCode.OWN_AUCTION, "Sellers cannot bid on their own auctions");
        }
    }

    /**
     * Evaluates the caller's bid eligibility state without throwing exceptions (for view/read model).
     */
    public BidEligibility evaluateBidEligibility(Auction auction, Long userId, OffsetDateTime now) {
        if (userId == null) {
            return BidEligibility.UNAUTHENTICATED;
        }

        if (addresses.findByUserId(userId).isEmpty()) {
            return BidEligibility.NO_SHIPPING_ADDRESS;
        }

        if (!userVerification.isEmailVerified(userId)) {
            return BidEligibility.EMAIL_NOT_VERIFIED;
        }

        if (bidderStrikes.findActiveBanUntil(userId, now).isPresent() || bidderStrikes.countActiveStrikes(userId) >= strikeLimit()) {
            return BidEligibility.BIDDING_PENALIZED;
        }

        if (isOwnAuction(auction.sellerProfileId(), userId)) {
            return BidEligibility.OWN_AUCTION;
        }

        return BidEligibility.ELIGIBLE;
    }

    /**
     * Finds any error codes that block the user from bidding on this auction.
     */
    public List<ErrorCode> findBlockers(Auction auction, long userId, OffsetDateTime now) {
        List<ErrorCode> blockers = new ArrayList<>();
        if (isOwnAuction(auction.sellerProfileId(), userId)) {
            blockers.add(ErrorCode.OWN_AUCTION);
        }
        if (addresses.findByUserId(userId).isEmpty()) {
            blockers.add(ErrorCode.NO_SHIPPING_ADDRESS);
        }
        if (!userVerification.isEmailVerified(userId)) {
            blockers.add(ErrorCode.EMAIL_NOT_VERIFIED);
        }
        if (bidderStrikes.findActiveBanUntil(userId, now).isPresent() || bidderStrikes.countActiveStrikes(userId) >= strikeLimit()) {
            blockers.add(ErrorCode.BIDDING_BANNED);
        }
        return blockers;
    }

    public boolean isOwnAuction(long sellerProfileId, long userId) {
        try {
            SellerProfile profile = sellers.requireProfile(userId);
            return profile != null && profile.id() == sellerProfileId;
        } catch (NotFoundException e) {
            return false;
        }
    }
}
