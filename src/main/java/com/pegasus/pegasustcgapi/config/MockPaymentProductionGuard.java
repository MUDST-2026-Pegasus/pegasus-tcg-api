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
 * under a production profile.
 *
 * <p>The mock payment endpoint ({@code POST /api/v1/orders/{id}/pay}) transitions orders
 * to PAID without requiring or verifying real monetary transactions. Enabling it on any
 * production deployment presents a severe vulnerability. This guard enforces a hard fail-fast
 * at startup.
 */
@Component
public class MockPaymentProductionGuard {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentProductionGuard.class);
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
        if (mockPaymentEnabled) {
            String[] activeProfiles = environment.getActiveProfiles();
            for (String profile : activeProfiles) {
                if (isProductionProfile(profile)) {
                    throw new IllegalStateException(
                            "CRITICAL SECURITY CONFIGURATION ERROR: pegasus.payment.mock-enabled is set to true "
                                    + "while production profile ('" + profile + "') is active. "
                                    + "Mock payment must never be enabled in production environments!");
                }
            }
            if (activeProfiles.length == 0) {
                for (String defaultProfile : environment.getDefaultProfiles()) {
                    if (isProductionProfile(defaultProfile)) {
                        throw new IllegalStateException(
                                "CRITICAL SECURITY CONFIGURATION ERROR: pegasus.payment.mock-enabled is set to true "
                                        + "while default production profile ('" + defaultProfile + "') is active. "
                                        + "Mock payment must never be enabled in production environments!");
                    }
                }
            }
            log.warn("Mock payment endpoint is ENABLED (pegasus.payment.mock-enabled=true). "
                    + "Active profiles: {}. Verify this is intentional and NOT a production deployment.",
                    Arrays.toString(activeProfiles));
        } else {
            log.info("Mock payment endpoint is DISABLED (pegasus.payment.mock-enabled=false).");
        }
    }

    private boolean isProductionProfile(String profile) {
        if (profile == null) {
            return false;
        }
        return PRODUCTION_PROFILES.contains(profile.trim().toLowerCase(Locale.ROOT));
    }
}
