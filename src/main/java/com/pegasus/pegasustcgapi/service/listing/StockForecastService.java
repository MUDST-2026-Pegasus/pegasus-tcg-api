package com.pegasus.pegasustcgapi.service.listing;

import com.pegasus.pegasustcgapi.dto.listing.StockForecastResponse;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.port.seller.SellerPort;
import com.pegasus.pegasustcgapi.model.listing.StockForecastStatus;
import com.pegasus.pegasustcgapi.repository.listing.StockForecastRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockForecastService {
    // Two completed units in the window are required before extrapolating a date.
    static final int MIN_SALES = 2;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    private final StockForecastRepository forecasts;
    private final SellerPort sellers;
    private final Clock clock;

    public StockForecastService(StockForecastRepository forecasts, SellerPort sellers, Clock clock) {
        this.forecasts = forecasts;
        this.sellers = sellers;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<StockForecastResponse> list(long userId) {
        long sellerId = sellers.requireProfile(userId).id();
        OffsetDateTime end = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime start = end.minusDays(30);
        return forecasts.list(sellerId, start, end).stream().map(row -> calculate(
                row.listingId(), row.listingName(), row.listingStatus(), row.availableStock(), row.sales(),
                start, end)).toList();
    }

    @Transactional(readOnly = true)
    public StockForecastResponse detail(long userId, long listingId) {
        long sellerId = sellers.requireProfile(userId).id();
        OffsetDateTime end = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime start = end.minusDays(30);
        return forecasts.detail(sellerId, listingId, start, end).map(row -> calculate(
                row.listingId(), row.listingName(), row.listingStatus(), row.availableStock(), row.sales(),
                start, end))
                .orElseThrow(() -> new NotFoundException(ErrorCode.LISTING_NOT_FOUND));
    }

    static StockForecastResponse calculate(long id, String name, String listingStatus, int stock, int sales,
            OffsetDateTime start, OffsetDateTime end) {
        BigDecimal average = BigDecimal.valueOf(sales).divide(BigDecimal.valueOf(30), 4, RoundingMode.HALF_UP);
        StockForecastStatus status = stock == 0 ? StockForecastStatus.OUT_OF_STOCK
                : sales == 0 ? StockForecastStatus.NO_SALES
                : sales < MIN_SALES ? StockForecastStatus.INSUFFICIENT_DATA : StockForecastStatus.FORECAST_AVAILABLE;
        Integer days = status == StockForecastStatus.FORECAST_AVAILABLE
                ? BigDecimal.valueOf(stock).multiply(BigDecimal.valueOf(30))
                        .divide(BigDecimal.valueOf(sales), 0, RoundingMode.CEILING).intValueExact()
                : null;
        return new StockForecastResponse(id, name, listingStatus, status, start, end, stock, sales, average,
                days, days == null ? null : end.atZoneSameInstant(BUSINESS_ZONE).toLocalDate().plusDays(days), end);
    }
}
