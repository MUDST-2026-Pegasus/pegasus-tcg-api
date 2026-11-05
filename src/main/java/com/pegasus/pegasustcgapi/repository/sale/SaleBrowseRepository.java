package com.pegasus.pegasustcgapi.repository.sale;

import static com.pegasus.pegasustcgapi.jooq.tables.PriceSale.PRICE_SALE;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import com.pegasus.pegasustcgapi.model.sale.PriceSale;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.OwnerType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.SaleType;
import com.pegasus.pegasustcgapi.model.sale.SaleStatus;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectOnConditionStep;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * The sales a buyer can browse [TCG-679]: the SALE page's tabs, one sale's own
 * page and the home page's flash sale. Status is worked out the same way the
 * admin desk does, see {@link AdminSaleRepository#status}.
 */
@Repository
public class SaleBrowseRepository {

    /** How far ahead a flash sale shows as "coming soon". */
    public static final Duration UPCOMING_FLASH = Duration.ofHours(24);

    private final DSLContext dsl;

    public SaleBrowseRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * A sale as buyers see it.
     *
     * @param shopUsername the shop of a SELLER sale; null for Pegasus
     * @param listingCount listings selling at this sale's price right now
     */
    public record Campaign(PriceSale sale, SaleStatus status, String shopUsername, String shopDisplayName,
                           long listingCount) {
    }

    /**
     * Pegasus campaigns running now, and flash sales starting within
     * {@link #UPCOMING_FLASH}. Flash first, then the soonest to end.
     */
    public List<Campaign> findOpenCampaigns(OffsetDateTime now) {
        Field<String> status = AdminSaleRepository.status(now);
        return select(status)
                .where(PRICE_SALE.OWNER_TYPE.eq(OwnerType.PLATFORM.name()))
                .and(open(status, now))
                .orderBy(DSL.when(PRICE_SALE.SALE_TYPE.eq(SaleType.FLASH_SALE.name()), 0).otherwise(1),
                        DSL.when(status.eq(SaleStatus.ACTIVE.name()), 0).otherwise(1),
                        PRICE_SALE.ENDS_AT.asc(), PRICE_SALE.ID.asc())
                .fetch(r -> toCampaign(r, status));
    }

    /** The campaigns an admin put on the home page that are open now, in their home order. */
    public List<Campaign> findFeatured(OffsetDateTime now) {
        Field<String> status = AdminSaleRepository.status(now);
        return select(status)
                .where(PRICE_SALE.FEATURED_ON_HOME.isTrue())
                .and(open(status, now))
                .orderBy(PRICE_SALE.HOME_SORT.asc().nullsLast(), PRICE_SALE.ID.asc())
                .fetch(r -> toCampaign(r, status));
    }

    /** Any sale, Pegasus's or a shop's, whatever its status. */
    public Optional<Campaign> findBySlug(String slug, OffsetDateTime now) {
        Field<String> status = AdminSaleRepository.status(now);
        return select(status)
                .where(PRICE_SALE.SLUG.eq(slug))
                .fetchOptional(r -> toCampaign(r, status));
    }

    /** Running, or a flash sale that starts soon. */
    private static Condition open(Field<String> status, OffsetDateTime now) {
        return status.eq(SaleStatus.ACTIVE.name())
                .or(status.eq(SaleStatus.SCHEDULED.name())
                        .and(PRICE_SALE.SALE_TYPE.eq(SaleType.FLASH_SALE.name()))
                        .and(PRICE_SALE.STARTS_AT.le(now.plus(UPCOMING_FLASH))));
    }

    private SelectOnConditionStep<Record> select(Field<String> status) {
        return dsl.select(PRICE_SALE.fields())
                .select(status, AdminSaleRepository.LISTING_COUNT, USER_ACCOUNT.USERNAME, USER_ACCOUNT.DISPLAY_NAME)
                .from(PRICE_SALE)
                .leftJoin(SELLER_PROFILE).on(SELLER_PROFILE.ID.eq(PRICE_SALE.SELLER_PROFILE_ID))
                .leftJoin(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID));
    }

    private static Campaign toCampaign(Record r, Field<String> status) {
        return new Campaign(PriceSaleRepository.toSale(r.into(PRICE_SALE)), SaleStatus.valueOf(r.get(status)),
                r.get(USER_ACCOUNT.USERNAME), r.get(USER_ACCOUNT.DISPLAY_NAME),
                r.get(AdminSaleRepository.LISTING_COUNT));
    }
}
