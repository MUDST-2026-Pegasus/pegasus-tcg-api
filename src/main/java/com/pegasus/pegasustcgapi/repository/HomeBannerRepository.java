package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.HomeBanner.HOME_BANNER;

import com.pegasus.pegasustcgapi.jooq.tables.records.HomeBannerRecord;
import com.pegasus.pegasustcgapi.model.HomeBanner;
import java.time.OffsetDateTime;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Reads {@code home_banner}. */
@Repository
public class HomeBannerRepository {

    private final DSLContext dsl;

    public HomeBannerRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Switched on, and inside its window at {@code now}, in the order they are shown. */
    public List<HomeBanner> findLive(OffsetDateTime now) {
        return dsl.selectFrom(HOME_BANNER)
                .where(HOME_BANNER.IS_ACTIVE.isTrue())
                .and(HOME_BANNER.STARTS_AT.isNull().or(HOME_BANNER.STARTS_AT.le(now)))
                .and(HOME_BANNER.ENDS_AT.isNull().or(HOME_BANNER.ENDS_AT.gt(now)))
                .orderBy(HOME_BANNER.DISPLAY_ORDER.asc(), HOME_BANNER.ID.asc())
                .fetch(HomeBannerRepository::toBanner);
    }

    private static HomeBanner toBanner(HomeBannerRecord r) {
        return new HomeBanner(
                r.getId(),
                r.getEyebrow(),
                r.getTitle(),
                r.getDescription(),
                r.getImageKey(),
                r.getImageAlt(),
                r.getTheme(),
                r.getPrimaryLabel(),
                r.getPrimaryHref(),
                r.getSecondaryLabel(),
                r.getSecondaryHref(),
                r.getDisplayOrder(),
                r.getIsActive(),
                r.getStartsAt(),
                r.getEndsAt());
    }
}
