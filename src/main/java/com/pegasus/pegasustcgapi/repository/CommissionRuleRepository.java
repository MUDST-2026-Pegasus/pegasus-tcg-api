package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.CommissionRule.COMMISSION_RULE;
import static com.pegasus.pegasustcgapi.jooq.tables.Game.GAME;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogCategory.CATALOG_CATEGORY;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;

import com.pegasus.pegasustcgapi.dto.CommissionRuleRequest;
import com.pegasus.pegasustcgapi.jooq.tables.records.CommissionRuleRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class CommissionRuleRepository {

    private final DSLContext dsl;

    public CommissionRuleRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<ProductScope> productScopes(Collection<Long> variantIds) {
        if (variantIds.isEmpty()) return List.of();
        return dsl.select(CATALOG_VARIANT.ID, CATALOG_PRODUCT.GAME_ID, CATALOG_PRODUCT.CATEGORY_ID)
                .from(CATALOG_VARIANT)
                .join(CATALOG_PRODUCT).on(CATALOG_PRODUCT.ID.eq(CATALOG_VARIANT.CATALOG_PRODUCT_ID))
                .where(CATALOG_VARIANT.ID.in(variantIds))
                .fetch(r -> new ProductScope(r.value1(), r.value2(), r.value3()));
    }

    public List<Rule> activeAt(OffsetDateTime at) {
        return dsl.selectFrom(COMMISSION_RULE)
                .where(COMMISSION_RULE.IS_ACTIVE.isTrue())
                .and(COMMISSION_RULE.EFFECTIVE_FROM.le(at))
                .and(COMMISSION_RULE.EFFECTIVE_TO.isNull().or(COMMISSION_RULE.EFFECTIVE_TO.gt(at)))
                .fetch(r -> new Rule(r.getId(), r.getScope(), r.getGameId(), r.getCategoryId(),
                        r.getSellerProfileId(), r.getRatePercent(), r.getFixedFee(),
                        r.getMinFee(), r.getMaxFee(), r.getPriority()));
    }

    public List<Detail> findAll() {
        return dsl.selectFrom(COMMISSION_RULE).orderBy(COMMISSION_RULE.ID.desc())
                .fetch(CommissionRuleRepository::detail);
    }

    public Optional<Detail> findById(long id) {
        return dsl.selectFrom(COMMISSION_RULE).where(COMMISSION_RULE.ID.eq(id))
                .fetchOptional(CommissionRuleRepository::detail);
    }

    public boolean targetExists(String scope, Short gameId, Integer categoryId, Long sellerProfileId) {
        return switch (scope) {
            case "GAME" -> dsl.fetchExists(dsl.selectOne().from(GAME).where(GAME.ID.eq(gameId)));
            case "CATEGORY" -> dsl.fetchExists(dsl.selectOne().from(CATALOG_CATEGORY)
                    .where(CATALOG_CATEGORY.ID.eq(categoryId)));
            case "SELLER" -> dsl.fetchExists(dsl.selectOne().from(SELLER_PROFILE)
                    .where(SELLER_PROFILE.ID.eq(sellerProfileId)));
            default -> true;
        };
    }

    public long insert(CommissionRuleRequest request, long adminId) {
        return dsl.insertInto(COMMISSION_RULE)
                .set(COMMISSION_RULE.SCOPE, request.scope())
                .set(COMMISSION_RULE.GAME_ID, request.gameId())
                .set(COMMISSION_RULE.CATEGORY_ID, request.categoryId())
                .set(COMMISSION_RULE.SELLER_PROFILE_ID, request.sellerProfileId())
                .set(COMMISSION_RULE.RATE_PERCENT, request.ratePercent())
                .set(COMMISSION_RULE.FIXED_FEE, request.fixedFee())
                .set(COMMISSION_RULE.MIN_FEE, request.minFee())
                .set(COMMISSION_RULE.MAX_FEE, request.maxFee())
                .set(COMMISSION_RULE.PRIORITY, request.priority())
                .set(COMMISSION_RULE.EFFECTIVE_FROM, request.effectiveFrom())
                .set(COMMISSION_RULE.EFFECTIVE_TO, request.effectiveTo())
                .set(COMMISSION_RULE.IS_ACTIVE, request.active() == null || request.active())
                .set(COMMISSION_RULE.CREATED_BY, adminId)
                .returningResult(COMMISSION_RULE.ID).fetchSingle(COMMISSION_RULE.ID);
    }

    public void update(long id, CommissionRuleRequest request) {
        dsl.update(COMMISSION_RULE)
                .set(COMMISSION_RULE.SCOPE, request.scope())
                .set(COMMISSION_RULE.GAME_ID, request.gameId())
                .set(COMMISSION_RULE.CATEGORY_ID, request.categoryId())
                .set(COMMISSION_RULE.SELLER_PROFILE_ID, request.sellerProfileId())
                .set(COMMISSION_RULE.RATE_PERCENT, request.ratePercent())
                .set(COMMISSION_RULE.FIXED_FEE, request.fixedFee())
                .set(COMMISSION_RULE.MIN_FEE, request.minFee())
                .set(COMMISSION_RULE.MAX_FEE, request.maxFee())
                .set(COMMISSION_RULE.PRIORITY, request.priority())
                .set(COMMISSION_RULE.EFFECTIVE_FROM, request.effectiveFrom())
                .set(COMMISSION_RULE.EFFECTIVE_TO, request.effectiveTo())
                .set(COMMISSION_RULE.IS_ACTIVE, request.active() == null || request.active())
                .where(COMMISSION_RULE.ID.eq(id)).execute();
    }

    public void deactivate(long id) {
        dsl.update(COMMISSION_RULE).set(COMMISSION_RULE.IS_ACTIVE, false)
                .where(COMMISSION_RULE.ID.eq(id)).execute();
    }

    private static Detail detail(CommissionRuleRecord r) {
        return new Detail(r.getId(), r.getScope(), r.getGameId(), r.getCategoryId(),
                r.getSellerProfileId(), r.getRatePercent(), r.getFixedFee(), r.getMinFee(),
                r.getMaxFee(), r.getPriority(), r.getEffectiveFrom(), r.getEffectiveTo(),
                r.getIsActive());
    }

    public record ProductScope(long variantId, short gameId, int categoryId) {
    }

    public record Rule(long id, String scope, Short gameId, Integer categoryId,
            Long sellerProfileId, BigDecimal rate, BigDecimal fixed,
            BigDecimal minimum, BigDecimal maximum, short priority) {
    }

    public record Detail(long id, String scope, Short gameId, Integer categoryId,
            Long sellerProfileId, BigDecimal ratePercent, BigDecimal fixedFee,
            BigDecimal minFee, BigDecimal maxFee, short priority,
            OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo, boolean active) {
    }
}
