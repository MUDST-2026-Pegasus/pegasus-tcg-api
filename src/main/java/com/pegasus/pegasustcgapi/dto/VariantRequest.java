package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.CardEdition;
import com.pegasus.pegasustcgapi.model.CardFinish;
import com.pegasus.pegasustcgapi.repository.CatalogVariantRepository.VariantFields;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * One printing of a catalogued card — the row listings and prices point at [RQ-5].
 *
 * @param sku          optional. Left out, it is built from the game, set, card
 *                     number, language and finish, so SKUs read alike whoever
 *                     entered the card. On update, left out means keep the current one.
 * @param printingNote part of the identity, not a comment: "Alt Art" and a plain
 *                     print of the same card are two variants
 */
public record VariantRequest(

        @Size(max = 64)
        @Pattern(regexp = "^[A-Za-z0-9_-]*$",
                message = "may only contain letters, digits, hyphens and underscores")
        String sku,

        @NotBlank @Size(max = 10)
        @Pattern(regexp = "^[A-Za-z]{2,10}$", message = "must be a language code such as EN or JP")
        String languageCode,

        CardFinish finish,

        CardEdition edition,

        @Size(max = 100)
        String printingNote,

        @Size(max = 64)
        String barcode,

        @Size(max = 500)
        String imageUrl,

        Boolean active) {

    public VariantFields toFields() {
        return new VariantFields(sku, languageCode,
                finish == null ? CardFinish.NORMAL : finish,
                edition == null ? CardEdition.NOT_APPLICABLE : edition,
                printingNote, barcode, imageUrl,
                active == null || active);
    }
}
