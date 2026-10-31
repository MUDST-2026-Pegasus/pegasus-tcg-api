package com.pegasus.pegasustcgapi.controller;

import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.TokenPurpose;
import com.pegasus.pegasustcgapi.security.ClientInfo;
import com.pegasus.pegasustcgapi.service.AuthTokenService;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.ApiClient.Response;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/** CR1: password recovery and change must invalidate older sessions on real HTTP and PostgreSQL. */
class PasswordWorkflowIntegrationTest extends PostgresIntegrationTest {

    private static final String OLD_PASSWORD = "OldPassword123!";
    private static final String NEW_PASSWORD = "NewPassword456!";

    @Autowired PasswordEncoder passwordEncoder;
    @Autowired AuthTokenService tokens;

    private ApiClient client;
    private String email;
    private long userId;

    @BeforeEach
    void setUpUser() {
        client = new ApiClient(port);
        var user = data.buyer();
        userId = user.userId();
        email = user.email();
        dsl.update(USER_ACCOUNT)
                .set(USER_ACCOUNT.PASSWORD_HASH, passwordEncoder.encode(OLD_PASSWORD))
                .where(USER_ACCOUNT.ID.eq(userId))
                .execute();
    }

    @Test
    void resetPasswordRevokesEveryOldSessionAndEveryOtherResetLink() {
        String firstSession = login(OLD_PASSWORD).refreshToken();
        String secondSession = login(OLD_PASSWORD).refreshToken();
        String resetToken = resetToken();
        String otherResetToken = resetToken();

        Response reset = client.post(ApiPaths.AUTH + "/password/reset", null,
                Map.of("token", resetToken, "newPassword", NEW_PASSWORD));

        assertThat(reset.status()).isEqualTo(200);
        assertInvalidRefresh(firstSession);
        assertInvalidRefresh(secondSession);
        assertThat(login(OLD_PASSWORD).status()).isEqualTo(401);
        assertThat(login(NEW_PASSWORD).status()).isEqualTo(200);
        assertThat(client.post(ApiPaths.AUTH + "/password/reset", null,
                Map.of("token", resetToken, "newPassword", OLD_PASSWORD)).errorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN.name());
        assertThat(client.post(ApiPaths.AUTH + "/password/reset", null,
                Map.of("token", otherResetToken, "newPassword", OLD_PASSWORD)).errorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN.name());
    }

    @Test
    void changePasswordKeepsOnlyTheNewSessionRefreshable() {
        Response firstLogin = login(OLD_PASSWORD);
        String access = firstLogin.data().path("tokens").path("accessToken").asString();
        String firstRefresh = firstLogin.refreshToken();
        String secondRefresh = login(OLD_PASSWORD).refreshToken();

        Response changed = client.post(ApiPaths.AUTH + "/password/change", "Bearer " + access,
                Map.of("currentPassword", OLD_PASSWORD, "newPassword", NEW_PASSWORD));

        assertThat(changed.status()).isEqualTo(200);
        String newRefresh = changed.refreshToken();
        assertThat(newRefresh).isNotBlank();
        assertInvalidRefresh(firstRefresh);
        assertInvalidRefresh(secondRefresh);
        assertThat(client.post(ApiPaths.AUTH + "/refresh", null,
                Map.of("refreshToken", newRefresh)).status()).isEqualTo(200);
        assertThat(login(OLD_PASSWORD).status()).isEqualTo(401);
        assertThat(login(NEW_PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void wrongCurrentPasswordLeavesExistingSessionActive() {
        Response signedIn = login(OLD_PASSWORD);
        String access = signedIn.data().path("tokens").path("accessToken").asString();
        String refresh = signedIn.refreshToken();

        Response rejected = client.post(ApiPaths.AUTH + "/password/change", "Bearer " + access,
                Map.of("currentPassword", "wrong-password", "newPassword", NEW_PASSWORD));

        assertThat(rejected.status()).isEqualTo(401);
        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS.name());
        assertThat(client.post(ApiPaths.AUTH + "/refresh", null,
                Map.of("refreshToken", refresh)).status()).isEqualTo(200);
        assertThat(login(OLD_PASSWORD).status()).isEqualTo(200);
        assertThat(login(NEW_PASSWORD).status()).isEqualTo(401);
    }

    private Response login(String password) {
        return client.post(ApiPaths.AUTH + "/login", null, Map.of("email", email, "password", password));
    }

    private String resetToken() {
        return tokens.issue(userId, TokenPurpose.PASSWORD_RESET, Duration.ofHours(1), UUID.randomUUID(),
                new ClientInfo("password-workflow-test", "127.0.0.1")).value();
    }

    private void assertInvalidRefresh(String refreshToken) {
        Response response = client.post(ApiPaths.AUTH + "/refresh", null, Map.of("refreshToken", refreshToken));
        assertThat(response.status()).isEqualTo(401);
        assertThat(response.errorCode()).isEqualTo(ErrorCode.INVALID_TOKEN.name());
    }
}
