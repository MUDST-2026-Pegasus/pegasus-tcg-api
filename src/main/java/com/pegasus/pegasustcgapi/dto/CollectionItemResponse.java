package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.CollectionItem;
import com.pegasus.pegasustcgapi.model.CollectionSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A row of the caller's own collection — everything, including what they paid and
 * what they wrote about it. Only ever returned to the owner; the public shape is
 * {@link PublicCollectionItemResponse}.
 *
 * @param photoUrl the owner's own photo, signed and short-lived
 */
public record CollectionItemResponse(
        long id,
        CollectionCard card,
        CardCondition condition,
        int quantity,
        CollectionSource source,
        String gradingCompany,
        BigDecimal gradeValue,
        String certNumber,
        BigDecimal acquiredPrice,
        OffsetDateTime acquiredAt,
        String imageKey,
        String photoUrl,
        String personalNote,
        boolean publicItem,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static CollectionItemResponse of(CollectionItem item, CollectionCard card, String photoUrl) {
        return new CollectionItemResponse(item.id(), card, item.condition(), item.quantity(),
                item.source(), item.gradingCompany(), item.gradeValue(), item.certNumber(),
                item.acquiredPrice(), item.acquiredAt(), item.imageKey(), photoUrl,
                item.personalNote(), item.publicItem(), item.createdAt(), item.updatedAt());
    }
}
