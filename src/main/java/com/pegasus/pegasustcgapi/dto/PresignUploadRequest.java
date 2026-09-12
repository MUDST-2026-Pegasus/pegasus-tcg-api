package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.storage.UploadPurpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Asks for somewhere to put one file.
 *
 * @param purpose     what the file is for; it decides the folder, the permitted
 *                    types and the size limit.
 * @param contentType the browser's own {@code file.type}.
 * @param sizeBytes   the browser's own {@code file.size}, checked before a URL is
 *                    handed out so an oversized file is refused before it is sent.
 */
public record PresignUploadRequest(

        @NotNull
        UploadPurpose purpose,

        @NotBlank @Size(max = 100)
        String contentType,

        @NotNull @Positive
        Long sizeBytes) {
}
