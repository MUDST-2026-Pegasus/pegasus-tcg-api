package com.pegasus.pegasustcgapi.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * What the cart or checkout page would cost with these codes. Nothing is reserved.
 *
 * @param cartItemIds            the ticked lines; null or empty means the whole cart
 * @param shippingOptionBySeller the chosen delivery per shop; left out, each shop's
 *                               default is used and the totals leave shipping out
 * @param autoPick               ignore the codes sent and pick the best from the
 *                               buyer's collected codes and the public ones
 */
public record PromoPreviewRequest(
        List<@NotNull @Positive Long> cartItemIds,
        Map<Long, Long> shippingOptionBySeller,
        @Size(max = 20) String platformCode,
        @Size(max = MAX_SHOP_CODES) Map<Long, @Size(max = 20) String> sellerCodes,
        Boolean autoPick) {

    /** More shops than any cart holds; a cap so one request cannot try thousands of codes. */
    public static final int MAX_SHOP_CODES = 50;

    public boolean autoPickOrFalse() {
        return Boolean.TRUE.equals(autoPick);
    }
}
