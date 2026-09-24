package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.support.AuthUserBuilder.anActiveUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.pegasus.pegasustcgapi.dto.UpdateProfileRequest;
import com.pegasus.pegasustcgapi.dto.UserResponse;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.AuthUser;
import com.pegasus.pegasustcgapi.model.RoleCode;
import com.pegasus.pegasustcgapi.repository.UserRepository;
import com.pegasus.pegasustcgapi.storage.StorageService;
import com.pegasus.pegasustcgapi.storage.UploadPurpose;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserProfileService")
class UserProfileServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleService roleService;

    @Mock
    private StorageService storageService;

    @InjectMocks
    private UserProfileService userProfileService;

    @Test
    @DisplayName("Successfully updates displayName, bio, phone, and avatarUrl")
    void updateProfileSuccess() {
        long userId = 1L;
        AuthUser existingUser = anActiveUser().withId(userId).build();
        AuthUser updatedUser = anActiveUser().withId(userId).build();

        given(userRepository.findById(userId))
                .willReturn(Optional.of(existingUser))
                .willReturn(Optional.of(updatedUser));
        given(roleService.rolesOf(userId)).willReturn(Set.of(RoleCode.BUYER));

        UpdateProfileRequest request = new UpdateProfileRequest(
                "New Name",
                "New Bio",
                "+66899999999",
                "avatars/2026/09/avatar.png"
        );

        UserResponse response = userProfileService.updateProfile(userId, request);

        assertThat(response).isNotNull();
        verify(storageService).requireUploadedFor(UploadPurpose.AVATAR_IMAGE, "avatars/2026/09/avatar.png");
        verify(userRepository).updateProfile(userId, "New Name", "New Bio", "+66899999999", "avatars/2026/09/avatar.png");
    }

    @Test
    @DisplayName("Blank displayName throws VALIDATION_FAILED")
    void updateProfileBlankDisplayNameThrows() {
        long userId = 1L;
        AuthUser existingUser = anActiveUser().withId(userId).build();
        given(userRepository.findById(userId)).willReturn(Optional.of(existingUser));

        UpdateProfileRequest request = new UpdateProfileRequest("   ", "Bio", "phone", null);

        assertThatThrownBy(() -> userProfileService.updateProfile(userId, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiException = (ApiException) e;
                    assertThat(apiException.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                });

        verify(userRepository, never()).updateProfile(anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("User not found throws USER_NOT_FOUND")
    void updateProfileUserNotFoundThrows() {
        long userId = 999L;
        given(userRepository.findById(userId)).willReturn(Optional.empty());

        UpdateProfileRequest request = new UpdateProfileRequest("New Name", null, null, null);

        assertThatThrownBy(() -> userProfileService.updateProfile(userId, request))
                .isInstanceOf(NotFoundException.class)
                .satisfies(e -> {
                    NotFoundException nfe = (NotFoundException) e;
                    assertThat(nfe.errorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
                });
    }

    @Test
    @DisplayName("Unchanged avatarUrl does not call storage requireUploadedFor")
    void updateProfileUnchangedAvatarDoesNotCallStorage() {
        long userId = 1L;
        AuthUser existingUser = anActiveUser()
                .withId(userId)
                .build();
        // create user with existing avatar
        AuthUser userWithAvatar = new AuthUser(
                userId, "test@example.com", "user", "Name", "hash",
                "bio", "phone", "avatars/2026/09/avatar.png",
                existingUser.status(), (short) 0, null, null,
                existingUser.createdAt(), Set.of(RoleCode.BUYER));

        given(userRepository.findById(userId))
                .willReturn(Optional.of(userWithAvatar))
                .willReturn(Optional.of(userWithAvatar));
        given(roleService.rolesOf(userId)).willReturn(Set.of(RoleCode.BUYER));

        UpdateProfileRequest request = new UpdateProfileRequest(
                "Name", "bio", "phone", "avatars/2026/09/avatar.png");

        userProfileService.updateProfile(userId, request);

        verify(storageService, never()).requireUploadedFor(any(), any());
    }

    @Test
    @DisplayName("Empty string clears bio, phone, and avatar")
    void updateProfileClearsOptionalFields() {
        long userId = 1L;
        AuthUser existingUser = anActiveUser().withId(userId).build();

        given(userRepository.findById(userId))
                .willReturn(Optional.of(existingUser))
                .willReturn(Optional.of(existingUser));
        given(roleService.rolesOf(userId)).willReturn(Set.of(RoleCode.BUYER));

        UpdateProfileRequest request = new UpdateProfileRequest(
                null, "", "", "");

        userProfileService.updateProfile(userId, request);

        verify(storageService, never()).requireUploadedFor(any(), any());
        verify(userRepository).updateProfile(eq(userId), eq(existingUser.displayName()), eq(null), eq(null), eq(null));
    }
}
