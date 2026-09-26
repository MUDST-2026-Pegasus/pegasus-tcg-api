package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.support.AuthUserBuilder.anActiveUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.pegasus.pegasustcgapi.dto.UpdateProfileRequest;
import com.pegasus.pegasustcgapi.dto.UserResponse;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.AuthUser;
import com.pegasus.pegasustcgapi.model.RoleCode;
import com.pegasus.pegasustcgapi.repository.UserRepository;
import com.pegasus.pegasustcgapi.storage.StorageService;
import com.pegasus.pegasustcgapi.storage.UploadPurpose;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserProfileService")
class UserProfileServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthService authService;

    @Mock
    private StorageService storageService;

    @InjectMocks
    private UserProfileService userProfileService;

    // ── Success path ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Updates displayName, bio, phone, avatarUrl and returns presigned URL")
    void updateProfileSuccess() {
        long userId = 1L;
        AuthUser existing = anActiveUser().withId(userId)
                .withDisplayName("Old Name").withBio("Old Bio").withPhone("+66800000000").build();
        AuthUser updated = anActiveUser().withId(userId)
                .withDisplayName("New Name").withBio("New Bio")
                .withPhone("+66899999999").withAvatarUrl("avatars/2026/09/avatar.png").build();

        given(userRepository.findById(userId)).willReturn(Optional.of(existing));
        given(authService.loadWithRoles(userId)).willReturn(updated.withRoles(Set.of(RoleCode.BUYER)));
        given(storageService.presignDownload("avatars/2026/09/avatar.png"))
                .willReturn("https://cdn.example.com/avatars/2026/09/avatar.png?sig=xxx");

        UpdateProfileRequest request = new UpdateProfileRequest(
                "New Name", "New Bio", "+66899999999", "avatars/2026/09/avatar.png");

        UserResponse response = userProfileService.updateProfile(userId, request);

        // Verify actual field values — not just isNotNull
        assertThat(response.displayName()).isEqualTo("New Name");
        assertThat(response.bio()).isEqualTo("New Bio");
        assertThat(response.phone()).isEqualTo("+66899999999");
        assertThat(response.avatarUrl()).isEqualTo("https://cdn.example.com/avatars/2026/09/avatar.png?sig=xxx");

        verify(storageService).requireUploadedFor(UploadPurpose.AVATAR_IMAGE, "avatars/2026/09/avatar.png");
        verify(userRepository).updateProfileSelective(eq(userId), any());
    }

    // ── Selective update ────────────────────────────────────────────────

    @Test
    @DisplayName("Only sets the fields that were provided in the request")
    @SuppressWarnings("unchecked")
    void updateProfileSelectiveUpdate() {
        long userId = 1L;
        AuthUser existing = anActiveUser().withId(userId).build();
        AuthUser updated = anActiveUser().withId(userId).withBio("New Bio").build();

        given(userRepository.findById(userId)).willReturn(Optional.of(existing));
        given(authService.loadWithRoles(userId)).willReturn(updated.withRoles(Set.of(RoleCode.BUYER)));

        // Only send bio — displayName, phone, avatarUrl omitted (null)
        UpdateProfileRequest request = new UpdateProfileRequest(null, "New Bio", null, null);
        userProfileService.updateProfile(userId, request);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(userRepository).updateProfileSelective(eq(userId), captor.capture());

        Map<String, Object> changes = captor.getValue();
        assertThat(changes).containsOnlyKeys("bio");
        assertThat(changes.get("bio")).isEqualTo("New Bio");
    }

    // ── User not found ──────────────────────────────────────────────────

    @Test
    @DisplayName("User not found throws USER_NOT_FOUND")
    void updateProfileUserNotFoundThrows() {
        given(userRepository.findById(999L)).willReturn(Optional.empty());

        UpdateProfileRequest request = new UpdateProfileRequest("New Name", null, null, null);

        assertThatThrownBy(() -> userProfileService.updateProfile(999L, request))
                .isInstanceOf(NotFoundException.class)
                .satisfies(e -> assertThat(((NotFoundException) e).errorCode())
                        .isEqualTo(ErrorCode.USER_NOT_FOUND));
    }

    // ── Avatar ownership ────────────────────────────────────────────────

    @Test
    @DisplayName("Avatar key used by another user throws IMAGE_KEY_IN_USE")
    void updateProfileAvatarKeyUsedByAnotherThrows() {
        long userId = 1L;
        AuthUser existing = anActiveUser().withId(userId).build();
        given(userRepository.findById(userId)).willReturn(Optional.of(existing));
        given(userRepository.avatarKeyUsedByAnother("avatars/stolen.png", userId)).willReturn(true);

        UpdateProfileRequest request = new UpdateProfileRequest(null, null, null, "avatars/stolen.png");

        assertThatThrownBy(() -> userProfileService.updateProfile(userId, request))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).errorCode())
                        .isEqualTo(ErrorCode.IMAGE_KEY_IN_USE));
    }

    // ── Unchanged avatar skips validation ────────────────────────────────

    @Test
    @DisplayName("Unchanged avatarUrl does not call storage.requireUploadedFor")
    void updateProfileUnchangedAvatarSkipsValidation() {
        long userId = 1L;
        AuthUser existing = anActiveUser().withId(userId)
                .withAvatarUrl("avatars/2026/09/same.png").build();

        given(userRepository.findById(userId)).willReturn(Optional.of(existing));
        given(authService.loadWithRoles(userId)).willReturn(existing.withRoles(Set.of(RoleCode.BUYER)));
        given(storageService.presignDownload("avatars/2026/09/same.png"))
                .willReturn("https://cdn.example.com/presigned");

        UpdateProfileRequest request = new UpdateProfileRequest(
                null, null, null, "avatars/2026/09/same.png");

        userProfileService.updateProfile(userId, request);

        verify(storageService, never()).requireUploadedFor(any(), any());
    }

    // ── Clearing optional fields ─────────────────────────────────────────

    @Test
    @DisplayName("Empty string clears bio, phone, and avatar")
    @SuppressWarnings("unchecked")
    void updateProfileClearsOptionalFields() {
        long userId = 1L;
        AuthUser existing = anActiveUser().withId(userId)
                .withBio("Old Bio").withPhone("+66800000000")
                .withAvatarUrl("avatars/old.png").build();

        given(userRepository.findById(userId)).willReturn(Optional.of(existing));
        given(authService.loadWithRoles(userId)).willReturn(existing.withRoles(Set.of(RoleCode.BUYER)));

        UpdateProfileRequest request = new UpdateProfileRequest(null, "", "", "");
        userProfileService.updateProfile(userId, request);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(userRepository).updateProfileSelective(eq(userId), captor.capture());

        Map<String, Object> changes = captor.getValue();
        assertThat(changes.get("bio")).isNull();
        assertThat(changes.get("phone")).isNull();
        assertThat(changes.get("avatarUrl")).isNull();
        verify(storageService, never()).requireUploadedFor(any(), any());
    }

    // ── Old avatar file deletion ─────────────────────────────────────────

    @Test
    @DisplayName("Old avatar file is deleted from storage when avatar changes")
    void updateProfileDeletesOldAvatar() {
        long userId = 1L;
        AuthUser existing = anActiveUser().withId(userId)
                .withAvatarUrl("avatars/old.png").build();
        AuthUser updated = anActiveUser().withId(userId)
                .withAvatarUrl("avatars/new.png").build();

        given(userRepository.findById(userId)).willReturn(Optional.of(existing));
        given(authService.loadWithRoles(userId)).willReturn(updated.withRoles(Set.of(RoleCode.BUYER)));
        given(storageService.presignDownload(anyString())).willReturn("https://cdn.example.com/presigned");

        UpdateProfileRequest request = new UpdateProfileRequest(null, null, null, "avatars/new.png");
        userProfileService.updateProfile(userId, request);

        verify(storageService).delete("avatars/old.png");
    }
}
