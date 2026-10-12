package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.dto.UpdateProfileRequest;
import com.pegasus.pegasustcgapi.dto.UserResponse;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.AuthUser;
import com.pegasus.pegasustcgapi.repository.UserRepository;
import com.pegasus.pegasustcgapi.storage.StorageService;
import com.pegasus.pegasustcgapi.storage.UploadPurpose;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Manages updates to the authenticated user's own profile.
 *
 * <p>The method is deliberately <em>not</em> {@code @Transactional}: avatar
 * validation hits MinIO over the network, and holding a database connection
 * during that call would waste the pool. The single {@code UPDATE} statement
 * in the repository is atomic on its own.
 */
@Service
public class UserProfileService {

    private final UserRepository users;
    private final AuthService authService;
    private final StorageService storage;

    public UserProfileService(UserRepository users, AuthService authService, StorageService storage) {
        this.users = users;
        this.authService = authService;
        this.storage = storage;
    }

    /**
     * Updates the profile of the given user. Only fields present in the request
     * are written (selective update), so two concurrent requests touching
     * different fields no longer overwrite each other.
     */
    public UserResponse updateProfile(long userId, UpdateProfileRequest request) {
        // ── Phase 1: read current state + validate avatar (may call MinIO) ──
        AuthUser current = users.findById(userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND, "User not found"));

        String oldAvatarKey = current.avatarUrl();
        String resolvedAvatar = resolveAvatar(userId, current, request);

        // ── Phase 2: selective DB update (single atomic statement, no MinIO) ──
        Map<String, Object> changes = new LinkedHashMap<>();
        if (request.displayName() != null) {
            changes.put("displayName", request.cleanDisplayName());
        }
        if (request.bio() != null) {
            changes.put("bio", request.cleanBio());
        }
        if (request.phone() != null) {
            changes.put("phone", request.cleanPhone());
        }
        if (request.avatarUrl() != null) {
            changes.put("avatarUrl", resolvedAvatar);
        }
        users.updateProfileSelective(userId, changes);

        // ── Phase 3: clean up old avatar file if it changed ──
        boolean avatarChanged = request.avatarUrl() != null
                && !Objects.equals(oldAvatarKey, resolvedAvatar);
        if (avatarChanged && oldAvatarKey != null) {
            try {
                storage.delete(oldAvatarKey);
            } catch (Exception ignored) {
                // Best-effort cleanup; the old file is orphaned but not harmful.
            }
        }

        // ── Phase 4: reload and return with presigned avatar ──
        AuthUser updated = authService.loadWithRoles(userId);
        return UserResponse.from(updated, presign(updated.avatarUrl()));
    }

    // ── Avatar resolution ────────────────────────────────────────────────

    /**
     * Determines the new avatar key, validating against storage and ownership
     * only when the key actually changes.
     */
    private String resolveAvatar(long userId, AuthUser current, UpdateProfileRequest request) {
        if (request.avatarUrl() == null) {
            return current.avatarUrl();          // not provided → keep current
        }
        String cleanAvatar = request.cleanAvatarUrl();
        if (cleanAvatar == null) {
            return null;                          // blank → clear
        }
        if (cleanAvatar.equals(current.avatarUrl())) {
            return cleanAvatar;                   // unchanged → skip validation
        }
        // New key → validate upload exists and prefix matches
        storage.requireUploadedFor(UploadPurpose.AVATAR_IMAGE, cleanAvatar);
        // Ensure the key is not already claimed by another user
        if (users.avatarKeyUsedByAnother(cleanAvatar, userId)) {
            throw new ConflictException(ErrorCode.IMAGE_KEY_IN_USE,
                    "This avatar image is already used by another account");
        }
        return cleanAvatar;
    }

    private String presign(String objectKey) {
        return objectKey == null ? null : storage.presignDownload(objectKey);
    }
}
