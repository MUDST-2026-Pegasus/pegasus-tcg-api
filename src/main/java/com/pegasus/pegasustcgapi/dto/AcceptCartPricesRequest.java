package com.pegasus.pegasustcgapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

@Schema(description = "Accept current prices for these cart items. Omit cartItemIds to accept all changed prices; an empty list accepts none.")
public record AcceptCartPricesRequest(List<@NotNull @Positive Long> cartItemIds) {
}
