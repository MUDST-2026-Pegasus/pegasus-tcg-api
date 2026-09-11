package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.repository.ShippingOptionRepository.OptionFields;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * A delivery choice the seller prices themselves [RQ-12].
 *
 * @param baseFee       charged once per order
 * @param perItemFee    charged for every card after the first
 * @param freeThreshold order value at or above which shipping is free; null means never
 */
public record ShippingOptionRequest(

        @NotBlank @Size(max = 100)
        String name,

        @Size(max = 32)
        String carrierCode,

        @NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2)
        BigDecimal baseFee,

        @NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2)
        BigDecimal perItemFee,

        @DecimalMin("0.01") @Digits(integer = 12, fraction = 2)
        BigDecimal freeThreshold,

        Short estDaysMin,
        Short estDaysMax,
        Boolean active,
        Short displayOrder) {

    public OptionFields toFields() {
        return new OptionFields(name, carrierCode, baseFee, perItemFee, freeThreshold,
                estDaysMin, estDaysMax,
                active == null || active,
                displayOrder == null ? 0 : displayOrder);
    }
}
