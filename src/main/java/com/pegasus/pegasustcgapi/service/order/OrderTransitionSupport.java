package com.pegasus.pegasustcgapi.service.order;

import com.pegasus.pegasustcgapi.service.setting.PlatformSettingService;

import com.pegasus.pegasustcgapi.dto.order.CancelOrderRequest;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.UnauthorizedException;
import com.pegasus.pegasustcgapi.jooq.tables.records.SalesOrderRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.SellerOrderRecord;
import com.pegasus.pegasustcgapi.model.order.SellerOrderStatus;
import com.pegasus.pegasustcgapi.model.seller.SellerProfile;
import com.pegasus.pegasustcgapi.port.seller.SellerPort;
import com.pegasus.pegasustcgapi.repository.order.OrderQueryRepository;
import com.pegasus.pegasustcgapi.repository.order.OrderReferenceQueryRepository;
import com.pegasus.pegasustcgapi.repository.order.OrderRepository;
import com.pegasus.pegasustcgapi.repository.order.OrderTransitionRepository;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/** Shared validation and time source for order transitions. */
final class OrderTransitionSupport {
    final OrderRepository orderRepository;
    final OrderTransitionRepository orderTransitions;
    final OrderQueryRepository orderQueries;
    final OrderReferenceQueryRepository orderReferences;
    final SellerPort sellerPort;
    final PlatformSettingService platformSettingService;
    final Clock clock;
    static final List<String> CANCELLABLE_STATUSES = SellerOrderStatus.namesOf(SellerOrderStatus.CANCELLABLE);
    static final List<String> CONFIRMABLE_STATUSES = SellerOrderStatus.namesOf(SellerOrderStatus.CONFIRMABLE);
    static final List<String> SHIPPABLE_STATUSES = SellerOrderStatus.namesOf(SellerOrderStatus.SHIPPABLE);

    OrderTransitionSupport(OrderRepository orderRepository, OrderTransitionRepository orderTransitions,
            OrderQueryRepository orderQueries, OrderReferenceQueryRepository orderReferences, SellerPort sellerPort, PlatformSettingService platformSettingService, Clock clock) {
        this.orderRepository = orderRepository;
        this.orderTransitions = orderTransitions;
        this.orderQueries = orderQueries;
        this.orderReferences = orderReferences;
        this.sellerPort = sellerPort;
        this.platformSettingService = platformSettingService;
        this.clock = clock;
    }

    SellerOrderRecord resolveBuyerSellerOrder(AuthPrincipal principal, long sellerOrderId) {
        SellerOrderRecord sellerOrder = orderRepository.findSellerOrderById(sellerOrderId).orElse(null);
        if (sellerOrder == null) {
            return null;
        }
        boolean ownedByCaller = orderRepository
                .findSalesOrderByIdAndBuyerId(sellerOrder.getSalesOrderId(), principal.userId())
                .isPresent();
        return ownedByCaller ? sellerOrder : null;
    }

    void requireWithinCancelWindow(SalesOrderRecord salesOrder) {
        if (salesOrder.getPaidAt() == null) {
            return;
        }
        Duration window = platformSettingService.getHours(PlatformSettingService.ORDER_CANCEL_WINDOW_HOURS);
        if (salesOrder.getPaidAt().plus(window).isBefore(now())) {
            throw new ConflictException(ErrorCode.CANCEL_WINDOW_CLOSED);
        }
    }

    OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    static String cancelReason(CancelOrderRequest request) {
        return (request != null && request.reason() != null && !request.reason().isBlank())
                ? request.reason() : "Cancelled by buyer";
    }

    void requireAuthenticated(AuthPrincipal principal) {
        if (principal == null) {
            throw new UnauthorizedException(ErrorCode.UNAUTHENTICATED);
        }
    }

    SellerProfile requireSellerProfile(long userId) {
        return sellerPort.requireProfile(userId);
    }
}
