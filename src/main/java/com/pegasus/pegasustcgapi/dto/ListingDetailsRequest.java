package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.repository.ListingRepository.Details;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Replaces what describes a listing. The card and condition never change once
 * cards may be on it, and the price has its own endpoint, because a price change
 * is written to history and a description change is not.
 *
 * @param imageKeys the whole set, in order; send the keys already on the listing to keep them
 */
public record ListingDetailsRequest(

        @Size(max = 20)
        String gradingCompany,

        @DecimalMin("0.5") @DecimalMax("10.0") @Digits(integer = 2, fraction = 1)
        BigDecimal gradeValue,

        @Size(max = 60)
        String lotLabel,

        @Size(max = 500)
        String publicNote,

        @Size(max = 10)
        List<@NotBlank @Size(max = 500) String> imageKeys) {

    public Details details() {
        return new Details(blankToNull(gradingCompany), gradeValue, blankToNull(lotLabel), blankToNull(publicNote));
    }

    public List<String> photos() {
        return imageKeys == null ? List.of() : imageKeys.stream().map(String::trim).toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
