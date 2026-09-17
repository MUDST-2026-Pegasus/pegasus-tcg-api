package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.ListingStatus;
import jakarta.validation.constraints.NotNull;

/**
 * @param status ACTIVE to publish or resume, PAUSED, or DELISTED to close for
 *               good. SOLD_OUT and BLOCKED are not the seller's to ask for.
 */
public record ListingStatusRequest(@NotNull ListingStatus status) {
}
