package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.repository.PlatformSettingRepository.Setting;
import com.pegasus.pegasustcgapi.repository.PlatformSettingRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Values an admin can change without a deploy [RQ-14] — how long escrow waits,
 * the fallback commission rate, the cancellation window.
 *
 * <p>Keys are fixed in code and seeded by migration: this is configuration with
 * a known shape, not a free-form key-value store, so a typo fails loudly instead
 * of silently reading as absent.
 */
@Service
public class PlatformSettingService {

    /** How long after delivery escrow releases on its own when the buyer never confirms. */
    public static final String ESCROW_AUTO_RELEASE_DAYS = "escrow.auto_release_days";
    public static final String ORDER_CANCEL_WINDOW_HOURS = "order.cancel_window_hours";
    public static final String RETURN_REQUEST_WINDOW_DAYS = "return.request_window_days";
    /** Percent applied when no commission_rule matches. */
    public static final String COMMISSION_DEFAULT_RATE = "commission.default_rate";
    public static final String PAYOUT_MINIMUM_AMOUNT = "payout.minimum_amount";
    /** Below this many listings the median is noise and AUTO_MEDIAN should skip the variant. */
    public static final String PRICING_MEDIAN_MIN_SAMPLE = "pricing.median_min_sample";

    private final PlatformSettingRepository settings;
    private final ObjectMapper json;

    public PlatformSettingService(PlatformSettingRepository settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
    }

    public List<Setting> list() {
        return settings.findAll();
    }

    public Setting get(String key) {
        return list().stream()
                .filter(s -> s.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new NotFoundException(ErrorCode.SETTING_NOT_FOUND, key));
    }

    public int getInt(String key) {
        return readValue(key).intValue();
    }

    public BigDecimal getDecimal(String key) {
        return readValue(key);
    }

    public Duration getDays(String key) {
        return Duration.ofDays(getInt(key));
    }

    public Duration getHours(String key) {
        return Duration.ofHours(getInt(key));
    }

    /**
     * Replaces a setting's value.
     *
     * <p>Only keys that already exist can be written: a new one belongs in a
     * migration, alongside the code that reads it.
     *
     * @param rawJson the new value as JSON — {@code 7}, {@code "THB"}, {@code {"a":1}}
     */
    public Setting update(String key, String rawJson, long adminId) {
        if (!settings.exists(key)) {
            throw new NotFoundException(ErrorCode.SETTING_NOT_FOUND, key);
        }
        try {
            json.readTree(rawJson);
        } catch (JacksonException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Value for " + key + " is not valid JSON");
        }
        settings.update(key, rawJson, adminId);
        return get(key);
    }

    /**
     * A missing or non-numeric setting is a broken deployment, not a runtime
     * condition to paper over — the seed migration is supposed to guarantee both.
     */
    private BigDecimal readValue(String key) {
        String raw = settings.findValue(key)
                .orElseThrow(() -> new NotFoundException(ErrorCode.SETTING_NOT_FOUND, key));
        try {
            return new BigDecimal(raw.replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Setting " + key + " is not a number: " + raw, e);
        }
    }
}
