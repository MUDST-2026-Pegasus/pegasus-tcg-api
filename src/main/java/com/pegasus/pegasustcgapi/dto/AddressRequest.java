package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.repository.AddressRepository.AddressFields;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * One entry in the address book. The same body creates and replaces, so a client
 * never has to know which fields an update is allowed to omit.
 *
 * @param label optional nickname, e.g. "บ้าน" or "ที่ทำงาน"
 */
public record AddressRequest(

        @Size(max = 50)
        String label,

        @NotBlank @Size(max = 150)
        String recipientName,

        @NotBlank @Size(max = 20)
        @Pattern(regexp = "^[0-9+()\\-\\s]+$", message = "is not a valid phone number")
        String phone,

        @NotBlank @Size(max = 255)
        String line1,

        @Size(max = 255)
        String line2,

        @Size(max = 100)
        String subdistrict,

        @Size(max = 100)
        String district,

        @NotBlank @Size(max = 100)
        String province,

        @NotBlank @Size(max = 10)
        @Pattern(regexp = "^[0-9]{5}$", message = "must be a 5-digit postal code")
        String postalCode,

        @Pattern(regexp = "^[A-Z]{2}$", message = "must be a 2-letter country code")
        String countryCode,

        /** Absent means "leave it as it is"; Jackson cannot default a primitive. */
        Boolean defaultShipping,
        Boolean defaultBilling) {

    public AddressFields toFields() {
        return new AddressFields(label, recipientName, phone, line1, line2, subdistrict,
                district, province, postalCode,
                countryCode == null || countryCode.isBlank() ? "TH" : countryCode,
                Boolean.TRUE.equals(defaultShipping), Boolean.TRUE.equals(defaultBilling));
    }
}
