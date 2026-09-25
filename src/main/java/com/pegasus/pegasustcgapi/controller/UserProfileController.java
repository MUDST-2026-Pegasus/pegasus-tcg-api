package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.UpdateProfileRequest;
import com.pegasus.pegasustcgapi.dto.UserResponse;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "User Profile", description = "Endpoints for user profile management")
@SecurityRequirement(name = "BearerAuth")
@RestController
@RequestMapping(ApiPaths.USERS)
public class UserProfileController {

    private final UserProfileService userProfileService;

    public UserProfileController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @Operation(
            summary = "Update current user profile",
            description = """
                    Updates profile fields for the authenticated user.
                    - Send a field with a value to update it.
                    - Send `null` (or omit the field) to keep the current value.
                    - Send `""` (empty string) to clear the field (bio, phone, avatarUrl only; \
                    displayName must contain at least one non-whitespace character when present).
                    - `avatarUrl` must be an object key obtained from `/uploads/presign` with \
                    purpose `AVATAR_IMAGE`; the response returns a presigned download URL.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profile updated successfully"),
            @ApiResponse(responseCode = "400", description = "Validation failed or invalid avatar key"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "404", description = "User or avatar file not found"),
            @ApiResponse(responseCode = "409", description = "Avatar image already in use by another account")
    })
    @PutMapping("/me")
    public ApiResult<UserResponse> updateProfile(
            @Valid @RequestBody UpdateProfileRequest request,
            AuthPrincipal principal) {

        UserResponse response = userProfileService.updateProfile(principal.userId(), request);
        return ApiResult.success("Profile updated", response);
    }
}
