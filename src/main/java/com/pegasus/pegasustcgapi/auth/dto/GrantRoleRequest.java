package com.pegasus.pegasustcgapi.auth.dto;

import com.pegasus.pegasustcgapi.auth.model.RoleCode;
import jakarta.validation.constraints.NotNull;

public record GrantRoleRequest(

        @NotNull
        RoleCode role) {
}
