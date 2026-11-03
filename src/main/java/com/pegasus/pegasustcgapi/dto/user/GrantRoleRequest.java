package com.pegasus.pegasustcgapi.dto.user;

import com.pegasus.pegasustcgapi.model.user.RoleCode;
import jakarta.validation.constraints.NotNull;

public record GrantRoleRequest(

        @NotNull
        RoleCode role) {
}
