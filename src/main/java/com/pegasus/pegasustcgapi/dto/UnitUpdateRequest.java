package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.service.ListingUnitService.UnitNotes;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/**
 * Replaces what is written about one card. The cost is not here: the stock ledger
 * and the average cost were already booked from it.
 *
 * @param acquiredAt decides which card goes first when an order is filled
 */
public record UnitUpdateRequest(

        @Size(max = 64)
        String certNumber,

        @Size(max = 255)
        String unitNote,

        @PastOrPresent
        OffsetDateTime acquiredAt) {

    public UnitNotes toNotes() {
        return new UnitNotes(blankToNull(certNumber), blankToNull(unitNote), acquiredAt);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
