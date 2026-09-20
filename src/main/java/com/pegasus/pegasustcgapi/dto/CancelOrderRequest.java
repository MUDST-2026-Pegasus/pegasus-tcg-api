package com.pegasus.pegasustcgapi.dto;

/**
 * Request payload for buyer order cancellation.
 */
public record CancelOrderRequest(
        String reason
) {
}
