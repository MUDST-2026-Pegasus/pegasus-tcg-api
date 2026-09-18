package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.ListingUnit;
import com.pegasus.pegasustcgapi.model.ListingUnitStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One physical card in the caller's stock. Identified by its UUID; the
 * sequential id is never sent, so card numbers cannot be walked.
 *
 * @param listingId null while the card is in hand
 */
public record ListingUnitResponse(
        UUID publicUid,
        Long listingId,
        ListingCard card,
        CardCondition condition,
        ListingUnitStatus status,
        String certNumber,
        BigDecimal acquisitionCost,
        OffsetDateTime acquiredAt,
        String unitNote,
        OffsetDateTime soldAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static ListingUnitResponse of(ListingUnit u, ListingCard card) {
        return new ListingUnitResponse(u.publicUid(), u.listingId(), card, u.condition(), u.status(),
                u.certNumber(), u.acquisitionCost(), u.acquiredAt(), u.unitNote(), u.soldAt(),
                u.createdAt(), u.updatedAt());
    }
}
