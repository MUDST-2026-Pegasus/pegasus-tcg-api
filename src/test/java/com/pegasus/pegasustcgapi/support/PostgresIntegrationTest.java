package com.pegasus.pegasustcgapi.support;

import static org.mockito.Mockito.reset;

import com.pegasus.pegasustcgapi.port.LedgerPort;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base for integration tests that need the real schema, real transactions and
 * real row locks: the whole application on a random port, against a throwaway
 * Postgres that Flyway migrates on start-up.
 *
 * <p>Every subclass shares one container and — because none of them adds bean
 * overrides of its own — one cached Spring context, so the cost of starting both
 * is paid once per test run rather than once per class. Tests do not roll back;
 * each seeds its own rows under a unique tag (see {@link TestData}) so they cannot
 * see one another's data.
 *
 * <p>The scheduled sweeps are switched off: a cron firing in the middle of a test
 * would cancel or complete orders the test is still looking at. Tests that are
 * about those sweeps call them directly, with the clock moved where they need it.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
                "pegasus.order.payment-timeout-cron=-",
                "pegasus.order.auto-release-cron=-",
                "pegasus.cart.guest-purge-cron=-",
                "pegasus.pricing.market-job-cron=-",
                "pegasus.payment.mock-enabled=true"
        })
@Import(IntegrationTestConfig.class)
public abstract class PostgresIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        // Started once for the JVM rather than per class; Ryuk removes it at exit.
        POSTGRES.start();
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected DSLContext dsl;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected TestData data;

    /** A spy, so escrow release can be counted while the real ledger still runs. */
    @MockitoSpyBean
    protected LedgerPort ledgerPort;

    @BeforeEach
    void resetSharedState() {
        clock.reset();
        reset(ledgerPort);
    }

    @AfterEach
    void restoreSettings() {
        data.restoreDefaultSettings();
    }
}
