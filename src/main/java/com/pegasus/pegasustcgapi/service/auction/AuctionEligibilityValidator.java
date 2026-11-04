package com.pegasus.pegasustcgapi.service.auction;

import com.pegasus.pegasustcgapi.dto.auction.BidEligibility;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.ForbiddenException;
import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.model.auction.AuctionStatus;
import com.pegasus.pegasustcgapi.model.seller.SellerProfile;
import com.pegasus.pegasustcgapi.port.UserVerificationPort;
import com.pegasus.pegasustcgapi.port.seller.SellerPort;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import com.pegasus.pegasustcgapi.repository.user.AddressRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Validates buyer eligibility for bidding and buy-now actions [TCG-616, TCG-617, TCG-618].
 */
@Component
public class AuctionEligibilityValidator {

    private final AddressRepository addresses;
    private final UserVerificationPort userVerification;
    private final AuctionRepository auctions;
    private final SellerPort sellers;

    public AuctionEligibilityValidator(
            AddressRepository addresses,
            UserVerificationPort userVerification,
            AuctionRepository auctions,
            SellerPort sellers) {
        this.addresses = addresses;
        this.userVerification = userVerification;
        this.auctions = auctions;
        this.sellers = sellers;
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

        Optional<OffsetDateTime> banUntil = auctions.findActiveBanUntil(userId, now);
        if (banUntil.isPresent()) {
            throw new ForbiddenException(
                    ErrorCode.BIDDING_BANNED,
                    "User is temporarily banned from bidding until " + banUntil.get(),
                    Map.of("bannedUntil", banUntil.get()));
        }

        if (auctions.countActiveStrikes(userId) >= 3) {
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

        if (auctions.findActiveBanUntil(userId, now).isPresent() || auctions.countActiveStrikes(userId) >= 3) {
            return BidEligibility.BIDDING_PENALIZED;
        }

        if (isOwnAuction(auction.sellerProfileId(), userId)) {
            return BidEligibility.OWN_AUCTION;
        }

        return BidEligibility.ELIGIBLE;
    }

    public boolean isOwnAuction(long sellerProfileId, long userId) {
        try {
            SellerProfile profile = sellers.requireProfile(userId);
            return profile != null && profile.id() == sellerProfileId;
        } catch (Exception e) {
            return false;
        }
    }
}
