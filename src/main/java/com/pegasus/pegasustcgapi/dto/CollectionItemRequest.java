package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.repository.CollectionItemRepository.CollectionItemFields;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A card being added to, or edited in, the caller's own collection.
 *
 * <p>The same body creates and replaces, so a client never has to know which
 * fields an update is allowed to omit.
 *
 * @param catalogVariantId the printing, picked from the shared catalogue. There is
 *                         no free-text card name [RQ-3].
 * @param acquiredPrice    what one card cost, not the whole row
 * @param imageKey         from {@code POST /uploads/presign} with purpose
 *                         COLLECTION_IMAGE, after the file was uploaded
 */
public record CollectionItemRequest(

        @NotNull @Positive
        Long catalogVariantId,

        CardCondition condition,

        @Min(1) @Max(9999)
        Integer quantity,

        @Size(max = 20)
        String gradingCompany,

        @DecimalMin("0.5") @DecimalMax("10.0") @Digits(integer = 2, fraction = 1)
        BigDecimal gradeValue,

        @Size(max = 64)
        String certNumber,

        @DecimalMin("0.00") @Digits(integer = 12, fraction = 2)
        BigDecimal acquiredPrice,

        @PastOrPresent
        OffsetDateTime acquiredAt,

        @Size(max = 500)
        String imageKey,

        @Size(max = 500)
        String personalNote,

        /** Absent means private: showing a card is something the owner opts into. */
        Boolean publicItem) {

    public CollectionItemFields toFields() {
        return new CollectionItemFields(
                catalogVariantId,
                condition == null ? CardCondition.NM : condition,
                quantity == null ? 1 : quantity,
                blankToNull(gradingCompany),
                gradeValue,
                blankToNull(certNumber),
                acquiredPrice,
                acquiredAt,
                blankToNull(imageKey),
                blankToNull(personalNote),
                publicItem != null && publicItem);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
