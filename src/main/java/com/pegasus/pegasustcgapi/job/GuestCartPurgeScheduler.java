package com.pegasus.pegasustcgapi.job;

import com.pegasus.pegasustcgapi.repository.CartRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Clears out signed-out baskets nobody came back for.
 *
 * <p>{@code POST /api/v1/cart/items} is open to anyone, so a guest cart costs a
 * caller one unauthenticated request and nothing else. {@code cart.expires_at} was
 * already being set but nothing ever read it, which left the table growing with no
 * upper bound. This is the same housekeeping the auth module does for spent tokens.
 */
@Component
public class GuestCartPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(GuestCartPurgeScheduler.class);

    static final String LOCK_NAME = "guestCartPurge";

    private final CartRepository cartRepository;
    private final JobLock jobLock;
    private final Clock clock;

    @Autowired
    public GuestCartPurgeScheduler(CartRepository cartRepository, JobLock jobLock, Clock clock) {
        this.cartRepository = cartRepository;
        this.jobLock = jobLock;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Scheduled(cron = "${pegasus.cart.guest-purge-cron:0 30 3 * * *}")
    public void runPurge() {
        purgeExpiredGuestCarts();
    }

    public void purgeExpiredGuestCarts() {
        jobLock.runExclusively(LOCK_NAME, () -> {
            OffsetDateTime cutoff = OffsetDateTime.now(clock);
            int deleted = cartRepository.deleteExpiredGuestCarts(cutoff);
            if (deleted > 0) {
                log.info("Purged {} expired guest carts", deleted);
            }
        });
    }
}
