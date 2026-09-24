package com.pegasus.pegasustcgapi.acceptance;

import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import io.cucumber.spring.CucumberContextConfiguration;

/**
 * The Spring context the scenarios run in: the same one the integration tests use —
 * the whole application on a random port against the Testcontainers Postgres — so
 * the steps talk to it over real HTTP.
 *
 * <p>JUnit's {@code @BeforeEach} and {@code @AfterEach} in the base class do not run
 * under Cucumber; the hooks in {@link com.pegasus.pegasustcgapi.acceptance.steps.MarketplaceSteps}
 * do the same resetting instead.
 */
@CucumberContextConfiguration
public class CucumberSpringConfiguration extends PostgresIntegrationTest {
}
