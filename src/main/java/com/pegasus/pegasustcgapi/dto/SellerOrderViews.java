package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.jooq.tables.records.PaymentRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * What the seller's order desk reads: their own sub-orders with the parts of the
 * parent order a packer needs — who bought it, where it goes and by when it should
 * ship. Values are raw; the page words them.
 *
 * <p>Unlike the admin list, a row here carries the shipping address: the seller
 * cannot pack an order without it, and every row is already their own.
 */
public final class SellerOrderViews {

    private SellerOrderViews() {}

    /**
     * @param id        the buyer's {@code user_account.id}
     * @param avatarUrl short-lived link to the buyer's profile picture, null when they have none
     */
    public record Buyer(long id, String username, String displayName, String avatarUrl) {
    }

    /**
     * @param order                   items, money, shipments and history, as the
     *                                buyer and admin views also see them
     * @param orderNumber             the parent order's {@code PGS-…} number, which is
     *                                what the buyer quotes
     * @param paidAt                  when the parent order was paid; sub-orders share it
     * @param shipBy                  {@code paidAt} plus the shop's handling days; null
     *                                until paid
     * @param shippingAddressSnapshot the address as it read at checkout, as JSON
     */
    public record Summary(
            SellerOrderDetailsResponse order,
            String orderNumber,
            Buyer buyer,
            OffsetDateTime paidAt,
            OffsetDateTime shipBy,
            String shippingAddressSnapshot,
            String buyerNote) {
    }

    /** The slip itself stays with the admin who checks it; the seller sees the outcome. */
    public record Payment(String method, String status, BigDecimal amount, OffsetDateTime paidAt) {

        public static Payment from(PaymentRecord row) {
            return new Payment(row.getMethod(), row.getStatus(), row.getAmount(), row.getPaidAt());
        }
    }

    /**
     * @param payment         the newest attempt on the parent order, null before checkout pays
     * @param costTotal       what the cards cost the seller, at the average frozen at checkout
     * @param buyerOrderCount orders this buyer has paid for at this shop, this one included
     */
    public record Detail(
            Summary summary,
            Payment payment,
            BigDecimal costTotal,
            long buyerOrderCount) {
    }
}
