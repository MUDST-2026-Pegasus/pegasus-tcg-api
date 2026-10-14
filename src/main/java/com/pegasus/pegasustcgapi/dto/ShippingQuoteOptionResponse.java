package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.ShippingOption;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "One active shipping option, priced by the same ShippingOption.feeFor calculation used at checkout")
public record ShippingQuoteOptionResponse(
        long id,
        String name,
        String carrierCode,
        BigDecimal baseFee,
        BigDecimal perItemFee,
        BigDecimal freeThreshold,
        Short estDaysMin,
        Short estDaysMax,
        BigDecimal fee,
        @Schema(description = "True only when this basket meets the option's free-shipping threshold") boolean free,
        @Schema(description = "Additional item spend needed to reach free shipping; null when reached or no threshold exists", nullable = true) BigDecimal amountToFree) {

    public static ShippingQuoteOptionResponse from(ShippingOption option, int itemCount, BigDecimal subtotal) {
        boolean free = option.freeThreshold() != null && subtotal.compareTo(option.freeThreshold()) >= 0;
        BigDecimal amountToFree = option.freeThreshold() != null && !free
                ? option.freeThreshold().subtract(subtotal) : null;
        return new ShippingQuoteOptionResponse(option.id(), option.name(), option.carrierCode(),
                option.baseFee(), option.perItemFee(), option.freeThreshold(), option.estDaysMin(),
                option.estDaysMax(), option.feeFor(itemCount, subtotal), free, amountToFree);
    }
}
