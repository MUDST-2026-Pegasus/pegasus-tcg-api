package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.SellerOrderDetailsResponse;
import com.pegasus.pegasustcgapi.dto.SellerOrderViews.Buyer;
import com.pegasus.pegasustcgapi.dto.SellerOrderViews.Detail;
import com.pegasus.pegasustcgapi.dto.SellerOrderViews.Payment;
import com.pegasus.pegasustcgapi.dto.SellerOrderViews.Summary;
import com.pegasus.pegasustcgapi.dto.ShipOrderRequest;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.SellerProfile;
import com.pegasus.pegasustcgapi.port.SellerPort;
import com.pegasus.pegasustcgapi.repository.AdminOrderRepository;
import com.pegasus.pegasustcgapi.repository.AdminOrderRepository.AdminOrderQuery;
import com.pegasus.pegasustcgapi.repository.AdminOrderRepository.AdminOrderRow;
import com.pegasus.pegasustcgapi.repository.AdminOrderRepository.OrderContext;
import com.pegasus.pegasustcgapi.repository.OrderRepository;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The seller's own orders, read the way the order page needs them [CR-7].
 *
 * <p>Reads go through {@link AdminOrderRepository} with the caller's shop pinned,
 * so searching, sorting and filtering by several statuses behave exactly as they
 * do for the admin. Every transition stays in {@link OrderLifecycleService}; the
 * methods here only hand back the refreshed view afterwards.
 *
 * <p>The shop is read from the seller module on every call rather than from the
 * token, so a seller suspended after signing in still sees their orders but the
 * lifecycle service decides what they may change.
 */
@Service
public class SellerOrderDeskService {

    private final AdminOrderRepository orders;
    private final OrderRepository orderRecords;
    private final OrderLifecycleService lifecycle;
    private final SellerPort sellers;

    public SellerOrderDeskService(
            AdminOrderRepository orders,
            OrderRepository orderRecords,
            OrderLifecycleService lifecycle,
            SellerPort sellers) {

        this.orders = orders;
        this.orderRecords = orderRecords;
        this.lifecycle = lifecycle;
        this.sellers = sellers;
    }

    /**
     * @param statuses any number of status names, repeated or comma-separated; empty for all
     * @param search   order number or the buyer's username / display name
     * @param sort     an {@link AdminOrderQuery.Sort} name; null for newest first
     */
    @Transactional(readOnly = true)
    public PageResponse<Summary> list(
            AuthPrincipal principal, List<String> statuses, String search, String sort, int page, int size) {

        SellerProfile shop = sellers.requireProfile(principal.userId());
        Paging paging = Paging.of(page, size);
        AdminOrderQuery query = new AdminOrderQuery(
                AdminOrderService.parseStatuses(statuses),
                shop.id(),
                null,
                null,
                null,
                search == null || search.isBlank() ? null : search.trim(),
                AdminOrderService.parseSort(sort),
                paging.size(),
                paging.offset());

        List<AdminOrderRow> rows = orders.findPage(query);
        return PageResponse.of(summaries(shop, rows), paging.page(), paging.size(), orders.count(query));
    }

    /** Every status the shop has orders in, with how many; statuses with none are left out. */
    @Transactional(readOnly = true)
    public Map<String, Long> counts(AuthPrincipal principal) {
        return orders.countByStatus(sellers.requireProfile(principal.userId()).id());
    }

    /** @throws NotFoundException for an id that is not one of this shop's, so its existence is not confirmed */
    @Transactional(readOnly = true)
    public Detail detail(AuthPrincipal principal, long sellerOrderId) {
        SellerProfile shop = sellers.requireProfile(principal.userId());
        AdminOrderRow row = orders.findById(sellerOrderId)
                .filter(found -> found.sellerProfileId() == shop.id())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND));

        Summary summary = summaries(shop, List.of(row)).getFirst();
        Payment payment = orders.findLatestPayment(row.salesOrderId()).map(Payment::from).orElse(null);
        BigDecimal cost = orderRecords.findCostTotalsBySellerOrderIds(List.of(sellerOrderId))
                .getOrDefault(sellerOrderId, BigDecimal.ZERO);

        return new Detail(summary, payment, cost,
                orderRecords.countPaidOrdersOfBuyerAtSeller(shop.id(), row.buyerId()));
    }

    @Transactional
    public Detail accept(AuthPrincipal principal, long sellerOrderId) {
        lifecycle.acceptSellerOrder(principal, sellerOrderId);
        return detail(principal, sellerOrderId);
    }

    @Transactional
    public Detail ship(AuthPrincipal principal, long sellerOrderId, ShipOrderRequest request) {
        lifecycle.shipSellerOrder(principal, sellerOrderId, request);
        return detail(principal, sellerOrderId);
    }

    @Transactional
    public Detail cancel(AuthPrincipal principal, long sellerOrderId, String reason) {
        lifecycle.cancelSellerOrderBySeller(principal, sellerOrderId, reason.trim());
        return detail(principal, sellerOrderId);
    }

    /** A page of rows in a fixed number of queries, in the order the rows came. */
    private List<Summary> summaries(SellerProfile shop, List<AdminOrderRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, SellerOrderDetailsResponse> details = lifecycle.toSellerOrderDetailsResponses(
                rows.stream().map(AdminOrderRow::sellerOrderId).toList());
        Map<Long, OrderContext> contexts = orders.findOrderContexts(
                rows.stream().map(AdminOrderRow::salesOrderId).distinct().toList());

        return rows.stream().map(row -> {
            OrderContext context = contexts.getOrDefault(row.salesOrderId(), new OrderContext(null, null));
            return new Summary(
                    details.get(row.sellerOrderId()),
                    row.orderNumber(),
                    new Buyer(row.buyerId(), row.buyerUsername(), row.buyerDisplayName()),
                    row.paidAt(),
                    shipBy(row.paidAt(), shop),
                    context.shippingAddressSnapshot(),
                    context.buyerNote());
        }).toList();
    }

    /**
     * The shop's own promise: paid plus its handling days, in calendar days. The
     * page flags an order as late from this, not from a platform-wide rule.
     */
    private static OffsetDateTime shipBy(OffsetDateTime paidAt, SellerProfile shop) {
        return paidAt == null ? null : paidAt.plusDays(shop.handlingDays());
    }
}
