package com.pegasus.pegasustcgapi.model;

import java.time.OffsetDateTime;

/**
 * A user's or guest's shopping cart.
 */
public record Cart(
        long id,
        Long userId,
        String sessionKey,
        String currency,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime expiresAt) {
}
