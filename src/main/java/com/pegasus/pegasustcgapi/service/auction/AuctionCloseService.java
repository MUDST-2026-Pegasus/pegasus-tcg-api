package com.pegasus.pegasustcgapi.service.auction;

import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.model.auction.AuctionBid;
import com.pegasus.pegasustcgapi.model.auction.AuctionStatus;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import com.pegasus.pegasustcgapi.repository.listing.ListingUnitRepository;
import com.pegasus.pegasustcgapi.service.inventory.InventoryService;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service to execute the close transition of a single auction [TCG-619, ISSUE-006].
 * Runs in its own transaction (REQUIRES_NEW) so failure on one auction does not impact others.
 */
@Service
public class AuctionCloseService {

    private static final Logger log = LoggerFactory.getLogger(AuctionCloseService.class);

    private final AuctionRepository auctions;
    private final ListingUnitRepository units;
    private final InventoryService inventoryService;

    public AuctionCloseService(
            AuctionRepository auctions,
            ListingUnitRepository units,
            InventoryService inventoryService) {
        this.auctions = auctions;
        this.units = units;
        this.inventoryService = inventoryService;
    }

    /**
     * Closes an auction that has reached its endsAt under a pessimistic row lock.
     *
     * @param auctionId the auction to close
     * @param now       the current evaluation timestamp from Clock
     * @return true if the auction was transitioned (won, unsold, suspended); false if skipped
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean closeAuction(long auctionId, OffsetDateTime now) {
        Auction auction = auctions.lock(auctionId).orElse(null);
        if (auction == null || auction.status() != AuctionStatus.LIVE) {
            return false;
        }

        // Anti-sniping safety check: skip if endsAt was extended past now
        if (now.isBefore(auction.endsAt())) {
            log.info("Auction #{} endsAt {} was extended past now {}; skipping close",
                    auctionId, auction.endsAt(), now);
            return false;
        }

        if (auction.bidCount() > 0) {
            return closeWithWinner(auction, now);
        } else {
            return closeUnsold(auction, now);
        }
    }

    private boolean closeWithWinner(Auction auction, OffsetDateTime now) {
        Long winnerId = null;
        if (auction.leadingBidId() != null) {
            winnerId = auctions.findBidById(auction.leadingBidId())
                    .map(AuctionBid::bidderId)
                    .orElse(null);
        }
        if (winnerId == null) {
            winnerId = auctions.findLeadingBidderId(auction.id()).orElse(null);
        }

        if (winnerId == null) {
            log.error("Auction #{} has bidCount {} but no leading bidder found; moving to SUSPENDED to prevent scheduler starvation",
                    auction.id(), auction.bidCount());
            auctions.recordAuctionSuspended(auction.id(), "Stuck auction: bidCount > 0 but winner could not be resolved", now);
            return true;
        }

        OffsetDateTime paymentDueAt = now.plus(AuctionPolicy.DEFAULT_PAYMENT_WINDOW);
        boolean updated = auctions.recordAuctionWon(auction.id(), winnerId, auction.currentPrice(), now, paymentDueAt);
        if (updated) {
            log.info("Auction #{} closed: winner #{}, amount {}, payment due at {}",
                    auction.id(), winnerId, auction.currentPrice(), paymentDueAt);
        }
        return updated;
    }

    private boolean closeUnsold(Auction auction, OffsetDateTime now) {
        boolean updated = auctions.recordAuctionUnsold(auction.id(), now);
        if (!updated) {
            return false;
        }

        // Return card to stock (listing_unit status becomes IN_STOCK)
        units.revertFromAuction(auction.listingUnitId());

        // Record inventory movement via InventoryService
        units.findOfSeller(auction.listingUnitId(), auction.sellerProfileId())
                .ifPresent(u -> inventoryService.recordAuctionUnsold(u, auction.id(), null));

        log.info("Auction #{} closed UNSOLD; card #{} returned to stock",
                auction.id(), auction.listingUnitId());
        return true;
    }
}
