package com.pegasus.pegasustcgapi.model;

import com.pegasus.pegasustcgapi.repository.AdminOrderRepository.AdminOrderQuery.Sort;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The five columns of the admin order board, in the order they are shown.
 *
 * <p>CANCELLED, RETURNED and REFUNDED are finished business and stay off the
 * board; the order list still finds them.
 */
public enum AdminOrderColumn {

    /** Oldest first: the one closest to being cancelled for non-payment. */
    PENDING_PAYMENT(EnumSet.of(SellerOrderStatus.PENDING_PAYMENT), Sort.OLDEST),
    /** Paid and not yet posted, the longest-waiting first. */
    AWAITING_SHIPMENT(EnumSet.of(SellerOrderStatus.PAID, SellerOrderStatus.PREPARING), Sort.PAID_OLDEST),
    IN_TRANSIT(EnumSet.of(SellerOrderStatus.SHIPPED, SellerOrderStatus.DELIVERED), Sort.SHIPPED_NEWEST),
    COMPLETED(EnumSet.of(SellerOrderStatus.COMPLETED), Sort.COMPLETED_NEWEST),
    /** A buyer has asked to return it and escrow is on hold. */
    DISPUTED(EnumSet.of(SellerOrderStatus.RETURN_REQUESTED), Sort.OLDEST);

    private final Set<SellerOrderStatus> statuses;
    private final Sort sort;

    AdminOrderColumn(Set<SellerOrderStatus> statuses, Sort sort) {
        this.statuses = statuses;
        this.sort = sort;
    }

    public List<String> statusNames() {
        return SellerOrderStatus.namesOf(statuses);
    }

    public Sort sort() {
        return sort;
    }
}
