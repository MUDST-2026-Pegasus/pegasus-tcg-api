package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.RoleCode;
import jakarta.validation.constraints.NotNull;

public record GrantRoleRequest(

        @NotNull
        RoleCode role) {
}
