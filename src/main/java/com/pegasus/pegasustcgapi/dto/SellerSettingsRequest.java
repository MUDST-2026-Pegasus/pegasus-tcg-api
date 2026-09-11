package com.pegasus.pegasustcgapi.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;

/**
 * @param handlingDays days the seller promises to take before posting
 * @param vacationMode hides all of this seller's listings at once
 */
public record SellerSettingsRequest(

        @NotNull @Min(0) @Max(30)
        Short handlingDays,

        Boolean vacationMode,
        Boolean autoAcceptOrders) {

    public boolean onVacation() {
        return Boolean.TRUE.equals(vacationMode);
    }

    /** Defaults to on, which is what a seller expects when they never touched the switch. */
    public boolean acceptsOrdersAutomatically() {
        return autoAcceptOrders == null || autoAcceptOrders;
    }
}
