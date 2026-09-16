package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.repository.CatalogImageRepository.ImageFields;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Attaches an already-uploaded file to a catalogue entry.
 *
 * @param imageKey          the key returned by {@code POST /uploads/presign}, after
 *                          the client has PUT the file to the URL that came with it
 * @param catalogVariantId  null means the art is shared by every variant, which is
 *                          the usual case; set it for art that is specific to one printing
 * @param primary           the first image of a product becomes primary whether or
 *                          not this says so, since something has to be
 */
public record CatalogImageRequest(

        @NotBlank @Size(max = 500)
        String imageKey,

        Long catalogVariantId,

        @Size(max = 255)
        String altText,

        Short sortOrder,

        Boolean primary) {

    public ImageFields toFields() {
        return new ImageFields(catalogVariantId, imageKey, altText,
                sortOrder == null ? 0 : sortOrder,
                primary != null && primary);
    }
}
