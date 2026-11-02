package com.pegasus.pegasustcgapi.config;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Startup guard that prevents the application from booting if mock payment is enabled
 * outside a development profile.
 *
 * <p>The mock payment endpoint ({@code POST /api/v1/orders/{id}/pay}) transitions orders
 * to PAID without requiring or verifying real monetary transactions. It is an allowlist,
 * not a denylist: our deployments run with no Spring profile at all, so a check for
 * {@code prod} would never fire there. Mock payment is only allowed when {@code local}
 * or {@code test} is active (and {@code prod}/{@code production} is not); anything else
 * fails at startup.
 */
@Component
public class MockPaymentProductionGuard {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentProductionGuard.class);
    private static final Set<String> ALLOWED_PROFILES = Set.of("local", "test");
    private static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production");

    private final Environment environment;
    private final boolean mockPaymentEnabled;

    public MockPaymentProductionGuard(
            Environment environment,
            @Value("${pegasus.payment.mock-enabled:false}") boolean mockPaymentEnabled) {
        this.environment = environment;
        this.mockPaymentEnabled = mockPaymentEnabled;
    }

    @PostConstruct
    public void validate() {
        if (!mockPaymentEnabled) {
            log.info("Mock payment endpoint is DISABLED (pegasus.payment.mock-enabled=false).");
            return;
        }
        String[] activeProfiles = environment.getActiveProfiles();
        boolean allowed = Arrays.stream(activeProfiles).map(MockPaymentProductionGuard::normalize)
                .anyMatch(ALLOWED_PROFILES::contains);
        boolean production = Arrays.stream(activeProfiles).map(MockPaymentProductionGuard::normalize)
                .anyMatch(PRODUCTION_PROFILES::contains);
        if (!allowed || production) {
            throw new IllegalStateException(
                    "CRITICAL SECURITY CONFIGURATION ERROR: pegasus.payment.mock-enabled is set to true "
                            + "but active profiles are " + Arrays.toString(activeProfiles) + ". "
                            + "Mock payment is only allowed under the 'local' or 'test' profile.");
        }
        log.warn("Mock payment endpoint is ENABLED (pegasus.payment.mock-enabled=true). "
                + "Active profiles: {}. Verify this is intentional and NOT a production deployment.",
                Arrays.toString(activeProfiles));
    }

    private static String normalize(String profile) {
        return profile == null ? "" : profile.trim().toLowerCase(Locale.ROOT);
    }
}
