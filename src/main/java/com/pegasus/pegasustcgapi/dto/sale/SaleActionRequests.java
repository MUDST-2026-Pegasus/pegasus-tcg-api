package com.pegasus.pegasustcgapi.dto.sale;

import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.ScopeType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** Small bodies for the admin sale actions that are not the full form. */
public final class SaleActionRequests {

    private SaleActionRequests() {
    }

    /** @param reason required when pausing a shop's sale; shown to the shop */
    public record Pause(@Size(max = 255) String reason, Boolean notifySeller) {

        public boolean notifySellerOrFalse() {
            return Boolean.TRUE.equals(notifySeller);
        }
    }

    public record Resume(Boolean notifySeller) {

        public boolean notifySellerOrFalse() {
            return Boolean.TRUE.equals(notifySeller);
        }
    }

    /**
     * The whole home page in display order: at most one flash sale, then campaign
     * banners. Sales left out stop being featured.
     */
    public record HomeOrder(@NotNull @Size(max = 8) List<@NotNull Long> saleIds) {
    }

    /** The scope part of the form (SAL-A2), counted before the sale is saved. */
    public record PreviewScope(
            @NotNull ScopeType scopeType,
            List<@NotNull Long> scopeIds,
            List<@NotNull CardCondition> conditions,
            @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal minUnitPrice) {
    }
}
