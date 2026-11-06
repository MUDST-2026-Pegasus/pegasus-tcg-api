package com.pegasus.pegasustcgapi.job.auction;

import com.pegasus.pegasustcgapi.job.JobLock;
import com.pegasus.pegasustcgapi.repository.auction.AuctionRepository;
import com.pegasus.pegasustcgapi.service.auction.AuctionPaymentTimeoutService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AuctionPaymentTimeoutScheduler {
    private static final Logger log = LoggerFactory.getLogger(AuctionPaymentTimeoutScheduler.class);
    static final String LOCK_NAME = "auctionPaymentTimeout";
    private static final int MAX_ROUNDS = 50;

    private final AuctionRepository auctions;
    private final AuctionPaymentTimeoutService timeout;
    private final JobLock lock;
    private final Clock clock;
    private final int batchSize;
    private final Set<Long> failedAuctions = ConcurrentHashMap.newKeySet();

    public AuctionPaymentTimeoutScheduler(AuctionRepository auctions, AuctionPaymentTimeoutService timeout,
            JobLock lock, Clock clock, @Value("${pegasus.auction.payment-timeout-batch-size:100}") int batchSize) {
        this.auctions = auctions;
        this.timeout = timeout;
        this.lock = lock;
        this.clock = clock;
        this.batchSize = batchSize > 0 ? batchSize : 100;
    }

    @Scheduled(cron = "${pegasus.auction.payment-timeout-cron:0 */1 * * * *}")
    public void runPaymentTimeout() {
        processExpiredAuctions();
    }

    public void processExpiredAuctions() {
        lock.runExclusively(LOCK_NAME, this::sweep);
    }

    private void sweep() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Set<Long> seen = new HashSet<>();
        long afterId = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            // Page forward by id: an auction that fails or is skipped stays behind the cursor.
            List<Long> ids = auctions.findExpiredPaymentAuctionIds(now, afterId, batchSize);
            for (Long id : ids) {
                seen.add(id);
                try {
                    if (timeout.expire(id, now)) failedAuctions.remove(id);
                } catch (Exception e) {
                    if (failedAuctions.add(id)) {
                        log.warn("Failed to expire unpaid auction #{}; subsequent failures will be suppressed until it succeeds", id, e);
                    }
                }
            }
            if (ids.size() < batchSize) {
                // A full pass saw every auction still due; forget failures that left the queue.
                failedAuctions.retainAll(seen);
                return;
            }
            afterId = ids.getLast();
        }
        log.warn("Auction payment timeout stopped after {} rounds", MAX_ROUNDS);
    }
}
