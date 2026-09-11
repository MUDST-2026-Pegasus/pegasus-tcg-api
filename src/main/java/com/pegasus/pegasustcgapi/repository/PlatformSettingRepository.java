package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.PlatformSetting.PLATFORM_SETTING;

import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code platform_setting} — the values an admin can change
 * without a deploy [RQ-14].
 *
 * <p>Values are JSONB so a setting can be a number, a string or an object
 * without a schema change. They come back as raw JSON text; turning that into a
 * usable type is {@code PlatformSettingService}'s job.
 */
@Repository
public class PlatformSettingRepository {

    private final DSLContext dsl;

    public PlatformSettingRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<Setting> findAll() {
        return dsl.selectFrom(PLATFORM_SETTING)
                .orderBy(PLATFORM_SETTING.SETTING_KEY.asc())
                .fetch(r -> new Setting(
                        r.getSettingKey(),
                        r.getSettingValue().data(),
                        r.getDescription(),
                        r.getUpdatedBy()));
    }

    public Optional<String> findValue(String key) {
        return dsl.select(PLATFORM_SETTING.SETTING_VALUE)
                .from(PLATFORM_SETTING)
                .where(PLATFORM_SETTING.SETTING_KEY.eq(key))
                .fetchOptional(PLATFORM_SETTING.SETTING_VALUE)
                .map(JSONB::data);
    }

    public boolean exists(String key) {
        return dsl.fetchExists(dsl.selectOne().from(PLATFORM_SETTING)
                .where(PLATFORM_SETTING.SETTING_KEY.eq(key)));
    }

    /** @return true when an existing setting was changed. */
    public boolean update(String key, String jsonValue, long updatedBy) {
        return dsl.update(PLATFORM_SETTING)
                .set(PLATFORM_SETTING.SETTING_VALUE, JSONB.valueOf(jsonValue))
                .set(PLATFORM_SETTING.UPDATED_BY, updatedBy)
                .where(PLATFORM_SETTING.SETTING_KEY.eq(key))
                .execute() > 0;
    }

    public record Setting(String key, String value, String description, Long updatedBy) {
    }
}
