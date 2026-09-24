package com.pegasus.pegasustcgapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload for updating the authenticated user's own profile.
 *
 * <p>{@code email} and {@code username} are deliberately absent; both are unique
 * identifiers that require dedicated verification flows to change.
 */
@Schema(description = "Payload for updating current user profile")
public record UpdateProfileRequest(

        @Schema(description = "User display name", example = "Card Master")
        @Size(max = 100)
        String displayName,

        @Schema(description = "Short biography or about me", example = "Collector of vintage cards")
        @Size(max = 1000)
        String bio,

        @Schema(description = "Contact phone number", example = "+66812345678")
        @Size(max = 20)
        @Pattern(regexp = "^[0-9+()\\-\\s]*$", message = "is not a valid phone number")
        String phone,

        @Schema(description = "Object key of uploaded avatar image from /uploads/presign", example = "avatars/2026/09/uuid.jpg")
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
