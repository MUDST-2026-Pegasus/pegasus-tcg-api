package com.pegasus.pegasustcgapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What a seller submits to be allowed to sell: the name on their bank account,
 * and the account.
 *
 * @param bankAccountNumber typed however the bank prints it. It is reduced to
 *        digits before storage, so "123-4-56789-0" and "1234567890" are the same
 *        account and cannot both slip past the duplicate check.
 */
public record VerificationRequest(

        @NotBlank @Size(max = 100)
        String legalFirstName,

        @NotBlank @Size(max = 100)
        String legalLastName,

        @NotBlank @Size(max = 10)
        String bankCode,

        @NotBlank @Size(max = 100)
        String bankName,

        @NotBlank @Size(max = 34)
        @Pattern(regexp = "^[0-9\\-\\s]+$", message = "may only contain digits, spaces and dashes")
        String bankAccountNumber,

        @NotBlank @Size(max = 500)
        String bankBookImageKey) {

    /** Digits only, so one account has exactly one stored form. */
    public String normalisedAccountNumber() {
        return bankAccountNumber.replaceAll("[^0-9]", "");
    }
}
