package com.pegasus.pegasustcgapi.service.auction;

import com.pegasus.pegasustcgapi.dto.auction.AuctionRequests.AuctionCheckout;
import com.pegasus.pegasustcgapi.dto.order.CheckoutResponse;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.ForbiddenException;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.exception.UnauthorizedException;
import com.pegasus.pegasustcgapi.jooq.tables.records.OrderItemRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.model.auction.Auction;
import com.pegasus.pegasustcgapi.model.auction.AuctionStatus;
import com.pegasus.pegasustcgapi.model.listing.ListingUnitStatus;
import com.pegasus.pegasustcgapi.model.order.SellerOrderStatus;
import com.pegasus.pegasustcgapi.model.shipping.ShippingOption;
import com.pegasus.pegasustcgapi.model.user.Address;
import com.pegasus.pegasustcgapi.port.finance.wallet.LedgerPort;
import com.pegasus.pegasustcgapi.port.finance.wallet.LedgerPort.CommissionQuote;
import com.pegasus.pegasustcgapi.port.inventory.InventoryPort;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import com.pegasus.pegasustcgapi.repository.listing.ListingUnitRepository;
import com.pegasus.pegasustcgapi.repository.order.OrderRepository;
import com.pegasus.pegasustcgapi.repository.order.OrderTransitionRepository;
import com.pegasus.pegasustcgapi.repository.order.OrderReferenceQueryRepository;
import com.pegasus.pegasustcgapi.repository.order.OrderReferenceQueryRepository.ListingSnapshotDetails;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.order.CheckoutService;
import com.pegasus.pegasustcgapi.service.shipping.ShippingOptionService;
import com.pegasus.pegasustcgapi.service.user.AddressService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.springframework.transaction.annotation.Transactional;

/** Places a won auction through the same order rows and payment flow as cart checkout. */
@Service
public class AuctionCheckoutService {

    private final AuctionRepository auctions;
    private final ListingUnitRepository units;
    private final OrderRepository orders;
    private final OrderTransitionRepository orderTransitions;
    private final OrderReferenceQueryRepository orderReferences;
    private final CheckoutService checkout;
    private final AddressService addresses;
    private final ShippingOptionService shipping;
    private final InventoryPort inventory;
    private final LedgerPort ledger;
    private final Clock clock;

    public AuctionCheckoutService(AuctionRepository auctions, ListingUnitRepository units,
            OrderRepository orders,
            OrderTransitionRepository orderTransitions,
            OrderReferenceQueryRepository orderReferences, CheckoutService checkout, AddressService addresses,
            ShippingOptionService shipping, InventoryPort inventory, LedgerPort ledger, Clock clock) {
        this.auctions = auctions;
        this.units = units;
        this.orders = orders;
        this.orderTransitions = orderTransitions;
        this.orderReferences = orderReferences;
        this.checkout = checkout;
        this.addresses = addresses;
        this.shipping = shipping;
        this.inventory = inventory;
        this.ledger = ledger;
        this.clock = clock;
    }

    @Transactional
    public CheckoutResponse checkout(long auctionId, AuthPrincipal principal, String key, AuctionCheckout request) {
        if (principal == null) throw new UnauthorizedException(ErrorCode.UNAUTHENTICATED);
        if (key == null || key.isBlank() || key.trim().length() > 64) {
            throw new BadRequestException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        if (request == null || request.shippingAddressId() == null) {
            throw new BadRequestException(ErrorCode.SHIPPING_ADDRESS_REQUIRED);
        }
        if (request.platformCode() != null || request.sellerCodes() != null) {
            throw new BadRequestException(ErrorCode.PROMO_NOT_APPLICABLE);
        }
        String trimmedKey = key.trim();
        Auction auction = auctions.lock(auctionId).orElseThrow(() -> new NotFoundException(ErrorCode.AUCTION_NOT_FOUND));
        if (!Objects.equals(auction.winnerId(), principal.userId())) {
            throw new ForbiddenException(ErrorCode.ACCESS_DENIED);
        }
        if (auction.salesOrderId() != null) {
            SalesOrderRecord placed = orders.findSalesOrderById(auction.salesOrderId())
                    .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));
            if (!trimmedKey.equals(placed.getIdempotencyKey())) {
                throw new ConflictException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            }
            return checkout.getOrderDetails(placed.getId(), true);
        }
        if (auction.status() != AuctionStatus.AWAITING_PAYMENT || auction.paymentDueAt() == null
                || !OffsetDateTime.now(clock).isBefore(auction.paymentDueAt())) {
            throw new ConflictException(ErrorCode.AUCTION_CLOSED);
        }
        if (orders.findSalesOrderByIdempotencyKey(trimmedKey).isPresent()) {
            throw new ConflictException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }

        Address address = addresses.get(principal.userId(), request.shippingAddressId());
        BigDecimal price = auction.winningAmount();
        if (price == null) throw new ConflictException(ErrorCode.AUCTION_CLOSED);
        ShippingOption option = shipping.resolveForCheckout(auction.sellerProfileId(), request.shippingOptionId());
        BigDecimal fee = option.feeFor(1, price);
        BigDecimal total = price.add(fee);
        if (request.expectedGrandTotal() != null && request.expectedGrandTotal().compareTo(total) != 0) {
            throw new ConflictException(ErrorCode.CART_PRICE_CHANGED);
        }

        // The auction already holds this exact card; reserve it directly, never select another listing unit.
        if (units.changeStatus(List.of(auction.listingUnitId()), ListingUnitStatus.ON_AUCTION,
                ListingUnitStatus.RESERVED) != 1) {
            throw new ConflictException(ErrorCode.UNIT_NOT_AVAILABLE);
        }
        ListingSnapshotDetails card = orderReferences.findAuctionCardDetails(auction.listingUnitId())
                .orElseThrow(() -> new ConflictException(ErrorCode.UNIT_NOT_AVAILABLE));
        CommissionQuote commission = ledger.quoteCommission(auction.sellerProfileId(), price,
                List.of(auction.catalogVariantId()));
        if (commission == null) commission = CommissionQuote.NONE;
        BigDecimal net = total.subtract(commission.amount());
        BigDecimal cost = inventory.averageUnitCost(auction.sellerProfileId(), auction.catalogVariantId(),
                auction.condition());

        String number = orders.nextOrderNumber();
        SalesOrderRecord sale;
        try {
            sale = orders.insertSalesOrder(number, principal.userId(), "THB", price, fee,
                    BigDecimal.ZERO, total, address.id(), checkout.createAddressSnapshot(address),
                    request.buyerNote(), trimmedKey);
        } catch (DuplicateKeyException | IntegrityConstraintViolationException e) {
            throw new ConflictException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        orders.markAuctionSource(sale.getId(), auctionId);
        SellerOrderRecord seller = orders.insertSellerOrder(sale.getId(), auction.sellerProfileId(),
                number + "-S" + auction.sellerProfileId(), price, fee, BigDecimal.ZERO, total,
                commission.ratePercent(), commission.amount(), net, option.id());
        orderTransitions.insertStatusHistory(seller.getId(), null, SellerOrderStatus.PENDING_PAYMENT.name(),
                principal.userId(), "Order placed");
        OrderItemRecord item = orders.insertOrderItem(seller.getId(), card.listingId(),
                auction.catalogVariantId(), 1, price, price, cost, card.productName(), card.variantLabel(),
                card.condition(), card.gameName(), card.imageKey());
        orders.insertOrderItemUnits(item.getId(), List.of(auction.listingUnitId()));
        auctions.attachSalesOrder(auctionId, sale.getId());
        return checkout.getOrderDetails(sale.getId(), false);
    }
}
