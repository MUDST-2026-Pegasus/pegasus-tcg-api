package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.Promotion;
import com.pegasus.pegasustcgapi.repository.PromotionRepository.NewPromotion;
import java.math.BigDecimal;
import java.util.Set;

/**
 * What may change on a code once it is out in the world, for Pegasus and shop
 * codes alike [TCG-567, TCG-568]. Once a code has been on an order, only its name,
 * description, a later end date, a higher usage limit and the claim section may
 * change. Once someone has claimed it, {@code claim_required} is fixed and
 * {@code claim_limit} can only go up. Anything else is {@code PROMO_IN_USE}.
 */
final class PromotionEditRules {

    private PromotionEditRules() {
    }

    /** @param hasRedemptions whether any order ever held the code, even one that released it */
    static void check(Promotion current, NewPromotion next, boolean hasRedemptions) {
        if (current.usedCount() > 0 || hasRedemptions) {
            requireOnlyEditableAfterUse(current, next);
        }
        if (current.claimedCount() > 0) {
            requireClaimRulesAfterClaims(current, next);
        }
    }

    private static void requireOnlyEditableAfterUse(Promotion c, NewPromotion n) {
        boolean locked = !c.code().equals(n.code())
                || c.discountType() != n.discountType()
                || !sameAmount(c.discountValue(), n.discountValue())
                || !sameAmount(c.maxDiscount(), n.maxDiscount())
                || !sameAmount(c.minSpend(), n.minSpend())
                || c.scopeType() != n.scopeType()
                || !Set.copyOf(c.scopeIds()).equals(Set.copyOf(n.scopeIds()))
                || c.visibility() != n.visibility()
                || c.newBuyerOnly() != n.newBuyerOnly()
                || c.usageLimitPerUser() != n.usageLimitPerUser()
                || c.claimRequired() != n.claimRequired()
                || !c.startsAt().isEqual(n.startsAt())
                || n.endsAt().isBefore(c.endsAt())
                || !notLower(c.usageLimitTotal(), n.usageLimitTotal());
        if (locked) {
            throw new ConflictException(ErrorCode.PROMO_IN_USE, "This code has been used on an order: only its "
                    + "name, description, a later end date, a higher usage limit and the claim settings can change");
        }
    }

    private static void requireClaimRulesAfterClaims(Promotion c, NewPromotion n) {
        if (c.claimRequired() != n.claimRequired() || !notLower(c.claimLimit(), n.claimLimit())) {
            throw new ConflictException(ErrorCode.PROMO_IN_USE, "People have claimed this code: "
                    + "\"must claim before use\" is fixed and the claim limit can only go up");
        }
    }

    /** Null is unlimited, so it is never lower; nothing finite beats it. */
    private static boolean notLower(Integer before, Integer after) {
        if (after == null) return true;
        return before != null && after >= before;
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

}
