package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.SellerProfile;
import com.pegasus.pegasustcgapi.model.SellerStatus;
import java.time.OffsetDateTime;

/**
 * The selling side of an account. No shop name or logo: what a buyer sees comes
 * from the user profile, which is the storefront [RQ-2].
 *
 * @param canPublish the answer to "may I list something right now"
 */
public record SellerProfileResponse(
        long id,
        long userId,
        SellerStatus status,
        boolean canPublish,
        OffsetDateTime verifiedAt,
        String suspendedReason,
        short handlingDays,
        boolean vacationMode,
        boolean autoAcceptOrders,
        OffsetDateTime createdAt) {

    public static SellerProfileResponse from(SellerProfile p) {
        return new SellerProfileResponse(p.id(), p.userId(), p.status(), p.canPublish(),
                p.verifiedAt(), p.suspendedReason(), p.handlingDays(), p.vacationMode(),
                p.autoAcceptOrders(), p.createdAt());
    }
}
