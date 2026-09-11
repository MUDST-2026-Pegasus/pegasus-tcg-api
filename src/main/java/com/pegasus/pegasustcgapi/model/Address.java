package com.pegasus.pegasustcgapi.model;

import java.time.OffsetDateTime;

/**
 * One entry in a buyer's address book [RQ-11].
 *
 * <p>Never hard-deleted, because orders point at it — and an order also keeps its
 * own snapshot, so editing an address here never rewrites a past delivery.
 */
public record Address(
        long id,
        long userId,
        String label,
        String recipientName,
        String phone,
        String line1,
        String line2,
        String subdistrict,
        String district,
        String province,
        String postalCode,
        String countryCode,
        boolean defaultShipping,
        boolean defaultBilling,
        OffsetDateTime createdAt) {
}
