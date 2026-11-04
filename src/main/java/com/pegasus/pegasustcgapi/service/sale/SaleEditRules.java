package com.pegasus.pegasustcgapi.service.sale;

import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.sale.PriceSale;
import com.pegasus.pegasustcgapi.repository.sale.PriceSaleRepository.NewPriceSale;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;

/**
 * What may change on a sale once something has sold at its price [TCG-677]:
 * the name, the end date, a higher budget and a higher per-listing quota — plus
 * how it is shown (banner, home page), which prices nothing. Everything that
 * decides a price must come back unchanged, so past orders still match the sale
 * they point at; anything else is {@code SALE_INVALID}.
 */
final class SaleEditRules {

    private SaleEditRules() {
    }

    static void checkAfterOrders(PriceSale c, NewPriceSale n) {
        boolean locked = !c.slug().equals(n.slug())
                || c.saleType() != n.saleType()
                || c.discountType() != n.discountType()
                || !sameAmount(c.discountValue(), n.discountValue())
                || !sameAmount(c.maxDiscountPerUnit(), n.maxDiscountPerUnit())
                || c.scopeType() != n.scopeType()
                || !Set.copyOf(c.scopeIds()).equals(Set.copyOf(n.scopeIds()))
                || !Set.copyOf(c.conditions()).equals(Set.copyOf(n.conditions()))
                || !sameAmount(c.minUnitPrice(), n.minUnitPrice())
                || !Objects.equals(c.perBuyerLimit(), n.perBuyerLimit())
                || !c.startsAt().isEqual(n.startsAt())
                || !notLower(c.budgetCap(), n.budgetCap())
                || !notLower(c.perListingQuota(), n.perListingQuota());
        if (locked) {
            throw new BadRequestException(ErrorCode.SALE_INVALID, "Cards have sold at this sale's price: only its "
                    + "name, end date, banner, a higher budget and a higher quota can change");
        }
    }

    /** Null is unlimited, so it is never lower; nothing finite beats it. */
    private static <T extends Comparable<T>> boolean notLower(T before, T after) {
        if (after == null) return true;
        return before != null && after.compareTo(before) >= 0;
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }
}
