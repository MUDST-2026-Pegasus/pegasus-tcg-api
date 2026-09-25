package com.pegasus.pegasustcgapi.controller;

import static com.pegasus.pegasustcgapi.jooq.tables.AuthToken.AUTH_TOKEN;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.ApiClient.Response;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * [CRITICAL] Multi-Device Logout & Token Revocation Integration Test (TCG-389).
 * Simulates logins from multiple devices, asserts active refresh tokens in DB,
 * calls /api/v1/auth/logout-all, verifies tokens are marked revoked in DB,
 * and confirms that previous tokens cannot refresh or call APIs.
 */
@DisplayName("[CRITICAL] Multi-Device Logout & Token Revocation Integration Tests")
class MultiDeviceLogoutIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private PasswordEncoder passwordEncoder;

    private ApiClient client;

    @BeforeEach
    void setUpClient() {
        client = new ApiClient(port);
    }

    @Test
    @DisplayName("[CRITICAL] Logging in from 3 devices, calling /auth/logout-all revokes all refresh tokens in DB and invalidates sessions")
    void multiDeviceLogoutRevokesAllTokens() {
        String email = "multidevice_" + data.tag() + "@example.com";
        String username = "multidevice_" + data.tag();
        String password = "Password123!";

        // 1. Create a user account in DB
        long userId = dsl.insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.EMAIL, email)
                .set(USER_ACCOUNT.USERNAME, username)
                .set(USER_ACCOUNT.DISPLAY_NAME, "Multi Device User")
                .set(USER_ACCOUNT.PASSWORD_HASH, passwordEncoder.encode(password))
                .returningResult(USER_ACCOUNT.ID)
                .fetchSingle(USER_ACCOUNT.ID);

        Map<String, String> loginBody = Map.of(
                "email", email,
                "password", password
        );

        // 2. Device 1 logs in (Desktop)
        Map<String, String> device1Headers = Map.of("User-Agent", "Mozilla/5.0 (Windows NT 10.0)");
        Response loginResp1 = client.post("/api/v1/auth/login", null, loginBody, device1Headers);
        assertThat(loginResp1.status()).isEqualTo(200);
        String accessToken1 = "Bearer " + loginResp1.data().path("tokens").path("accessToken").asText();
        String refreshToken1 = loginResp1.data().path("tokens").path("refreshToken").asText();
        assertThat(refreshToken1).isNotBlank();

        // 3. Device 2 logs in (Mobile iOS)
        Map<String, String> device2Headers = Map.of("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0)");
        Response loginResp2 = client.post("/api/v1/auth/login", null, loginBody, device2Headers);
        assertThat(loginResp2.status()).isEqualTo(200);
        String refreshToken2 = loginResp2.data().path("tokens").path("refreshToken").asText();
        assertThat(refreshToken2).isNotBlank();

        // 4. Device 3 logs in (Tablet iPad)
        Map<String, String> device3Headers = Map.of("User-Agent", "Mozilla/5.0 (iPad; CPU OS 16_0)");
        Response loginResp3 = client.post("/api/v1/auth/login", null, loginBody, device3Headers);
        assertThat(loginResp3.status()).isEqualTo(200);
        String refreshToken3 = loginResp3.data().path("tokens").path("refreshToken").asText();
        assertThat(refreshToken3).isNotBlank();

        // 5. Verify that in DB, there are 3 unrevoked refresh tokens for this user
        int activeCountBefore = dsl.selectCount()
                .from(AUTH_TOKEN)
                .where(AUTH_TOKEN.USER_ID.eq(userId))
                .and(AUTH_TOKEN.REVOKED_AT.isNull())
                .fetchOne(0, int.class);
        assertThat(activeCountBefore).isEqualTo(3);

        // 6. Device 1 calls POST /api/v1/auth/logout-all
        Response logoutAllResp = client.post("/api/v1/auth/logout-all", accessToken1, null);
        assertThat(logoutAllResp.status()).isEqualTo(200);
        assertThat(logoutAllResp.body().path("success").asBoolean()).isTrue();

        // 7. Verify in DB that all refresh tokens for this user now have REVOKED_AT set (revoked in DB)
        int activeCountAfter = dsl.selectCount()
                .from(AUTH_TOKEN)
                .where(AUTH_TOKEN.USER_ID.eq(userId))
                .and(AUTH_TOKEN.REVOKED_AT.isNull())
                .fetchOne(0, int.class);
        assertThat(activeCountAfter).isEqualTo(0);

        int revokedCount = dsl.selectCount()
                .from(AUTH_TOKEN)
                .where(AUTH_TOKEN.USER_ID.eq(userId))
                .and(AUTH_TOKEN.REVOKED_AT.isNotNull())
                .fetchOne(0, int.class);
        assertThat(revokedCount).isEqualTo(3);

        // 8. Device 2 attempts to refresh using refreshToken2 -> rejected with 401 INVALID_TOKEN
        Response refreshResp2 = client.post("/api/v1/auth/refresh", null, Map.of("refreshToken", refreshToken2));
        assertThat(refreshResp2.status()).isEqualTo(401);
        assertThat(refreshResp2.errorCode()).isEqualTo(ErrorCode.INVALID_TOKEN.name());

        // 9. Device 3 attempts to refresh using refreshToken3 -> rejected with 401 INVALID_TOKEN
        Response refreshResp3 = client.post("/api/v1/auth/refresh", null, Map.of("refreshToken", refreshToken3));
        assertThat(refreshResp3.status()).isEqualTo(401);
        assertThat(refreshResp3.errorCode()).isEqualTo(ErrorCode.INVALID_TOKEN.name());

        // 10. Device 1 attempts to refresh using refreshToken1 -> rejected with 401 INVALID_TOKEN
        Response refreshResp1 = client.post("/api/v1/auth/refresh", null, Map.of("refreshToken", refreshToken1));
        assertThat(refreshResp1.status()).isEqualTo(401);
        assertThat(refreshResp1.errorCode()).isEqualTo(ErrorCode.INVALID_TOKEN.name());
    }

    @Test
    @DisplayName("[CONTRAST] Single device logout (/auth/logout) only revokes that device's token; other devices remain active")
    void singleDeviceLogoutOnlyRevokesTargetDevice() {
        String email = "singlelogout_" + data.tag() + "@example.com";
        String username = "singlelogout_" + data.tag();
        String password = "Password123!";

        // 1. Create user account
        long userId = dsl.insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.EMAIL, email)
                .set(USER_ACCOUNT.USERNAME, username)
                .set(USER_ACCOUNT.DISPLAY_NAME, "Single Logout User")
                .set(USER_ACCOUNT.PASSWORD_HASH, passwordEncoder.encode(password))
                .returningResult(USER_ACCOUNT.ID)
                .fetchSingle(USER_ACCOUNT.ID);

        Map<String, String> loginBody = Map.of("email", email, "password", password);

        // 2. Device 1 logs in
        Response loginResp1 = client.post("/api/v1/auth/login", null, loginBody, Map.of("User-Agent", "Device1-Laptop"));
        assertThat(loginResp1.status()).isEqualTo(200);
        String refreshToken1 = loginResp1.data().path("tokens").path("refreshToken").asText();

        // 3. Device 2 logs in
        Response loginResp2 = client.post("/api/v1/auth/login", null, loginBody, Map.of("User-Agent", "Device2-Phone"));
        assertThat(loginResp2.status()).isEqualTo(200);
        String refreshToken2 = loginResp2.data().path("tokens").path("refreshToken").asText();

        // 4. In DB: 2 active tokens
        int activeBefore = dsl.selectCount()
                .from(AUTH_TOKEN)
                .where(AUTH_TOKEN.USER_ID.eq(userId))
                .and(AUTH_TOKEN.REVOKED_AT.isNull())
                .fetchOne(0, int.class);
        assertThat(activeBefore).isEqualTo(2);

        // 5. Device 1 logs out: POST /api/v1/auth/logout with refreshToken1
        Response logoutResp = client.post("/api/v1/auth/logout", null, Map.of("refreshToken", refreshToken1));
        assertThat(logoutResp.status()).isEqualTo(200);

        // 6. In DB: exactly 1 active token remains (Device 2)
        int activeAfter = dsl.selectCount()
                .from(AUTH_TOKEN)
                .where(AUTH_TOKEN.USER_ID.eq(userId))
                .and(AUTH_TOKEN.REVOKED_AT.isNull())
                .fetchOne(0, int.class);
        assertThat(activeAfter).isEqualTo(1);

        // 7. Device 1 refresh fails with 401
        Response refreshResp1 = client.post("/api/v1/auth/refresh", null, Map.of("refreshToken", refreshToken1));
        assertThat(refreshResp1.status()).isEqualTo(401);
        assertThat(refreshResp1.errorCode()).isEqualTo(ErrorCode.INVALID_TOKEN.name());

        // 8. Device 2 refresh succeeds with 200 OK
        Response refreshResp2 = client.post("/api/v1/auth/refresh", null, Map.of("refreshToken", refreshToken2));
        assertThat(refreshResp2.status()).isEqualTo(200);
        assertThat(refreshResp2.data().path("tokens").path("accessToken").asText()).isNotBlank();
    }
}
