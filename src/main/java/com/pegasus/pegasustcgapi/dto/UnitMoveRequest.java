package com.pegasus.pegasustcgapi.dto;

import jakarta.validation.constraints.Positive;

/**
 * Where a card goes. Its UUID, cost and history go with it.
 *
 * @param listingId another listing of the same printing, condition and seller —
 *                  which is how one card of a group gets its own price; null
 *                  takes the card off sale and back into the seller's hands
 */
public record UnitMoveRequest(@Positive Long listingId) {
}
