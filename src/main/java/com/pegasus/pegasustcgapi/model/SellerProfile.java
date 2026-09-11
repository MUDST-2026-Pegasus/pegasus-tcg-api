package com.pegasus.pegasustcgapi.model;

import java.time.OffsetDateTime;

/**
 * The selling side of an account — not a shop. There is no name, logo or slug
 * here on purpose: everything a buyer sees comes from {@code user_account}, so
 * the profile at {@code /u/{username}} is also the storefront [RQ-2].
 *
 * @param vacationMode hides every listing of this seller at once, without
 *        touching any {@code listing.status}.
 */
public record SellerProfile(
        long id,
        long userId,
        SellerStatus status,
        OffsetDateTime verifiedAt,
        String suspendedReason,
        short handlingDays,
        boolean vacationMode,
        boolean autoAcceptOrders,
        OffsetDateTime createdAt) {

    /** The one condition that lets a listing go live. */
    public boolean canPublish() {
        return status == SellerStatus.VERIFIED;
    }
}
