package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.dto.GrantRoleRequest;
import com.pegasus.pegasustcgapi.model.RoleCode;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.RoleService;
import com.pegasus.pegasustcgapi.common.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
            @ApiResponse(responseCode = "400", description = "Invalid role or target user already has role"),
            @ApiResponse(responseCode = "403", description = "Access denied — caller lacks ADMIN role")
    })
    @PostMapping
    public com.pegasus.pegasustcgapi.common.ApiResponse<Set<RoleCode>> grant(
            @Parameter(description = "ID of user to grant role to", example = "1") @PathVariable long userId,
            @Valid @RequestBody GrantRoleRequest request,
            AuthPrincipal principal) {

        return com.pegasus.pegasustcgapi.common.ApiResponse.success(
                "Role granted", roleService.grantAsAdmin(userId, request.role(), principal.userId()));
    }

    @Operation(summary = "Revoke role from user", description = "Revokes a specific role from a user account.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Role revoked successfully"),
            @ApiResponse(responseCode = "400", description = "Role cannot be revoked or user lacks role"),
            @ApiResponse(responseCode = "403", description = "Access denied — caller lacks ADMIN role")
    })
    @DeleteMapping("/{role}")
    public com.pegasus.pegasustcgapi.common.ApiResponse<Set<RoleCode>> revoke(
            @Parameter(description = "ID of user to revoke role from", example = "1") @PathVariable long userId,
            @Parameter(description = "Role code to revoke") @PathVariable RoleCode role,
            AuthPrincipal principal) {

        return com.pegasus.pegasustcgapi.common.ApiResponse.success(
                "Role revoked", roleService.revokeAsAdmin(userId, role, principal.userId()));
    }
}
