package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.config.PricingProperties;
import com.pegasus.pegasustcgapi.dto.ProductMarketResponse;
import com.pegasus.pegasustcgapi.dto.ProductMarketResponse.LastSold;
import com.pegasus.pegasustcgapi.repository.VariantMarketStatRepository;
import com.pegasus.pegasustcgapi.repository.VariantMarketStatRepository.ProductMarketRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ProductMarketService {

    private final VariantMarketStatRepository stats;
    private final PricingProperties pricing;
    private final Clock clock;

    public ProductMarketService(VariantMarketStatRepository stats, PricingProperties pricing, Clock clock) {
        this.stats = stats;
        this.pricing = pricing;
        this.clock = clock;
    }

    public List<ProductMarketResponse> ofProduct(long productId) {
        LocalDate baselineDate = LocalDate.now(clock.withZone(pricing.zone())).minusDays(30);
        return stats.latestByProduct(productId, baselineDate).stream().map(ProductMarketService::toResponse).toList();
    }

    private static ProductMarketResponse toResponse(ProductMarketRow row) {
        BigDecimal change = null;
        if (row.medianPrice() != null && row.baselineMedian() != null
                && row.baselineMedian().signum() > 0) {
            change = row.medianPrice().subtract(row.baselineMedian())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(row.baselineMedian(), 2, RoundingMode.HALF_UP);
        }
        LastSold lastSold = row.lastSoldAt() == null ? null : new LastSold(row.lastSoldPrice(), row.lastSoldAt());
        return new ProductMarketResponse(row.catalogVariantId(), row.condition(), row.medianPrice(),
                row.statDate(), change, lastSold, row.sampleSize() < 3);
    }
}
