package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.CollectionItem;
import java.math.BigDecimal;

/**
 * A card as a visitor to someone's profile sees it.
 *
 * <p>Marking a card public means "show that I have this", not "show what I paid"
 * — so the price, the date, the personal note and the slab's certificate number
 * are left out of this shape entirely, rather than blanked in a shared one where
 * a later field could leak by default. The source is left out too: whether a
 * card was bought here is the owner's business.
 */
public record PublicCollectionItemResponse(
        long id,
        CollectionCard card,
        CardCondition condition,
        int quantity,
        String gradingCompany,
        BigDecimal gradeValue,
        String photoUrl) {

    public static PublicCollectionItemResponse of(CollectionItem item, CollectionCard card, String photoUrl) {
        return new PublicCollectionItemResponse(item.id(), card, item.condition(), item.quantity(),
                item.gradingCompany(), item.gradeValue(), photoUrl);
    }
}
