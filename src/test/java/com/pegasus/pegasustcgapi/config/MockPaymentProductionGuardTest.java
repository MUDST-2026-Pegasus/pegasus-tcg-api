package com.pegasus.pegasustcgapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("MockPaymentProductionGuard Unit Tests")
class MockPaymentProductionGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MockPaymentProductionGuard.class);

    @Test
    @DisplayName("fails startup when pegasus.payment.mock-enabled=true and no profile is active (how deployments run)")
    void failsStartupWhenNoProfileAndMockPaymentEnabled() {
        runner.withPropertyValues("pegasus.payment.mock-enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("CRITICAL SECURITY CONFIGURATION ERROR");
                });
    }

    @ParameterizedTest(name = "fails startup when mock-enabled=true and active profiles are ''{0}''")
    @ValueSource(strings = {"prod", "production", "k8s", "staging", "k8s,prod", "local,prod", "test,production"})
    void failsStartupOutsideAllowedProfiles(String profiles) {
        runner.withPropertyValues("pegasus.payment.mock-enabled=true", "spring.profiles.active=" + profiles)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("CRITICAL SECURITY CONFIGURATION ERROR");
                });
    }

    @ParameterizedTest(name = "succeeds when mock-enabled=true and active profiles are ''{0}''")
    @ValueSource(strings = {"local", "test", "k8s,local", "LOCAL"})
    void succeedsUnderAllowedProfiles(String profiles) {
        runner.withPropertyValues("pegasus.payment.mock-enabled=true", "spring.profiles.active=" + profiles)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MockPaymentProductionGuard.class);
                });
    }

    @Test
    @DisplayName("succeeds when pegasus.payment.mock-enabled=false in 'prod' profile")
    void succeedsWhenProdProfileAndMockPaymentDisabled() {
        runner.withPropertyValues("pegasus.payment.mock-enabled=false", "spring.profiles.active=prod")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MockPaymentProductionGuard.class);
                });
    }

    @Test
    @DisplayName("succeeds by default when pegasus.payment.mock-enabled is false (or omitted)")
    void succeedsByDefaultWhenMockPaymentOmitted() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MockPaymentProductionGuard.class);
        });
    }
}
