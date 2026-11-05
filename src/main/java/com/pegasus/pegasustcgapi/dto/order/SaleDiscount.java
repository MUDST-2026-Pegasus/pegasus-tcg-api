package com.pegasus.pegasustcgapi.dto.order;

import com.pegasus.pegasustcgapi.jooq.tables.records.OrderItemRecord;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * What a sale price took off an order line, and who pays for it [TCG-680]. Pegasus
 * pays the shop its campaigns' part at release (SALE_SUBSIDY); a shop's own sale
 * the shop simply sold for less.
 *
 * @param saleId   the sale the line sold under; null off sale
 * @param perUnit  off each card
 * @param platform off the whole line, paid by Pegasus
 * @param seller   off the whole line, given by the shop
 */
@Schema(description = "A line's sale discount, split by who pays for it")
public record SaleDiscount(@Schema(nullable = true) Long saleId, BigDecimal perUnit, BigDecimal platform,
                           BigDecimal seller) {

    public static final SaleDiscount NONE =
            new SaleDiscount(null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    public static SaleDiscount of(OrderItemRecord oi) {
        BigDecimal perUnit = oi.getSaleDiscount() == null ? BigDecimal.ZERO : oi.getSaleDiscount();
        if (oi.getSaleId() == null || perUnit.signum() == 0) {
            return NONE;
        }
        BigDecimal line = perUnit.multiply(BigDecimal.valueOf(oi.getQuantity()));
        boolean pegasus = "PLATFORM".equals(oi.getSaleFundedBy());
        return new SaleDiscount(oi.getSaleId(), perUnit, pegasus ? line : BigDecimal.ZERO,
                pegasus ? BigDecimal.ZERO : line);
    }

    /** The asking price per card the line sold against. */
    public static BigDecimal listPrice(OrderItemRecord oi) {
        return oi.getListPrice() != null ? oi.getListPrice() : oi.getUnitPrice();
    }
}
