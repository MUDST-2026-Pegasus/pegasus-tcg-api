package com.pegasus.pegasustcgapi.service.auction;

import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import com.pegasus.pegasustcgapi.repository.auction.BidderStrikeRepository;
import com.pegasus.pegasustcgapi.repository.listing.ListingUnitRepository;
import com.pegasus.pegasustcgapi.repository.order.OrderRepository;
import com.pegasus.pegasustcgapi.repository.user.UserRepository;
import com.pegasus.pegasustcgapi.service.inventory.InventoryService;
import com.pegasus.pegasustcgapi.service.order.OrderLifecycleService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAuctionService {
    private static final Logger AUDIT = LoggerFactory.getLogger("pegasus.audit.auction");
    private final AuctionRepository auctions;
    private final BidderStrikeRepository bidderStrikes;
    private final OrderRepository orders;
    private final OrderLifecycleService orderLifecycle;
    private final ListingUnitRepository units;
    private final InventoryService inventory;
    private final UserRepository users;
    private final Clock clock;

    public AdminAuctionService(AuctionRepository auctions, BidderStrikeRepository bidderStrikes, OrderRepository orders,
            OrderLifecycleService orderLifecycle, ListingUnitRepository units,
            InventoryService inventory, UserRepository users, Clock clock) {
        this.auctions = auctions;
        this.bidderStrikes = bidderStrikes;
        this.orders = orders;
        this.orderLifecycle = orderLifecycle;
        this.units = units;
        this.inventory = inventory;
        this.users = users;
        this.clock = clock;
    }

    @Transactional
    public void suspend(long auctionId, long adminId, String reason) {
        String explanation = requiredReason(reason);
        Auction observed = auctions.findById(auctionId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.AUCTION_NOT_FOUND));
        if (!observed.open()) throw new ConflictException(ErrorCode.AUCTION_CLOSED);
        // Order completion locks the parent before the auction. Keep the same ordering here.
        if (observed.salesOrderId() != null) orders.findSalesOrderByIdForUpdate(observed.salesOrderId());
        Auction current = auctions.lock(auctionId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.AUCTION_NOT_FOUND));
        if (!current.open() || !Objects.equals(observed.salesOrderId(), current.salesOrderId())) {
            throw new ConflictException(ErrorCode.AUCTION_CLOSED);
        }
        if (current.salesOrderId() != null
                && !orderLifecycle.expireUnpaidAuctionOrder(current.salesOrderId(), auctionId)) {
            throw new ConflictException(ErrorCode.AUCTION_CLOSED);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!auctions.suspendByAdmin(auctionId, adminId, explanation, now)) {
            throw new ConflictException(ErrorCode.AUCTION_CLOSED);
        }
        auctions.cancelAllBids(auctionId);
        if (current.salesOrderId() == null && !units.revertFromAuction(current.listingUnitId())) {
            throw new IllegalStateException("Auction card could not be returned to stock: " + auctionId);
        }
        var card = units.findOfSeller(current.listingUnitId(), current.sellerProfileId())
                .orElseThrow(() -> new IllegalStateException("Auction card missing: " + auctionId));
        inventory.recordAuctionCancellation(card, auctionId, adminId);
        AUDIT.info("auction.suspend id={} by={} reason={}", auctionId, adminId, explanation);
    }

    @Transactional
    public void liftBan(long userId, long adminId, String reason) {
        String explanation = requiredReason(reason);
        if (users.findById(userId).isEmpty()) throw new NotFoundException(ErrorCode.USER_NOT_FOUND);
        bidderStrikes.lockBidder(userId);
        bidderStrikes.liftBanAndClearStrikes(userId, adminId, explanation, OffsetDateTime.now(clock));
        AUDIT.info("auction.ban.lift user={} by={} reason={}", userId, adminId, explanation);
    }

    private static String requiredReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "Reason is required");
        }
        return reason.trim();
    }
}
