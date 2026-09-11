package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.SellerShippingOption.SELLER_SHIPPING_OPTION;

import com.pegasus.pegasustcgapi.jooq.tables.records.SellerShippingOptionRecord;
import com.pegasus.pegasustcgapi.model.ShippingOption;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Reads and writes {@code seller_shipping_option} — what a seller charges to post an order [RQ-12]. */
@Repository
public class ShippingOptionRepository {

    private final DSLContext dsl;

    public ShippingOptionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<ShippingOption> findBySellerProfileId(long sellerProfileId, boolean activeOnly) {
        return dsl.selectFrom(SELLER_SHIPPING_OPTION)
                .where(SELLER_SHIPPING_OPTION.SELLER_PROFILE_ID.eq(sellerProfileId))
                .and(activeOnly ? SELLER_SHIPPING_OPTION.IS_ACTIVE.isTrue() : org.jooq.impl.DSL.noCondition())
                .orderBy(SELLER_SHIPPING_OPTION.DISPLAY_ORDER.asc(), SELLER_SHIPPING_OPTION.ID.asc())
                .fetch(ShippingOptionRepository::toOption);
    }

    public Optional<ShippingOption> findByIdAndSeller(long id, long sellerProfileId) {
        return dsl.selectFrom(SELLER_SHIPPING_OPTION)
                .where(SELLER_SHIPPING_OPTION.ID.eq(id))
                .and(SELLER_SHIPPING_OPTION.SELLER_PROFILE_ID.eq(sellerProfileId))
                .fetchOptional()
                .map(ShippingOptionRepository::toOption);
    }

    public long insert(long sellerProfileId, OptionFields f) {
        return dsl.insertInto(SELLER_SHIPPING_OPTION)
                .set(SELLER_SHIPPING_OPTION.SELLER_PROFILE_ID, sellerProfileId)
                .set(apply(dsl.newRecord(SELLER_SHIPPING_OPTION), f))
                .returningResult(SELLER_SHIPPING_OPTION.ID)
                .fetchSingle(SELLER_SHIPPING_OPTION.ID);
    }

    public boolean update(long id, long sellerProfileId, OptionFields f) {
        return dsl.update(SELLER_SHIPPING_OPTION)
                .set(apply(dsl.newRecord(SELLER_SHIPPING_OPTION), f))
                .where(SELLER_SHIPPING_OPTION.ID.eq(id))
                .and(SELLER_SHIPPING_OPTION.SELLER_PROFILE_ID.eq(sellerProfileId))
                .execute() > 0;
    }

    /**
     * Deactivates rather than deletes: a past order points at the option it was
     * shipped under, and that reference has to keep resolving.
     */
    public boolean deactivate(long id, long sellerProfileId) {
        return dsl.update(SELLER_SHIPPING_OPTION)
                .set(SELLER_SHIPPING_OPTION.IS_ACTIVE, false)
                .where(SELLER_SHIPPING_OPTION.ID.eq(id))
                .and(SELLER_SHIPPING_OPTION.SELLER_PROFILE_ID.eq(sellerProfileId))
                .execute() > 0;
    }

    private static SellerShippingOptionRecord apply(SellerShippingOptionRecord r, OptionFields f) {
        r.setName(f.name());
        r.setCarrierCode(f.carrierCode());
        r.setBaseFee(f.baseFee());
        r.setPerItemFee(f.perItemFee());
        r.setFreeThreshold(f.freeThreshold());
        r.setEstDaysMin(f.estDaysMin());
        r.setEstDaysMax(f.estDaysMax());
        r.setIsActive(f.active());
        r.setDisplayOrder(f.displayOrder());
        return r;
    }

    private static ShippingOption toOption(SellerShippingOptionRecord r) {
        return new ShippingOption(
                r.getId(),
                r.getSellerProfileId(),
                r.getName(),
                r.getCarrierCode(),
                r.getBaseFee(),
                r.getPerItemFee(),
                r.getFreeThreshold(),
                r.getEstDaysMin(),
                r.getEstDaysMax(),
                r.getIsActive(),
                r.getDisplayOrder());
    }

    public record OptionFields(
            String name,
            String carrierCode,
            BigDecimal baseFee,
            BigDecimal perItemFee,
            BigDecimal freeThreshold,
            Short estDaysMin,
            Short estDaysMax,
            boolean active,
            short displayOrder) {
    }
}
