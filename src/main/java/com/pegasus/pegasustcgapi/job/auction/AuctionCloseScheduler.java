package com.pegasus.pegasustcgapi.job.auction;

import com.pegasus.pegasustcgapi.job.JobLock;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import com.pegasus.pegasustcgapi.service.auction.AuctionCloseService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps LIVE auctions whose ends_at has arrived, determines the outcome
 * (AWAITING_PAYMENT for winners, UNSOLD for auctions without bids), and
 * returns unsold cards to seller stock [TCG-613, TCG-619].
 *
 * <p>Uses {@link JobLock} advisory locking to run exclusively across instances,
 * and processes each auction in its own transaction via {@link AuctionCloseService}.
 */
@Component
public class AuctionCloseScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuctionCloseScheduler.class);

    static final String LOCK_NAME = "auctionClose";

    /** Stops a batch that keeps failing from looping forever on the same rows. */
    private static final int MAX_ROUNDS = 50;

    private final AuctionRepository auctions;
    private final AuctionCloseService auctionCloseService;
    private final JobLock jobLock;
    private final Clock clock;
    private final int batchSize;

    @Autowired
    public AuctionCloseScheduler(
            AuctionRepository auctions,
            AuctionCloseService auctionCloseService,
            JobLock jobLock,
            Clock clock,
            @Value("${pegasus.auction.close-batch-size:100}") int batchSize) {
        this.auctions = auctions;
        this.auctionCloseService = auctionCloseService;
        this.jobLock = jobLock;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.batchSize = batchSize > 0 ? batchSize : 100;
    }

    public AuctionCloseScheduler(
            AuctionRepository auctions,
            AuctionCloseService auctionCloseService,
            JobLock jobLock,
            Clock clock) {
        this(auctions, auctionCloseService, jobLock, clock, 100);
    }

    @Scheduled(cron = "${pegasus.auction.close-cron:0 */1 * * * *}")
    public void runAuctionClose() {
        processExpiredAuctions();
    }

    public void processExpiredAuctions() {
        jobLock.runExclusively(LOCK_NAME, this::sweep);
    }

    private void sweep() {
        OffsetDateTime now = OffsetDateTime.now(clock);

        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<Long> expiredIds = auctions.findExpiredLiveAuctionIds(now, batchSize);
            if (expiredIds.isEmpty()) {
                return;
            }

            log.info("Processing {} auctions that reached ends_at", expiredIds.size());
            for (Long auctionId : expiredIds) {
                try {
                    auctionCloseService.closeAuction(auctionId, now);
                } catch (Exception e) {
                    log.error("Failed to close auction #{}", auctionId, e);
                }
            }

            if (expiredIds.size() < batchSize) {
                return;
            }
        }

        log.warn("Auction close sweep stopped after {} rounds of {}; the rest waits for the next tick",
                MAX_ROUNDS, batchSize);
    }
}
