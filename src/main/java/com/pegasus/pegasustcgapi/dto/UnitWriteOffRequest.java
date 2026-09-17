package com.pegasus.pegasustcgapi.dto;

import jakarta.validation.constraints.Size;

/** @param reason kept on the ledger line, e.g. "water damage in storage" */
public record UnitWriteOffRequest(@Size(max = 255) String reason) {

    public String reasonOrNull() {
        return reason == null || reason.isBlank() ? null : reason.trim();
    }
}
