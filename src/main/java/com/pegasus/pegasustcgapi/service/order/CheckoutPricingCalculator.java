package com.pegasus.pegasustcgapi.service.order;

import com.pegasus.pegasustcgapi.model.cart.CartItem;
import com.pegasus.pegasustcgapi.port.pricing.PricingPort.ListingOffer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Arithmetic on already resolved checkout snapshots. This class performs no I/O. */
public final class CheckoutPricingCalculator {
    private CheckoutPricingCalculator() {}

    public record SellerPrice(BigDecimal subtotal, BigDecimal shipping, BigDecimal sellerDiscount,
            BigDecimal platformDiscount, BigDecimal saleSubsidy, BigDecimal grandTotal,
            BigDecimal commissionBase, BigDecimal netAmount) {}

    public record Totals(BigDecimal itemsSubtotal, BigDecimal shippingTotal,
            BigDecimal discountTotal, BigDecimal grandTotal) {}

    public static BigDecimal lineTotal(CartItem item) {
        return item.unitPriceAtAdd().multiply(BigDecimal.valueOf(item.quantity()));
    }

    public static BigDecimal subtotal(List<CartItem> items) {
        return items.stream().map(CheckoutPricingCalculator::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static Map<Long, List<CartItem>> groupBySeller(List<CartItem> items, Map<Long, ListingOffer> offers) {
        Map<Long, List<CartItem>> grouped = new LinkedHashMap<>();
        for (CartItem item : items) {
            grouped.computeIfAbsent(offers.get(item.listingId()).sellerProfileId(), ignored -> new ArrayList<>()).add(item);
        }
        return grouped;
    }

    public static BigDecimal commissionBase(BigDecimal subtotal, BigDecimal saleSubsidy, BigDecimal sellerDiscount) {
        return subtotal.add(saleSubsidy).subtract(sellerDiscount);
    }

    public static SellerPrice seller(BigDecimal subtotal, BigDecimal shipping, BigDecimal sellerDiscount,
            BigDecimal platformDiscount, BigDecimal saleSubsidy, BigDecimal commission) {
        BigDecimal grandTotal = subtotal.add(shipping).subtract(sellerDiscount).subtract(platformDiscount);
        BigDecimal net = grandTotal.add(platformDiscount).add(saleSubsidy).subtract(commission);
        return new SellerPrice(subtotal, shipping, sellerDiscount, platformDiscount, saleSubsidy, grandTotal,
                commissionBase(subtotal, saleSubsidy, sellerDiscount), net);
    }

    public static Totals totals(List<SellerPrice> sellers) {
        BigDecimal items = BigDecimal.ZERO;
        BigDecimal shipping = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        for (SellerPrice seller : sellers) {
            items = items.add(seller.subtotal());
            shipping = shipping.add(seller.shipping());
            discount = discount.add(seller.sellerDiscount()).add(seller.platformDiscount());
        }
        return new Totals(items, shipping, discount, items.add(shipping).subtract(discount));
    }
}
