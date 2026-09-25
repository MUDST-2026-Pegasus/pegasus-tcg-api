package com.pegasus.pegasustcgapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload for updating the authenticated user's own profile.
 *
 * <p>{@code email} and {@code username} are deliberately absent; both are unique
 * identifiers that require dedicated verification flows to change.
 *
 * <p>Null-semantics: a field set to {@code null} (or omitted) means "keep the
 * current value". A field set to {@code ""} means "clear the value" (applies to
 * {@code bio}, {@code phone}, and {@code avatarUrl}; {@code displayName} must
 * contain at least one non-whitespace character when present).
 */
@Schema(description = "Payload for updating current user profile")
public record UpdateProfileRequest(

        @Schema(description = "User display name (null = keep, must not be blank when present)",
                example = "Card Master")
        @Size(max = 100)
        @Pattern(regexp = ".*\\S.*", message = "must not be blank")
        String displayName,

        @Schema(description = "Short biography or about me (null = keep, empty = clear)",
                example = "Collector of vintage cards")
        @Size(max = 1000)
        String bio,

        @Schema(description = "Contact phone number (null = keep, empty = clear)",
                example = "+66812345678")
        @Size(max = 20)
        @Pattern(regexp = "^(\\+?\\d[\\d()\\-\\s]*)?$", message = "is not a valid phone number")
        String phone,

        @Schema(description = "Object key of uploaded avatar image from /uploads/presign (null = keep, empty = clear)",
                example = "avatars/2026/09/uuid.jpg")
        @Size(max = 500)
        String avatarUrl) {

    public String cleanDisplayName() {
        return blankToNull(displayName);
    }

    public String cleanBio() {
        return blankToNull(bio);
    }

    public String cleanPhone() {
        return blankToNull(phone);
    }

    public String cleanAvatarUrl() {
        return blankToNull(avatarUrl);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
