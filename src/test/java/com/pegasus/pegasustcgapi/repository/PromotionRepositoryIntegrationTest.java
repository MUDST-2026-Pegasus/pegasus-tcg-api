package com.pegasus.pegasustcgapi.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pegasus.pegasustcgapi.model.Promotion;
import com.pegasus.pegasustcgapi.model.Promotion.DiscountType;
import com.pegasus.pegasustcgapi.model.Promotion.OwnerType;
import com.pegasus.pegasustcgapi.model.Promotion.ScopeType;
import com.pegasus.pegasustcgapi.model.Promotion.Visibility;
import com.pegasus.pegasustcgapi.model.PromotionClaimSource;
import com.pegasus.pegasustcgapi.model.PromotionStatus;
import com.pegasus.pegasustcgapi.repository.PromotionRepository.NewPromotion;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

class PromotionRepositoryIntegrationTest extends PostgresIntegrationTest {

    @Autowired PromotionRepository promotions;
    @Autowired PromotionClaimRepository claims;

    @Test
    void storesAPlatformCodeAndReadsItBackByAnyCase() {
        String code = code("PEG");
        long id = promotions.insert(platformPercent(code, List.of(3L, 5L), 2, null));

        Promotion p = promotions.findByCode(" " + code.toLowerCase(Locale.ROOT) + " ").orElseThrow();

        assertThat(p.id()).isEqualTo(id);
        assertThat(p.code()).isEqualTo(code);
        assertThat(p.ownerType()).isEqualTo(OwnerType.PLATFORM);
        assertThat(p.discountType()).isEqualTo(DiscountType.PERCENT);
        assertThat(p.discountValue()).isEqualByComparingTo("10");
        assertThat(p.maxDiscount()).isEqualByComparingTo("200");
        assertThat(p.scopeType()).isEqualTo(ScopeType.GAME);
        assertThat(p.scopeIds()).containsExactly(3L, 5L);
        assertThat(p.usedCount()).isZero();
        assertThat(p.statusAt(OffsetDateTime.now(clock))).isEqualTo(PromotionStatus.ACTIVE);
        assertThat(promotions.findById(id)).contains(p);
        assertThat(promotions.codeExists(code.toLowerCase(Locale.ROOT))).isTrue();
    }

    @Test
    void oneCodeAcrossPegasusAndEveryShop() {
        String code = code("DUP");
        promotions.insert(platformPercent(code, List.of(), 1, null));
        long shop = data.seller().profileId();

        assertThatThrownBy(() -> promotions.insert(new NewPromotion(code, "shop", null,
                OwnerType.SELLER, shop, DiscountType.FIXED, new BigDecimal("50"), null, BigDecimal.ZERO,
                ScopeType.ALL, List.of(), Visibility.PUBLIC, false, null, 1,
                false, false, null, false, null, OffsetDateTime.now(clock), OffsetDateTime.now(clock).plusDays(1), null)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void schemaRejectsAShopCodeForFreeShipping() {
        long shop = data.seller().profileId();

        assertThatThrownBy(() -> promotions.insert(new NewPromotion(code("SHIP"), "shop", null,
                OwnerType.SELLER, shop, DiscountType.FREE_SHIPPING, BigDecimal.ZERO, null, BigDecimal.ZERO,
                ScopeType.ALL, List.of(), Visibility.PUBLIC, false, null, 1,
                false, false, null, false, null, OffsetDateTime.now(clock), OffsetDateTime.now(clock).plusDays(1), null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void usesStopAtTheLimitAndComeBackOnRelease() {
        long id = promotions.insert(platformPercent(code("LIM"), List.of(), 2, null));

        assertThat(promotions.takeUse(id)).isTrue();
        assertThat(promotions.takeUse(id)).isTrue();
        assertThat(promotions.takeUse(id)).isFalse();
        assertThat(promotions.findById(id).orElseThrow().statusAt(OffsetDateTime.now(clock)))
                .isEqualTo(PromotionStatus.USED_UP);

        assertThat(promotions.releaseUse(id)).isTrue();
        assertThat(promotions.takeUse(id)).isTrue();
    }

    @Test
    void claimingIsOncePerUserAndStopsAtTheClaimLimit() {
        long id = promotions.insert(platformPercent(code("CLM"), List.of(), 10, 1));
        long first = data.buyer().userId();
        long second = data.buyer().userId();

        assertThat(claims.insertIfAbsent(id, first, PromotionClaimSource.HOME)).isTrue();
        assertThat(claims.insertIfAbsent(id, first, PromotionClaimSource.CART)).isFalse();
        assertThat(promotions.takeClaimSlot(id)).isTrue();
        assertThat(promotions.takeClaimSlot(id)).isFalse();

        assertThat(claims.isClaimed(id, first)).isTrue();
        assertThat(claims.isClaimed(id, second)).isFalse();
        assertThat(claims.findClaimedAmong(first, Set.of(id, -1L))).containsExactly(id);
        assertThat(claims.findClaimedAmong(first, Set.of())).isEmpty();
        assertThat(promotions.findById(id).orElseThrow().claimedCount()).isEqualTo(1);
    }

    private String code(String prefix) {
        return (prefix + data.tag()).toUpperCase(Locale.ROOT);
    }

    private NewPromotion platformPercent(String code, List<Long> games, Integer usageLimit, Integer claimLimit) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return new NewPromotion(code, "Pegasus 10%", null, OwnerType.PLATFORM, null,
                DiscountType.PERCENT, new BigDecimal("10"), new BigDecimal("200"), BigDecimal.ZERO,
                games.isEmpty() ? ScopeType.ALL : ScopeType.GAME, games, Visibility.PUBLIC, false,
                usageLimit, 1, claimLimit != null, false, claimLimit, false, null,
                now.minusDays(1), now.plusDays(30), null);
    }
}
