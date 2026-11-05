package com.pegasus.pegasustcgapi.service.listing;

import com.pegasus.pegasustcgapi.dto.listing.StockForecastResponse;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.port.seller.SellerPort;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockForecastService {
    // Two completed units in the window are required before extrapolating a date.
    static final int MIN_SALES = 2;
    private final DSLContext db;
    private final SellerPort sellers;
    private final Clock clock;

    public StockForecastService(DSLContext db, SellerPort sellers, Clock clock) {
        this.db = db;
        this.sellers = sellers;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<StockForecastResponse> list(long userId) {
        long sellerId = sellers.requireProfile(userId).id();
        OffsetDateTime end = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime start = end.minusDays(30);
        return db.fetch("""
                SELECT l.id, p.name, l.status AS listing_status, l.quantity_available,
                       COALESCE(s.units, 0) AS sales
                  FROM listing l
                  JOIN catalog_variant v ON v.id = l.catalog_variant_id
                  JOIN catalog_product p ON p.id = v.catalog_product_id
                  LEFT JOIN (
                    SELECT oi.listing_id, SUM(GREATEST(oi.quantity - COALESCE(returned.units, 0), 0)) AS units
                      FROM order_item oi
                      JOIN seller_order so ON so.id = oi.seller_order_id
                      LEFT JOIN (
                        SELECT ri.order_item_id, SUM(ri.quantity) AS units
                          FROM return_item ri
                          JOIN return_request rr ON rr.id = ri.return_request_id
                         WHERE rr.status IN ('REFUNDED', 'CLOSED')
                         GROUP BY ri.order_item_id
                      ) returned ON returned.order_item_id = oi.id
                     WHERE so.seller_profile_id = ?
                       AND so.status = 'COMPLETED'
                       AND so.completed_at >= CAST(? AS timestamptz)
                       AND so.completed_at < CAST(? AS timestamptz)
                     GROUP BY oi.listing_id
                  ) s ON s.listing_id = l.id
                 WHERE l.seller_profile_id = ? AND l.deleted_at IS NULL
                 ORDER BY l.id
                """, sellerId, start, end, sellerId).map(row -> calculate(
                row.get("id", Long.class), row.get("name", String.class),
                row.get("listing_status", String.class), row.get("quantity_available", Integer.class),
                row.get("sales", BigDecimal.class).intValueExact(), start, end));
    }

    @Transactional(readOnly = true)
    public StockForecastResponse detail(long userId, long listingId) {
        return list(userId).stream().filter(row -> row.listingId() == listingId).findFirst()
                .orElseThrow(() -> new NotFoundException(ErrorCode.LISTING_NOT_FOUND));
    }

    static StockForecastResponse calculate(long id, String name, String listingStatus, int stock, int sales,
            OffsetDateTime start, OffsetDateTime end) {
        BigDecimal average = BigDecimal.valueOf(sales).divide(BigDecimal.valueOf(30), 4, RoundingMode.HALF_UP);
        String status = stock == 0 ? "OUT_OF_STOCK" : sales == 0 ? "NO_SALES"
                : sales < MIN_SALES ? "INSUFFICIENT_DATA" : "FORECAST_AVAILABLE";
        Integer days = "FORECAST_AVAILABLE".equals(status)
                ? BigDecimal.valueOf(stock).multiply(BigDecimal.valueOf(30))
                        .divide(BigDecimal.valueOf(sales), 0, RoundingMode.CEILING).intValueExact()
                : null;
        return new StockForecastResponse(id, name, listingStatus, status, start, end, stock, sales, average,
                days, days == null ? null : end.toLocalDate().plusDays(days), end);
    }
}
