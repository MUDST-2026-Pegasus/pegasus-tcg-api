package com.pegasus.pegasustcgapi.controller;

import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.config.AuthProperties;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.ApiClient.Response;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * A burst of parallel wrong passwords must not get more guesses than the lockout
 * allows: each attempt is counted in the database before its password is checked.
 * More parallel requests than the connection pool holds also checks that a burst
 * cannot stall the pool.
 */
class LoginLockoutConcurrencyIntegrationTest extends PostgresIntegrationTest {

    private static final int PARALLEL = 40;
    private static final String PASSWORD = "RightPassword123!";

    @Autowired PasswordEncoder passwordEncoder;
    @Autowired AuthProperties auth;

    @Test
    void parallelWrongPasswordsGetNoMoreGuessesThanTheLimit() throws Exception {
        var user = data.buyer();
        dsl.update(USER_ACCOUNT)
                .set(USER_ACCOUNT.PASSWORD_HASH, passwordEncoder.encode(PASSWORD))
                .where(USER_ACCOUNT.ID.eq(user.userId()))
                .execute();
        ApiClient client = new ApiClient(port);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL);
        List<Future<Response>> calls = new ArrayList<>();
        try {
            for (int i = 0; i < PARALLEL; i++) {
                String guess = "WrongGuess" + i;
                calls.add(pool.submit(() -> {
                    start.await();
                    return client.post(ApiPaths.AUTH + "/login", null,
                            Map.of("email", user.email(), "password", guess));
                }));
            }
            start.countDown();

            int invalid = 0;
            int locked = 0;
            for (Future<Response> call : calls) {
                Response response = call.get();
                if (response.status() == 401) invalid++;
                if (response.status() == 403) locked++;
            }

            // Below the limit a wrong guess is a 401; the guess at the limit and every
            // one after it are refused as locked.
            assertThat(invalid).isLessThanOrEqualTo(auth.maxFailedLogins() - 1);
            assertThat(invalid + locked).isEqualTo(PARALLEL);
        } finally {
            pool.shutdownNow();
        }

        assertThat(dsl.select(USER_ACCOUNT.LOCKED_UNTIL).from(USER_ACCOUNT)
                .where(USER_ACCOUNT.ID.eq(user.userId())).fetchOne().value1()).isNotNull();
        Response rightPassword = client.post(ApiPaths.AUTH + "/login", null,
                Map.of("email", user.email(), "password", PASSWORD));
        assertThat(rightPassword.status()).isEqualTo(403);
        assertThat(rightPassword.errorCode()).isEqualTo("ACCOUNT_LOCKED");
    }
}
