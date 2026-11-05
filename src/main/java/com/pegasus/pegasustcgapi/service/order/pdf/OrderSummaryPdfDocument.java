package com.pegasus.pegasustcgapi.service.order.pdf;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Normalized, immutable input for the shared Order Summary PDF template.
 *
 * <p>The role-aware services in the next delivery map their own authorized order views into this
 * document. Keeping this type independent of controllers and persistence lets one renderer serve
 * Buyer, Seller, and Admin documents without widening any role's data access.
 */
public record OrderSummaryPdfDocument(
        OrderSummaryPdfRole role,
        String orderNumber,
        String sellerOrderNumber,
        String status,
        String currency,
        OffsetDateTime placedAt,
        OffsetDateTime paidAt,
        Party buyer,
        List<SellerSection> sellerSections,
        Shipping shipping,
        Payment payment,
        Totals totals,
        List<StatusEvent> statusHistory,
        OffsetDateTime generatedAt) {

    public OrderSummaryPdfDocument {
        role = Objects.requireNonNull(role, "role is required");
        orderNumber = required(orderNumber, "orderNumber");
        sellerOrderNumber = blankToNull(sellerOrderNumber);
        status = defaultText(status, "UNKNOWN");
        currency = required(currency, "currency");
        sellerSections = immutable(sellerSections);
        totals = totals == null ? Totals.empty() : totals;
        statusHistory = immutable(statusHistory);
        generatedAt = Objects.requireNonNull(generatedAt, "generatedAt is required");
    }

    public record Party(String displayName, String username) {
        public Party {
            displayName = defaultText(displayName, "Not available");
            username = blankToNull(username);
        }
    }

    public record SellerSection(String sellerName, String sellerOrderNumber, List<LineItem> items, Totals totals) {
        public SellerSection {
            sellerName = defaultText(sellerName, "Seller");
            sellerOrderNumber = blankToNull(sellerOrderNumber);
            items = immutable(items);
            totals = totals == null ? Totals.empty() : totals;
        }
    }

    public record LineItem(
            String productName,
            String variantLabel,
            String condition,
            String gameName,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal discount,
            BigDecimal lineTotal) {

        public LineItem {
            productName = defaultText(productName, "Unnamed item");
            variantLabel = blankToNull(variantLabel);
            condition = blankToNull(condition);
            gameName = blankToNull(gameName);
            quantity = Math.max(quantity, 0);
            unitPrice = money(unitPrice);
            discount = money(discount);
            lineTotal = money(lineTotal);
        }
    }

    public record Shipping(
            String recipientName,
            String address,
            String carrierName,
            String trackingNumber,
            String estimatedDelivery) {

        public Shipping {
            recipientName = blankToNull(recipientName);
            address = blankToNull(address);
            carrierName = blankToNull(carrierName);
            trackingNumber = blankToNull(trackingNumber);
            estimatedDelivery = blankToNull(estimatedDelivery);
        }

        public boolean hasAnyValue() {
            return recipientName != null || address != null || carrierName != null
                    || trackingNumber != null || estimatedDelivery != null;
        }
    }

    public record Payment(String method, String status, BigDecimal amount, OffsetDateTime paidAt) {
        public Payment {
            method = blankToNull(method);
            status = blankToNull(status);
            amount = money(amount);
        }

        public boolean hasAnyValue() {
            return method != null || status != null || amount.signum() != 0 || paidAt != null;
        }
    }

    public record Totals(
            BigDecimal itemsSubtotal,
            BigDecimal shippingFee,
            BigDecimal discountTotal,
            BigDecimal totalPaid,
            BigDecimal commissionAmount,
            BigDecimal sellerNetAmount) {

        public Totals {
            itemsSubtotal = money(itemsSubtotal);
            shippingFee = money(shippingFee);
            discountTotal = money(discountTotal);
            totalPaid = money(totalPaid);
            commissionAmount = money(commissionAmount);
            sellerNetAmount = money(sellerNetAmount);
        }

        public static Totals empty() {
            return new Totals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    public record StatusEvent(String status, OffsetDateTime occurredAt, String note) {
        public StatusEvent {
            status = defaultText(status, "UNKNOWN");
            note = blankToNull(note);
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static String required(String value, String field) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String defaultText(String value, String fallback) {
        String normalized = blankToNull(value);
        return normalized == null ? fallback : normalized;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
