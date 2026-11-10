package com.pegasus.pegasustcgapi.service.setting;

import com.pegasus.pegasustcgapi.model.audit.AuditAction;
import com.pegasus.pegasustcgapi.service.audit.AuditLogService;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.repository.setting.PlatformSettingRepository.Setting;
import com.pegasus.pegasustcgapi.repository.setting.PlatformSettingRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    /** How long an unpaid order may hold its reserved cards before it is cancelled. */
    public static final String ORDER_PAYMENT_TIMEOUT_MINUTES = "order.payment_timeout_minutes";
    public static final String AUCTION_PAYMENT_HOURS = "auction.payment_hours";
    public static final String AUCTION_EXTENSION_SECONDS = "auction.extension_seconds";
    public static final String AUCTION_MIN_DURATION_MINUTES = "auction.min_duration_minutes";
    public static final String AUCTION_MAX_DURATION_DAYS = "auction.max_duration_days";
    public static final String AUCTION_STRIKE_LIMIT = "auction.strike_limit";
    public static final String AUCTION_BAN_DAYS = "auction.ban_days";
    public static final String RETURN_REQUEST_WINDOW_DAYS = "return.request_window_days";
    /** Percent applied when no commission_rule matches. */
    public static final String COMMISSION_DEFAULT_RATE = "commission.default_rate";
    public static final String PAYOUT_MINIMUM_AMOUNT = "payout.minimum_amount";
    public static final String PAYMENT_SLIP_AUTO_APPROVE = "payment.slip_auto_approve";
    public static final String PAYMENT_TRANSFER_ACCOUNT = "payment.transfer_account";
    /** Below this many listings the median is noise and AUTO_MEDIAN should skip the variant. */
    public static final String PRICING_MEDIAN_MIN_SAMPLE = "pricing.median_min_sample";

    /** Weekly report: remaining units below this read as low stock. */
    public static final String REPORT_LOW_STOCK_THRESHOLD = "report.low_stock_threshold";
    /** Weekly report: percent of the week's stock sold, above which a row reads as fast moving. */
    public static final String REPORT_FAST_MOVING_PERCENT = "report.fast_moving_percent";

    /** Values that hold a bank account; the log records that they changed, not what to. */
    private static final Set<String> REDACTED_IN_AUDIT = Set.of(PAYMENT_TRANSFER_ACCOUNT);

    private final PlatformSettingRepository settings;
    private final ObjectMapper json;
    private final AuditLogService audit;

    public PlatformSettingService(PlatformSettingRepository settings, ObjectMapper json, AuditLogService audit) {
        this.settings = settings;
        this.json = json;
        this.audit = audit;
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

    public boolean getBoolean(String key) {
        String raw = settings.findValue(key)
                .orElseThrow(() -> new NotFoundException(ErrorCode.SETTING_NOT_FOUND, key));
        if ("true".equals(raw)) return true;
        if ("false".equals(raw)) return false;
        throw new IllegalStateException("Setting " + key + " is not a boolean: " + raw);
    }

    public Duration getDays(String key) {
        return Duration.ofDays(getInt(key));
    }

    public Duration getHours(String key) {
        return Duration.ofHours(getInt(key));
    }

    public Duration getMinutes(String key) {
        return Duration.ofMinutes(getInt(key));
    }

    public Duration getSeconds(String key) {
        return Duration.ofSeconds(getInt(key));
    }

    /** Fractional hours let QA exercise payment expiry without waiting an hour. */
    public Duration getAuctionPaymentWindow() {
        return Duration.ofMillis(getDecimal(AUCTION_PAYMENT_HOURS)
                .multiply(BigDecimal.valueOf(3_600_000)).longValueExact());
    }

    /**
     * Replaces a setting's value.
     *
     * <p>Only keys that already exist can be written: a new one belongs in a
     * migration, alongside the code that reads it.
     *
     * @param rawJson the new value as JSON — {@code 7}, {@code "THB"}, {@code {"a":1}}
     */
    @Transactional
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
        if (COMMISSION_DEFAULT_RATE.equals(key)) {
            try {
                BigDecimal rate = new BigDecimal(rawJson.replace("\"", "").trim());
                if (rate.signum() < 0 || rate.compareTo(new BigDecimal("100")) > 0 || rate.scale() > 3) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "Commission rate must be between 0 and 100 with at most three decimal places");
            }
        }
        if (PAYMENT_SLIP_AUTO_APPROVE.equals(key)) {
            String trimmed = rawJson.replace("\"", "").trim();
            if (!"true".equals(trimmed) && !"false".equals(trimmed)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "Setting " + key + " must be a boolean: true or false");
            }
        }
        if (AUCTION_PAYMENT_HOURS.equals(key)) {
            try {
                if (!rawJson.matches("(0|[1-9][0-9]*)(\\.[0-9]+)?")
                        || new BigDecimal(rawJson).signum() <= 0) throw new NumberFormatException();
                new BigDecimal(rawJson).multiply(BigDecimal.valueOf(3_600_000)).longValueExact();
            } catch (RuntimeException e) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "Setting " + key + " must be positive hours in whole milliseconds");
            }
        }
        if (List.of(AUCTION_EXTENSION_SECONDS,
                AUCTION_STRIKE_LIMIT, AUCTION_BAN_DAYS, AUCTION_MIN_DURATION_MINUTES,
                AUCTION_MAX_DURATION_DAYS).contains(key)) {
            try {
                if (!rawJson.matches("[1-9][0-9]*")) throw new NumberFormatException();
                Integer.parseInt(rawJson);
            } catch (NumberFormatException e) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "Setting " + key + " must be a positive whole number");
            }
        }
        if (REPORT_LOW_STOCK_THRESHOLD.equals(key) && !rawJson.matches("0|[1-9][0-9]{0,8}")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Setting " + key + " must be a whole number of units, zero or more");
        }
        if (REPORT_FAST_MOVING_PERCENT.equals(key) && !rawJson.matches("[1-9][0-9]?|100")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Setting " + key + " must be a whole percent from 1 to 100");
        }
        String before = settings.findValue(key).orElse("null");
        settings.update(key, rawJson, adminId);
        Setting after = get(key);
        // The settings response has no history of its own; this row is it.
        audit.record(adminId, AuditAction.SETTING_UPDATED, key, "Changed setting " + key,
                REDACTED_IN_AUDIT.contains(key)
                        ? AuditLogService.meta("redacted", true)
                        : AuditLogService.change(readJson(before), readJson(after.value())));
        return after;
    }

    /** Stored values are JSON already; parsed so the log shows 7, not "7". */
    private Object readJson(String raw) {
        return json.readTree(raw);
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
