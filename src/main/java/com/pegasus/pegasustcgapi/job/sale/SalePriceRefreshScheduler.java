package com.pegasus.pegasustcgapi.job.sale;

import com.pegasus.pegasustcgapi.job.JobLock;
import com.pegasus.pegasustcgapi.service.sale.SalePriceRefresher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Starts and ends sales on time [TCG-676]. Nothing calls in when a sale's
 * {@code starts_at} or {@code ends_at} passes, so once a minute every listing's
 * sale price is worked out again; see {@link SalePriceRefresher}. One instance
 * at a time, since every replica would otherwise write the same rows.
 */
@Component
public class SalePriceRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(SalePriceRefreshScheduler.class);

    static final String LOCK_NAME = "salePriceRefresh";

    private final SalePriceRefresher refresher;
    private final JobLock jobLock;

    public SalePriceRefreshScheduler(SalePriceRefresher refresher, JobLock jobLock) {
        this.refresher = refresher;
        this.jobLock = jobLock;
    }

    @Scheduled(cron = "${pegasus.sale.refresh-cron:0 * * * * *}")
    public void runRefresh() {
        jobLock.runExclusively(LOCK_NAME, () -> {
            int changed = refresher.refreshAll();
            if (changed > 0) {
                log.info("Sale prices: {} listing(s) changed", changed);
            }
        });
    }
}
