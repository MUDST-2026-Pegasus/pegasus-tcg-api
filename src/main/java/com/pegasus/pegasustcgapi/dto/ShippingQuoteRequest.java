package com.pegasus.pegasustcgapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

@Schema(description = "Selected items in the authenticated buyer's cart. Null or empty selects the entire cart. No address is needed for the current shipping tariff.")
public record ShippingQuoteRequest(List<@NotNull @Positive Long> cartItemIds) {
}
