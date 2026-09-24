package com.pegasus.pegasustcgapi.service;

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
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserProfileService {

    private final UserRepository users;
    private final RoleService roles;
    private final StorageService storage;

    public UserProfileService(UserRepository users, RoleService roles, StorageService storage) {
        this.users = users;
        this.roles = roles;
        this.storage = storage;
    }

    @Transactional
    public UserResponse updateProfile(long userId, UpdateProfileRequest request) {
        AuthUser user = users.findById(userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND, "User not found"));

        String displayName = user.displayName();
        if (request.displayName() != null) {
            String clean = request.cleanDisplayName();
            if (clean == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "displayName cannot be blank");
            }
            displayName = clean;
        }

        String avatarUrl = user.avatarUrl();
        if (request.avatarUrl() != null) {
            String cleanAvatar = request.cleanAvatarUrl();
            if (cleanAvatar == null) {
                avatarUrl = null;
            } else {
                if (!cleanAvatar.equals(user.avatarUrl())) {
                    storage.requireUploadedFor(UploadPurpose.AVATAR_IMAGE, cleanAvatar);
                }
                avatarUrl = cleanAvatar;
            }
        }

        String bio = request.bio() != null ? request.cleanBio() : user.bio();
        String phone = request.phone() != null ? request.cleanPhone() : user.phone();

        users.updateProfile(userId, displayName, bio, phone, avatarUrl);

        AuthUser updated = users.findById(userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND, "User not found"));
        Set<RoleCode> userRoles = roles.rolesOf(userId);
        return UserResponse.from(updated.withRoles(userRoles));
    }
}
