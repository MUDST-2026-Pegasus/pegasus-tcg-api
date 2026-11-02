package com.pegasus.pegasustcgapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("MockPaymentProductionGuard Unit Tests")
class MockPaymentProductionGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MockPaymentProductionGuard.class);

    @Test
    @DisplayName("fails startup when pegasus.payment.mock-enabled=true and active profile is 'prod'")
    void failsStartupWhenProdProfileAndMockPaymentEnabled() {
        runner.withPropertyValues("pegasus.payment.mock-enabled=true", "spring.profiles.active=prod")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class);
                    assertThat(context.getStartupFailure().getCause())
                            .hasMessageContaining("CRITICAL SECURITY CONFIGURATION ERROR")
                            .hasMessageContaining("prod");
                });
    }

    @Test
    @DisplayName("fails startup when pegasus.payment.mock-enabled=true and active profile is 'production'")
    void failsStartupWhenProductionProfileAndMockPaymentEnabled() {
        runner.withPropertyValues("pegasus.payment.mock-enabled=true", "spring.profiles.active=production")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class);
                    assertThat(context.getStartupFailure().getCause())
                            .hasMessageContaining("CRITICAL SECURITY CONFIGURATION ERROR")
                            .hasMessageContaining("production");
                });
    }

    @Test
    @DisplayName("fails startup when pegasus.payment.mock-enabled=true and active profile contains multiple including 'prod'")
    void failsStartupWhenMultipleProfilesIncludeProdAndMockPaymentEnabled() {
        runner.withPropertyValues("pegasus.payment.mock-enabled=true", "spring.profiles.active=k8s,prod")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class);
                    assertThat(context.getStartupFailure().getCause())
                            .hasMessageContaining("CRITICAL SECURITY CONFIGURATION ERROR");
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
    @DisplayName("succeeds when pegasus.payment.mock-enabled=true in 'local' profile")
    void succeedsWhenLocalProfileAndMockPaymentEnabled() {
        runner.withPropertyValues("pegasus.payment.mock-enabled=true", "spring.profiles.active=local")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MockPaymentProductionGuard.class);
                });
    }

    @Test
    @DisplayName("succeeds when pegasus.payment.mock-enabled=true in 'test' profile")
    void succeedsWhenTestProfileAndMockPaymentEnabled() {
        runner.withPropertyValues("pegasus.payment.mock-enabled=true", "spring.profiles.active=test")
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
