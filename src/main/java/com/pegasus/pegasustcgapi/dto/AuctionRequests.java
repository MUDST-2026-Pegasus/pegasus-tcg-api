package com.pegasus.pegasustcgapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Every request body the auction endpoints accept [TCG-613]. Money is THB. */
public final class AuctionRequests {

    /** Seller notes and admin reasons share this limit. */
    public static final int MAX_NOTE = 500;

    private AuctionRequests() {
    }

    /**
     * A bid. It cannot be withdrawn once accepted.
     *
     * <p>Below the minimum is 409 BID_TOO_LOW with {@code details.minNextBid} and
     * {@code details.currentPrice}, which is also what a buyer gets when someone bid first.
     */
    @Schema(description = "A bid on a live auction")
    public record PlaceBid(
            @Schema(description = "At least minNextBid from the auction page", example = "8550")
            @NotNull @Positive BigDecimal amount) {
    }

    /**
     * The checkout for a won auction. The price is locked at the winning bid and
     * discount codes do not apply; the rest works like the cart checkout.
     *
     * @param expectedGrandTotal what the buyer was last shown; a different total is 409 CART_PRICE_CHANGED
     */
    @Schema(description = "Pay for a won auction through the ordinary order and payment flow")
    public record AuctionCheckout(
            @Schema(description = "One of the buyer's addresses", example = "12")
            @NotNull Long shippingAddressId,
            @Schema(description = "The seller's shipping option; null takes the seller's default", example = "3")
            Long shippingOptionId,
            @Schema(example = "ฝากใส่ซองกันน้ำด้วยครับ")
            @Size(max = MAX_NOTE) String buyerNote,
            @Schema(example = "8500")
            BigDecimal expectedGrandTotal) {
    }

    /**
     * A new auction from one card in the seller's stock. It opens as soon as it is created.
     *
     * <p>A card on a live listing is taken off the listing. A card held by an order or
     * another auction is 409 UNIT_NOT_AVAILABLE.
     */
    @Schema(description = "Open an auction for one stock card")
    public record CreateAuction(
            @Schema(description = "The stock card, from GET /sellers/me/auctions/eligible-units",
                    example = "0f9e1c2a-6b1d-4e8a-9a51-2c7d5b3e1042")
            @NotNull UUID unitUid,
            @Schema(example = "1000")
            @NotNull @Positive BigDecimal startingPrice,
            @Schema(description = "The least each bid must add", example = "50")
            @NotNull @Positive BigDecimal bidIncrement,
            @Schema(description = "1 hour to 30 days from now (AUCTION_END_TOO_SOON / AUCTION_END_TOO_FAR)",
                    example = "2026-11-02T21:00:00+07:00")
            @NotNull OffsetDateTime endsAt,
            @Schema(description = "Null leaves buy-now off; else above startingPrice (BUY_NOW_BELOW_START)",
                    example = "1800")
            @Positive BigDecimal buyNowPrice,
            @Schema(description = "Shown on the auction page; no contact details outside Pegasus",
                    example = "เก็บใน top loader ตั้งแต่แกะซอง ไม่เคยเล่น")
            @Size(max = MAX_NOTE) String sellerNote) {
    }

    /**
     * The settings a seller may still change. Send both fields; allowed only while
     * there are no bids (else 409 AUCTION_HAS_BIDS).
     */
    @Schema(description = "Change buy-now or the note before the first bid")
    public record UpdateAuction(
            @Schema(description = "Null turns buy-now off; else above the starting price", example = "3200")
            @Positive BigDecimal buyNowPrice,
            @Schema(example = "ส่งแบบกล่องแข็ง")
            @Size(max = MAX_NOTE) String sellerNote) {
    }

    /** What the seller does after the winner did not pay. */
    public enum DecisionChoice {
        /** Offer the card to the runner-up at their own bid (409 NO_SECOND_BIDDER when there is none). */
        OFFER_TO_SECOND,
        /** Put the card back in stock; the app then opens the new-auction form with the old settings. */
        RELIST
    }

    @Schema(description = "The seller's choice after the winner did not pay")
    public record SellerDecision(@NotNull DecisionChoice choice) {
    }

    @Schema(description = "Why an admin stopped the auction; the seller and every bidder see it")
    public record SuspendAuction(
            @Schema(example = "พบรูปแบบบิดผิดปกติจากบัญชีที่เชื่อมกับผู้ขาย (บิดปั่นราคา)")
            @NotBlank @Size(max = MAX_NOTE) String reason) {
    }

    @Schema(description = "Why an admin lifted a bidding ban, kept in the activity log")
    public record LiftBiddingBan(
            @Schema(example = "ระบบชำระเงินขัดข้องวันที่ 29 ต.ค.")
            @Size(max = MAX_NOTE) String reason) {
    }
}
