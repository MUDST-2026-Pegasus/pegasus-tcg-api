package com.pegasus.pegasustcgapi.model;

import java.time.OffsetDateTime;

/**
 * A photo of the actual cards, taken by the seller [RQ-8]. The official art is
 * the catalogue's.
 *
 * @param imageKey an object key, never a public URL
 */
public record ListingImage(
        long id,
        long listingId,
        String imageKey,
        short sortOrder,
        boolean primary,
        OffsetDateTime createdAt) {
}
