package com.pegasus.pegasustcgapi.service.auction;

import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.model.auction.AuctionStatus;
import com.pegasus.pegasustcgapi.model.listing.ListingUnit;
import com.pegasus.pegasustcgapi.model.listing.ListingUnitStatus;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import com.pegasus.pegasustcgapi.repository.listing.ListingUnitRepository;
import com.pegasus.pegasustcgapi.repository.order.OrderRepository;
import com.pegasus.pegasustcgapi.service.inventory.InventoryService;
import com.pegasus.pegasustcgapi.service.order.OrderLifecycleService;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuctionPaymentTimeoutService {
    private final AuctionRepository auctions;
    private final OrderRepository orders;
    private final OrderLifecycleService orderLifecycle;
    private final BidderStrikeService strikes;
    private final ListingUnitRepository units;
    private final InventoryService inventory;

    public AuctionPaymentTimeoutService(AuctionRepository auctions, OrderRepository orders,
            OrderLifecycleService orderLifecycle, BidderStrikeService strikes,
            ListingUnitRepository units, InventoryService inventory) {
        this.auctions = auctions;
        this.orders = orders;
        this.orderLifecycle = orderLifecycle;
        this.strikes = strikes;
        this.units = units;
        this.inventory = inventory;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expire(long auctionId, OffsetDateTime now) {
        Auction observed = auctions.findById(auctionId).orElse(null);
        if (observed == null || observed.status() != AuctionStatus.AWAITING_PAYMENT) return false;
        // Payment completion locks the parent order before updating the auction.
        if (observed.salesOrderId() != null) {
            orders.findSalesOrderByIdForUpdate(observed.salesOrderId());
        }
        Auction auction = auctions.lock(auctionId).orElse(null);
        if (auction == null || auction.status() != AuctionStatus.AWAITING_PAYMENT
                || auction.paymentDueAt() == null || now.isBefore(auction.paymentDueAt())
                || auction.winnerId() == null) return false;
        // An order appeared after our first read. Retry next tick with order-first lock ordering.
        if (!Objects.equals(observed.salesOrderId(), auction.salesOrderId())) return false;
        if (auction.salesOrderId() != null
                && !orderLifecycle.expireUnpaidAuctionOrder(auction.salesOrderId(), auctionId)) return false;

        strikes.recordUnpaidWin(auction.winnerId(), auctionId, auction.salesOrderId(), now);
        if (!auctions.recordUnpaidAuction(auctionId, now)) {
            throw new IllegalStateException("Failed to transition unpaid auction: " + auctionId);
        }
        if (auction.salesOrderId() == null && !units.revertFromAuction(auction.listingUnitId())) {
            throw new IllegalStateException("Auction card was not ON_AUCTION: " + auction.listingUnitId());
        }
        ListingUnit card = units.findOfSeller(auction.listingUnitId(), auction.sellerProfileId())
                .orElseThrow(() -> new IllegalStateException("Auction card is missing: " + auction.listingUnitId()));
        if (card.status() != ListingUnitStatus.IN_STOCK) {
            throw new IllegalStateException("Auction card was not returned to stock: " + card.id());
        }
        inventory.recordAuctionUnsold(card, auctionId, null);
        return true;
    }
}
