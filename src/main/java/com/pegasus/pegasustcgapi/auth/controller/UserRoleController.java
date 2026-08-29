package com.pegasus.pegasustcgapi.auth.controller;

import com.pegasus.pegasustcgapi.auth.dto.GrantRoleRequest;
import com.pegasus.pegasustcgapi.auth.model.RoleCode;
import com.pegasus.pegasustcgapi.auth.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.auth.service.RoleService;
import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResponse;
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
@RestController
@RequestMapping(ApiPaths.USERS + "/{userId}/roles")
@PreAuthorize("hasRole('ADMIN')")
public class UserRoleController {

    private final RoleService roleService;

    public UserRoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @PostMapping
    public ApiResponse<Set<RoleCode>> grant(
            @PathVariable long userId,
            @Valid @RequestBody GrantRoleRequest request,
            AuthPrincipal principal) {

        return ApiResponse.success(
                "Role granted", roleService.grantAsAdmin(userId, request.role(), principal.userId()));
    }

    @DeleteMapping("/{role}")
    public ApiResponse<Set<RoleCode>> revoke(
            @PathVariable long userId, @PathVariable RoleCode role, AuthPrincipal principal) {

        return ApiResponse.success(
                "Role revoked", roleService.revokeAsAdmin(userId, role, principal.userId()));
    }
}
