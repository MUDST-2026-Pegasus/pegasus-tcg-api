package com.pegasus.pegasustcgapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A bank account for receiving payouts. Approval already registers the account
 * that was verified, so this is for a seller who changes bank or keeps more than
 * one.
 *
 * @param accountNumber reduced to digits before storage, so one account has
 *        exactly one stored form
 */
public record PayoutAccountRequest(

        @NotBlank @Size(max = 10)
        String bankCode,

        @NotBlank @Size(max = 100)
        String bankName,

        @NotBlank @Size(max = 150)
        String accountName,

        @NotBlank @Size(max = 34)
        @Pattern(regexp = "^[0-9\\-\\s]+$", message = "may only contain digits, spaces and dashes")
        String accountNumber,

        /** Absent means no. The first account a seller adds becomes default regardless. */
        Boolean makeDefault) {

    public boolean wantsDefault() {
        return Boolean.TRUE.equals(makeDefault);
    }

    public String normalisedAccountNumber() {
        return accountNumber.replaceAll("[^0-9]", "");
    }
}
