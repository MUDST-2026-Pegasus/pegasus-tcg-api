package com.pegasus.pegasustcgapi.dto.user;

import com.pegasus.pegasustcgapi.model.user.UserStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateUserStatusRequest(

        @NotNull
        UserStatus status) {
}
