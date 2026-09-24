package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.repository.AddressRepository.AddressFields;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * One entry in the address book. The same body creates and replaces, so a client
 * never has to know which fields an update is allowed to omit.
 *
 * <p>The free-text fields reject control characters: an order freezes this address
 * into {@code sales_order.shipping_address_snapshot}, and a newline or a NUL in a
 * recipient name is never a real address, only a way to make the stored record
 * read differently from what was typed.
 *
 * @param label optional nickname, e.g. "บ้าน" or "ที่ทำงาน"
 */
public record AddressRequest(

        @Size(max = 50)
        @Pattern(regexp = NO_CONTROL_CHARS, message = NO_CONTROL_CHARS_MESSAGE)
        String label,

        @NotBlank @Size(max = 150)
        @Pattern(regexp = NO_CONTROL_CHARS, message = NO_CONTROL_CHARS_MESSAGE)
        String recipientName,

        @NotBlank @Size(max = 20)
        @Pattern(regexp = "^[0-9+()\\-\\s]+$", message = "is not a valid phone number")
        String phone,

        @NotBlank @Size(max = 255)
        @Pattern(regexp = NO_CONTROL_CHARS, message = NO_CONTROL_CHARS_MESSAGE)
        String line1,

        @Size(max = 255)
        @Pattern(regexp = NO_CONTROL_CHARS, message = NO_CONTROL_CHARS_MESSAGE)
        String line2,

        @Size(max = 100)
        @Pattern(regexp = NO_CONTROL_CHARS, message = NO_CONTROL_CHARS_MESSAGE)
        String subdistrict,

        @Size(max = 100)
        @Pattern(regexp = NO_CONTROL_CHARS, message = NO_CONTROL_CHARS_MESSAGE)
        String district,

        @NotBlank @Size(max = 100)
        @Pattern(regexp = NO_CONTROL_CHARS, message = NO_CONTROL_CHARS_MESSAGE)
        String province,

        @NotBlank @Size(max = 10)
        @Pattern(regexp = "^[0-9]{5}$", message = "must be a 5-digit postal code")
        String postalCode,

        @Pattern(regexp = "^[A-Z]{2}$", message = "must be a 2-letter country code")
        String countryCode,

        /** Absent means "leave it as it is"; Jackson cannot default a primitive. */
        Boolean defaultShipping,
        Boolean defaultBilling) {

    /** Anything printable, on one line. */
    static final String NO_CONTROL_CHARS = "^[^\\p{Cntrl}]*$";
    static final String NO_CONTROL_CHARS_MESSAGE = "must not contain line breaks or control characters";

    public AddressFields toFields() {
        return new AddressFields(label, recipientName, phone, line1, line2, subdistrict,
                district, province, postalCode,
                countryCode == null || countryCode.isBlank() ? "TH" : countryCode,
                Boolean.TRUE.equals(defaultShipping), Boolean.TRUE.equals(defaultBilling));
    }
}
