package com.pegasus.pegasustcgapi.service.sale;

import com.pegasus.pegasustcgapi.dto.sale.SalePricing;
import com.pegasus.pegasustcgapi.repository.sale.ListingSalePriceRepository;
import com.pegasus.pegasustcgapi.repository.sale.ListingSalePriceRepository.Shown;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The price buyers are shown, read from {@code listing_sale_price} [TCG-679]. One
 * read per page whatever it holds, so every page that shows a card shows the same
 * figure. Checkout does not use this: it works the price out afresh, see
 * {@code SalePriceResolver}.
 */
@Service
public class SalePricingService {

    private final ListingSalePriceRepository prices;
    private final Clock clock;

    public SalePricingService(ListingSalePriceRepository prices, Clock clock) {
        this.prices = prices;
        this.clock = clock;
    }

    /** @param listPrices each listing's own price, by listing id */
    public Map<Long, SalePricing> pricing(Map<Long, BigDecimal> listPrices) {
        if (listPrices.isEmpty()) {
            return Map.of();
        }
        Map<Long, Shown> shown = prices.findShown(listPrices.keySet(), OffsetDateTime.now(clock));
        Map<Long, SalePricing> out = new LinkedHashMap<>();
        listPrices.forEach((id, price) -> out.put(id, SalePricing.of(price, shown.get(id))));
        return out;
    }

    public SalePricing pricing(long listingId, BigDecimal listPrice) {
        return pricing(Map.of(listingId, listPrice)).get(listingId);
    }
}
