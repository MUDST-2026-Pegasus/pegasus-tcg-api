package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.dto.GrantRoleRequest;
import com.pegasus.pegasustcgapi.model.RoleCode;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.RoleService;
import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
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
    public ApiResult<Set<RoleCode>> grant(
            @PathVariable long userId,
            @Valid @RequestBody GrantRoleRequest request,
            AuthPrincipal principal) {

        return ApiResult.success(
                "Role granted", roleService.grantAsAdmin(userId, request.role(), principal.userId()));
    }

    @DeleteMapping("/{role}")
    public ApiResult<Set<RoleCode>> revoke(
            @PathVariable long userId, @PathVariable RoleCode role, AuthPrincipal principal) {

        return ApiResult.success(
                "Role revoked", roleService.revokeAsAdmin(userId, role, principal.userId()));
    }
}
