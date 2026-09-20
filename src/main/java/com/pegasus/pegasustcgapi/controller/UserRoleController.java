package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.dto.GrantRoleRequest;
import com.pegasus.pegasustcgapi.model.RoleCode;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.RoleService;
import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only role management. BUYER and SELLER are self-service at sign-up;
 * ADMIN and SUPPORT can only be handed out here.
 *
 * <p>A change lands in the target's next access token, not the current one — they
 * pick it up when their client refreshes.
 */
@Tag(name = "User Roles (Admin)", description = "Endpoints for granting and revoking roles (Admin only)")
@SecurityRequirement(name = "BearerAuth")
@RestController
@RequestMapping(ApiPaths.USERS + "/{userId}/roles")
@PreAuthorize("hasRole('ADMIN')")
public class UserRoleController {

    private final RoleService roleService;

    public UserRoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @Operation(summary = "Grant role to user", description = "Grants a specific role (e.g. SUPPORT, ADMIN) to a user account.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Role granted successfully"),
            @ApiResponse(responseCode = "400", description = "Request payload failed validation"),
            @ApiResponse(responseCode = "403", description = "Access denied — caller lacks ADMIN role"),
            @ApiResponse(responseCode = "404", description = "User or role not found")
    })
    @PostMapping
    public ApiResult<Set<RoleCode>> grant(
            @PathVariable long userId,
            @Valid @RequestBody GrantRoleRequest request,
            AuthPrincipal principal) {

        return ApiResult.success(
                "Role granted", roleService.grantAsAdmin(userId, request.role(), principal.userId()));
    }

    @Operation(summary = "Revoke role from user", description = "Revokes a specific role from a user account.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Role revoked successfully"),
            @ApiResponse(responseCode = "403", description = "Access denied — caller lacks ADMIN role or admin cannot revoke own ADMIN role"),
            @ApiResponse(responseCode = "404", description = "User or role not found")
    })
    @DeleteMapping("/{role}")
    public ApiResult<Set<RoleCode>> revoke(
            @PathVariable long userId, @PathVariable RoleCode role, AuthPrincipal principal) {

        return ApiResult.success(
                "Role revoked", roleService.revokeAsAdmin(userId, role, principal.userId()));
    }
}
