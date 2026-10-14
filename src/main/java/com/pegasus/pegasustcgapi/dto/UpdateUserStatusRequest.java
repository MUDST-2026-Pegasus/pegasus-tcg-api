package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.UserStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateUserStatusRequest(

        @NotNull
        UserStatus status) {
}
