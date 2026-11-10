package com.pegasus.pegasustcgapi.service.order;

import com.pegasus.pegasustcgapi.model.cart.CartItem;
import com.pegasus.pegasustcgapi.model.promotion.Promotion;
import com.pegasus.pegasustcgapi.port.inventory.InventoryPort;
import com.pegasus.pegasustcgapi.port.inventory.InventoryPort.ReservedUnit;
import com.pegasus.pegasustcgapi.service.promotion.CheckoutPromotionService;
import com.pegasus.pegasustcgapi.service.sale.SaleCheckoutService;
import com.pegasus.pegasustcgapi.service.sale.SaleCheckoutService.Hold;
import com.pegasus.pegasustcgapi.service.sale.SaleCheckoutService.Plan;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Reservations run in the caller's checkout transaction and retain its lock order. */
@Service
class CheckoutReservationService {
    private final InventoryPort inventory;
    private SaleCheckoutService sales;
    private final CheckoutPromotionService promotions;

    @Autowired
    CheckoutReservationService(InventoryPort inventory, CheckoutPromotionService promotions) {
        this(inventory, null, promotions);
    }

    CheckoutReservationService(InventoryPort inventory, SaleCheckoutService sales,
            CheckoutPromotionService promotions) {
        this.inventory = inventory;
        this.sales = sales;
        this.promotions = promotions;
    }

    @Autowired(required = false)
    void setSaleCheckout(SaleCheckoutService sales) { this.sales = sales; }

    record Stock(Map<Long, List<ReservedUnit>> units, Map<Long, Hold> saleHolds) {}

    Stock reserveStock(List<CartItem> lines, Plan plan) {
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (CartItem item : lines) quantities.merge(item.listingId(), item.quantity(), Integer::sum);
        Map<Long, List<ReservedUnit>> units = inventory.reserve(quantities);
        Map<Long, Hold> holds = sales == null ? Map.of() : sales.hold(plan);
        return new Stock(units, holds);
    }

    Optional<Promotion> reservePromotions(long buyerId, long orderId, Map<Long, Long> sellerOrders,
            CheckoutPromotionService.Evaluation evaluation) {
        return promotions.reserve(buyerId, orderId, sellerOrders, evaluation);
    }
}
