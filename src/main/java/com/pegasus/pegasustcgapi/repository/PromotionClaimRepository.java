package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.PromotionClaim.PROMOTION_CLAIM;

import com.pegasus.pegasustcgapi.model.PromotionClaimSource;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Reads and writes {@code promotion_claim} — the codes a user has collected [TCG-566]. */
@Repository
public class PromotionClaimRepository {

    private final DSLContext dsl;

    public PromotionClaimRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Records the claim unless the user already has one. False on a repeat, so a
     * double tap is harmless; pair a true with {@link PromotionRepository#takeClaimSlot}
     * in the same transaction to keep {@code claimed_count} honest.
     */
    public boolean insertIfAbsent(long promotionId, long userId, PromotionClaimSource source) {
        return dsl.insertInto(PROMOTION_CLAIM)
                .set(PROMOTION_CLAIM.PROMOTION_ID, promotionId)
                .set(PROMOTION_CLAIM.USER_ID, userId)
                .set(PROMOTION_CLAIM.SOURCE, source.name())
                .onConflict(PROMOTION_CLAIM.PROMOTION_ID, PROMOTION_CLAIM.USER_ID)
                .doNothing()
                .execute() > 0;
    }

    /** One collected code in a user's wallet. */
    public record Claim(long id, long promotionId, PromotionClaimSource source, OffsetDateTime claimedAt) {
    }

    public Optional<Claim> find(long promotionId, long userId) {
        return dsl.selectFrom(PROMOTION_CLAIM)
                .where(PROMOTION_CLAIM.PROMOTION_ID.eq(promotionId))
                .and(PROMOTION_CLAIM.USER_ID.eq(userId))
                .fetchOptional(r -> new Claim(r.getId(), r.getPromotionId(),
                        PromotionClaimSource.valueOf(r.getSource()), r.getClaimedAt()));
    }

    public boolean isClaimed(long promotionId, long userId) {
        return dsl.fetchExists(PROMOTION_CLAIM,
                PROMOTION_CLAIM.PROMOTION_ID.eq(promotionId).and(PROMOTION_CLAIM.USER_ID.eq(userId)));
    }

    /** Which of {@code promotionIds} this user has collected. */
    public Set<Long> findClaimedAmong(long userId, Collection<Long> promotionIds) {
        if (promotionIds.isEmpty()) {
            return Set.of();
        }
        return dsl.select(PROMOTION_CLAIM.PROMOTION_ID)
                .from(PROMOTION_CLAIM)
                .where(PROMOTION_CLAIM.USER_ID.eq(userId))
                .and(PROMOTION_CLAIM.PROMOTION_ID.in(promotionIds))
                .fetchSet(PROMOTION_CLAIM.PROMOTION_ID);
    }
}
