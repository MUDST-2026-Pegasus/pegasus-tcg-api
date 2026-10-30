package com.pegasus.pegasustcgapi.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Small bodies for the admin promotion actions that are not the full form. */
public final class PromotionActionRequests {

    private PromotionActionRequests() {
    }

    /** @param reason required when pausing a shop's code; shown to the shop */
    public record Pause(@Size(max = 255) String reason, Boolean notifySeller) {

        public boolean notifySellerOrFalse() {
            return Boolean.TRUE.equals(notifySeller);
        }
    }

    public record Resume(Boolean notifySeller) {

        public boolean notifySellerOrFalse() {
            return Boolean.TRUE.equals(notifySeller);
        }
    }

    /** The whole home-page strip in display order; codes left out stop being featured. */
    public record HomeOrder(@NotNull @Size(max = 8) List<@NotNull Long> promotionIds) {
    }
}
