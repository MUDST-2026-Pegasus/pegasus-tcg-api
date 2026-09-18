package com.pegasus.pegasustcgapi.job;

import com.pegasus.pegasustcgapi.service.MarketPricingService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Starts the market run at 03:00 Thai time, when prices move least and fewest
 * buyers are looking. The work, and why repeating it is harmless, is in
 * {@link MarketPricingService}.
 */
@Component
public class MarketPricingJob {

    private final MarketPricingService pricing;

    public MarketPricingJob(MarketPricingService pricing) {
        this.pricing = pricing;
    }

    @Scheduled(cron = "${pegasus.pricing.market-job-cron}", zone = "${pegasus.pricing.zone}")
    public void runNightly() {
        pricing.runToday();
    }
}
