package com.pegasus.pegasustcgapi.controller.auth;

import static com.pegasus.pegasustcgapi.jooq.tables.AuthToken.AUTH_TOKEN;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;
import static com.pegasus.pegasustcgapi.jooq.tables.UserIdentity.USER_IDENTITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willReturn;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.auth.TokenPurpose;
import com.pegasus.pegasustcgapi.model.user.RoleCode;
import com.pegasus.pegasustcgapi.model.user.UserStatus;
import com.pegasus.pegasustcgapi.repository.user.RoleRepository;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.security.ClientInfo;
import com.pegasus.pegasustcgapi.service.auth.AuthTokenService;
import com.pegasus.pegasustcgapi.service.auth.GoogleTokenVerifier.GoogleIdentity;
import com.pegasus.pegasustcgapi.service.user.RoleService;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.ApiClient.Response;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

@DisplayName("Sign in with Google Integration Tests")
class GoogleSignInIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "OldPassword123!";

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AuthTokenService authTokens;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RoleService roleService;

    private ApiClient client;
    private String tag;

    @BeforeEach
    void setUp() {
        client = new ApiClient(port);
        tag = data.tag();
    }

    @Test
    @DisplayName("returns 503 GOOGLE_SIGNIN_DISABLED when client-id is not configured")
    void returns503WhenClientIdIsNotConfigured() {
        // Without stubbing googleTokenVerifier, the real DefaultGoogleTokenVerifier runs
        // with the empty default pegasus.auth.google.client-id from application.yaml.
        Response response = client.post(ApiPaths.AUTH + "/google", null,
                Map.of("credential", "any-google-token"));

        assertThat(response.status()).isEqualTo(503);
        assertThat(response.errorCode()).isEqualTo(ErrorCode.GOOGLE_SIGNIN_DISABLED.name());
    }

    @Test
    @DisplayName("registers a new buyer with 201 Created, sets pegasus_rt cookie, and signs in with 200 OK even after Google email changes")
    void registersNewUserAndLogsInAgainBySubjectWhenEmailChanges() {
        String sub = "google-sub-new-" + tag;
        String email = "newbuyer_" + tag + "@gmail.com";
        String changedEmail = "changed_" + tag + "@gmail.com";

        willReturn(new GoogleIdentity(sub, email, true, "Ploy Google", null))
                .given(googleTokenVerifier).verify("cred-1");

        Response created = client.post(ApiPaths.AUTH + "/google", null, Map.of("credential", "cred-1"));

        assertThat(created.status()).isEqualTo(201);
        long userId = created.data().path("user").path("id").asLong();
        String username = created.data().path("user").path("username").asString();
        assertThat(username).matches("^user_[a-z0-9]{6}$");
        assertThat(created.data().path("user").path("displayName").asString()).isEqualTo("Ploy Google");
        assertThat(created.data().path("user").path("email").asString()).isEqualTo(email);
        assertThat(created.data().path("user").path("roles").get(0).asString()).isEqualTo("BUYER");
        assertThat(created.data().path("tokens").path("accessToken").asString()).isNotBlank();
        assertThat(created.data().path("tokens").has("refreshToken")).isFalse();

        // Check pegasus_rt cookie attributes
        String setCookie = created.header("set-cookie").orElse("");
        assertThat(setCookie)
                .contains("pegasus_rt=")
                .contains("HttpOnly")
                .contains("Path=/api/v1/auth")
                .contains("SameSite=")
                .contains("Max-Age=");
        assertThat(created.refreshToken()).isNotBlank();

        // Verify database state
        var userRecord = dsl.selectFrom(USER_ACCOUNT).where(USER_ACCOUNT.ID.eq(userId)).fetchSingle();
        assertThat(userRecord.getPasswordHash()).isNull();
        assertThat(userRecord.getEmailVerifiedAt()).isNotNull();

        var identityRecord = dsl.selectFrom(USER_IDENTITY)
                .where(USER_IDENTITY.USER_ID.eq(userId))
                .fetchSingle();
        assertThat(identityRecord.getProvider()).isEqualTo("GOOGLE");
        assertThat(identityRecord.getProviderSubject()).isEqualTo(sub);
        assertThat(identityRecord.getLastLoginAt()).isNotNull();

        // Advance clock and sign in again with the same sub but a changed Google email
        clock.advance(Duration.ofMinutes(10));
        willReturn(new GoogleIdentity(sub, changedEmail, true, "Ploy Updated", null))
                .given(googleTokenVerifier).verify("cred-2");

        Response secondLogin = client.post(ApiPaths.AUTH + "/google", null, Map.of("credential", "cred-2"));

        assertThat(secondLogin.status()).isEqualTo(200);
        assertThat(secondLogin.data().path("user").path("id").asLong()).isEqualTo(userId);
        assertThat(secondLogin.refreshToken()).isNotBlank();

        var updatedIdentity = dsl.selectFrom(USER_IDENTITY)
                .where(USER_IDENTITY.USER_ID.eq(userId))
                .fetchSingle();
        assertThat(updatedIdentity.getLastLoginAt()).isAfter(identityRecord.getLastLoginAt());
    }

    @Test
    @DisplayName("concurrent sign-up requests for the same Google identity all succeed and create a single user")
    void concurrentRequestsForSameGoogleIdentityCreateSingleUser() throws Exception {
        String sub = "google-sub-race-" + tag;
        String email = "race_" + tag + "@gmail.com";
        GoogleIdentity identity = new GoogleIdentity(sub, email, true, "Concurrent Buyer", null);
        willAnswer(inv -> identity).given(googleTokenVerifier).verify("cred-race");

        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Response>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return client.post(ApiPaths.AUTH + "/google", null, Map.of("credential", "cred-race"));
                }));
            }
            ready.await();
            start.countDown();

            List<Long> userIds = new ArrayList<>();
            int createdCount = 0;
            int okCount = 0;
            for (Future<Response> future : futures) {
                Response resp = future.get();
                assertThat(resp.status()).isIn(200, 201);
                if (resp.status() == 201) createdCount++;
                if (resp.status() == 200) okCount++;
                userIds.add(resp.data().path("user").path("id").asLong());
                assertThat(resp.refreshToken()).isNotBlank();
            }

            assertThat(createdCount).isEqualTo(1);
            assertThat(okCount).isEqualTo(threads - 1);
            assertThat(userIds).allMatch(id -> id.equals(userIds.getFirst()));

            int accountCount = dsl.selectCount().from(USER_ACCOUNT)
                    .where(USER_ACCOUNT.EMAIL.eq(email))
                    .fetchOne(0, int.class);
            int identityCount = dsl.selectCount().from(USER_IDENTITY)
                    .where(USER_IDENTITY.PROVIDER_SUBJECT.eq(sub))
                    .fetchOne(0, int.class);
            assertThat(accountCount).isEqualTo(1);
            assertThat(identityCount).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("links existing @gmail.com account, clears password and lockout, and revokes old refresh & reset tokens")
    void linksExistingGmailAccountClearsPasswordAndRevokesOldTokens() {
        String email = "existing_gmail_" + tag + "@gmail.com";
        long userId = createPasswordUser("gmail_" + tag, email, RoleCode.BUYER);

        // Open an active password session and a password reset token before linking
        Response oldLogin = client.post(ApiPaths.AUTH + "/login", null,
                Map.of("email", email, "password", PASSWORD));
        assertThat(oldLogin.status()).isEqualTo(200);
        String oldRefreshToken = oldLogin.refreshToken();
        String oldResetToken = authTokens.issue(userId, TokenPurpose.PASSWORD_RESET, Duration.ofHours(1),
                UUID.randomUUID(), new ClientInfo("test", "127.0.0.1")).value();

        // Simulate a password lockout on the account; Google sign-in must NOT be blocked by locked_until
        dsl.update(USER_ACCOUNT)
                .set(USER_ACCOUNT.FAILED_LOGIN_ATTEMPTS, (short) 5)
                .set(USER_ACCOUNT.LOCKED_UNTIL, OffsetDateTime.now().plusMinutes(15))
                .where(USER_ACCOUNT.ID.eq(userId))
                .execute();

        String sub = "google-sub-gmail-" + tag;
        willReturn(new GoogleIdentity(sub, email, true, "Gmail User", null))
                .given(googleTokenVerifier).verify("cred-gmail-link");

        Response linked = client.post(ApiPaths.AUTH + "/google", null, Map.of("credential", "cred-gmail-link"));

        assertThat(linked.status()).isEqualTo(200);
        assertThat(linked.data().path("user").path("id").asLong()).isEqualTo(userId);
        assertThat(linked.refreshToken()).isNotBlank();

        // Verify password_hash is cleared and lockout is reset
        var userRecord = dsl.selectFrom(USER_ACCOUNT).where(USER_ACCOUNT.ID.eq(userId)).fetchSingle();
        assertThat(userRecord.getPasswordHash()).isNull();
        assertThat(userRecord.getFailedLoginAttempts()).isEqualTo((short) 0);
        assertThat(userRecord.getLockedUntil()).isNull();

        // Old password no longer works (returns 401 INVALID_CREDENTIALS, never 500)
        Response passwordLogin = client.post(ApiPaths.AUTH + "/login", null,
                Map.of("email", email, "password", PASSWORD));
        assertThat(passwordLogin.status()).isEqualTo(401);
        assertThat(passwordLogin.errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS.name());

        // Old refresh token and reset token are revoked
        Response oldRefreshAttempt = client.post(ApiPaths.AUTH + "/refresh", null,
                Map.of("refreshToken", oldRefreshToken));
        assertThat(oldRefreshAttempt.status()).isEqualTo(401);
        assertThat(oldRefreshAttempt.errorCode()).isEqualTo(ErrorCode.INVALID_TOKEN.name());

        Response oldResetAttempt = client.post(ApiPaths.AUTH + "/password/reset", null,
                Map.of("token", oldResetToken, "newPassword", "NewPassword999!"));
        assertThat(oldResetAttempt.status()).isEqualTo(401);
        assertThat(oldResetAttempt.errorCode()).isEqualTo(ErrorCode.INVALID_TOKEN.name());

        // New refresh token from Google sign-in works
        Response newRefreshAttempt = client.post(ApiPaths.AUTH + "/refresh", null,
                Map.of("refreshToken", linked.refreshToken()));
        assertThat(newRefreshAttempt.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("links existing Google Workspace account when hd claim is present, clearing password and revoking tokens")
    void linksExistingWorkspaceAccountWithHdClaim() {
        String email = "workspace_" + tag + "@corp.example.com";
        long userId = createPasswordUser("ws_" + tag, email, RoleCode.BUYER);

        Response oldLogin = client.post(ApiPaths.AUTH + "/login", null,
                Map.of("email", email, "password", PASSWORD));
        assertThat(oldLogin.status()).isEqualTo(200);
        String oldRefreshToken = oldLogin.refreshToken();

        String sub = "google-sub-ws-" + tag;
        willReturn(new GoogleIdentity(sub, email, true, "Workspace User", "corp.example.com"))
                .given(googleTokenVerifier).verify("cred-ws-link");

        Response linked = client.post(ApiPaths.AUTH + "/google", null, Map.of("credential", "cred-ws-link"));

        assertThat(linked.status()).isEqualTo(200);
        assertThat(linked.data().path("user").path("id").asLong()).isEqualTo(userId);

        // Old password and old refresh token no longer work
        assertThat(client.post(ApiPaths.AUTH + "/login", null,
                Map.of("email", email, "password", PASSWORD)).status()).isEqualTo(401);
        assertThat(client.post(ApiPaths.AUTH + "/refresh", null,
                Map.of("refreshToken", oldRefreshToken)).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("refuses to link existing non-Gmail account without hd claim with 409 GOOGLE_LINK_NOT_ALLOWED and keeps password intact")
    void refusesToLinkExistingNonGmailAccountWithoutHdAndKeepsPassword() {
        String email = "custom_" + tag + "@yahoo.com";
        long userId = createPasswordUser("custom_" + tag, email, RoleCode.BUYER);

        String sub = "google-sub-custom-" + tag;
        willReturn(new GoogleIdentity(sub, email, true, "Custom User", null))
                .given(googleTokenVerifier).verify("cred-custom");

        Response rejected = client.post(ApiPaths.AUTH + "/google", null, Map.of("credential", "cred-custom"));

        assertThat(rejected.status()).isEqualTo(409);
        assertThat(rejected.errorCode()).isEqualTo(ErrorCode.GOOGLE_LINK_NOT_ALLOWED.name());

        // No identity created, and password login still works
        int identityCount = dsl.selectCount().from(USER_IDENTITY)
                .where(USER_IDENTITY.USER_ID.eq(userId))
                .fetchOne(0, int.class);
        assertThat(identityCount).isZero();

        Response passwordLogin = client.post(ApiPaths.AUTH + "/login", null,
                Map.of("email", email, "password", PASSWORD));
        assertThat(passwordLogin.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("allows a new user with a non-Gmail email and no hd claim to register (201 Created)")
    void allowsNewUserWithNonGmailEmailWithoutHdToRegister() {
        String email = "brandnew_" + tag + "@outlook.com";
        String sub = "google-sub-outlook-" + tag;
        willReturn(new GoogleIdentity(sub, email, true, "Outlook Buyer", null))
                .given(googleTokenVerifier).verify("cred-outlook");

        Response response = client.post(ApiPaths.AUTH + "/google", null, Map.of("credential", "cred-outlook"));

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.data().path("user").path("email").asString()).isEqualTo(email);
    }

    @Test
    @DisplayName("refuses ADMIN and SUPPORT accounts from signing in or linking with Google with 403 GOOGLE_SIGNIN_NOT_ALLOWED and preserves password")
    void refusesAdminAndSupportAccountsAndPreservesPassword() {
        for (RoleCode privilegedRole : List.of(RoleCode.ADMIN, RoleCode.SUPPORT)) {
            String roleTag = privilegedRole.name().toLowerCase() + "_" + tag;
            String email = roleTag + "@gmail.com";
            long userId = createPasswordUser(roleTag, email, privilegedRole);

            String sub = "google-sub-" + roleTag;
            willReturn(new GoogleIdentity(sub, email, true, "Staff User", null))
                    .given(googleTokenVerifier).verify("cred-" + roleTag);

            Response rejected = client.post(ApiPaths.AUTH + "/google", null,
                    Map.of("credential", "cred-" + roleTag));

            assertThat(rejected.status()).isEqualTo(403);
            assertThat(rejected.errorCode()).isEqualTo(ErrorCode.GOOGLE_SIGNIN_NOT_ALLOWED.name());

            // Password was not cleared and still works
            Response passwordLogin = client.post(ApiPaths.AUTH + "/login", null,
                    Map.of("email", email, "password", PASSWORD));
            assertThat(passwordLogin.status()).isEqualTo(200);

            int identityCount = dsl.selectCount().from(USER_IDENTITY)
                    .where(USER_IDENTITY.USER_ID.eq(userId))
                    .fetchOne(0, int.class);
            assertThat(identityCount).isZero();
        }
    }

    @Test
    @DisplayName("RoleService.grantAsAdmin and POST /users/{id}/roles reject granting ADMIN or SUPPORT to a Google-only account with 409 ROLE_REQUIRES_PASSWORD")
    void rejectsGrantingAdminOrSupportToGoogleOnlyAccount() {
        String sub = "google-sub-role-" + tag;
        String email = "googleonly_" + tag + "@gmail.com";
        willReturn(new GoogleIdentity(sub, email, true, "Google Only", null))
                .given(googleTokenVerifier).verify("cred-role");

        Response created = client.post(ApiPaths.AUTH + "/google", null, Map.of("credential", "cred-role"));
        assertThat(created.status()).isEqualTo(201);
        long googleUserId = created.data().path("user").path("id").asLong();

        AuthPrincipal admin = data.admin();
        String adminBearer = data.bearer(admin);

        // Via HTTP endpoint
        for (String role : List.of("ADMIN", "SUPPORT")) {
            Response grantResp = client.post(ApiPaths.USERS + "/" + googleUserId + "/roles",
                    adminBearer, Map.of("role", role));
            assertThat(grantResp.status()).isEqualTo(409);
            assertThat(grantResp.errorCode()).isEqualTo(ErrorCode.ROLE_REQUIRES_PASSWORD.name());
        }

        // Direct service call
        assertThatThrownBy(() -> roleService.grantAsAdmin(googleUserId, RoleCode.ADMIN, admin.userId()))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ROLE_REQUIRES_PASSWORD));
        assertThatThrownBy(() -> roleService.grantAsAdmin(googleUserId, RoleCode.SUPPORT, admin.userId()))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ROLE_REQUIRES_PASSWORD));
    }

    @Test
    @DisplayName("handles SUSPENDED, DEACTIVATED, soft-deleted (EMAIL_ALREADY_USED), and differently-linked (GOOGLE_ALREADY_LINKED) accounts")
    void handlesSuspendedDeactivatedSoftDeletedAndAlreadyLinkedAccounts() {
        // 1. SUSPENDED account -> 403 ACCOUNT_SUSPENDED
        String suspendedEmail = "susp_" + tag + "@gmail.com";
        long suspendedId = createPasswordUser("susp_" + tag, suspendedEmail, RoleCode.BUYER);
        dsl.update(USER_ACCOUNT).set(USER_ACCOUNT.STATUS, UserStatus.SUSPENDED)
                .where(USER_ACCOUNT.ID.eq(suspendedId)).execute();
        willReturn(new GoogleIdentity("sub-susp-" + tag, suspendedEmail, true, "Suspended", null))
                .given(googleTokenVerifier).verify("cred-susp");

        Response suspResp = client.post(ApiPaths.AUTH + "/google", null, Map.of("credential", "cred-susp"));
        assertThat(suspResp.status()).isEqualTo(403);
        assertThat(suspResp.errorCode()).isEqualTo(ErrorCode.ACCOUNT_SUSPENDED.name());

        // 2. DEACTIVATED account -> 403 ACCOUNT_DEACTIVATED
        String deactivatedEmail = "deact_" + tag + "@gmail.com";
        long deactivatedId = createPasswordUser("deact_" + tag, deactivatedEmail, RoleCode.BUYER);
        dsl.update(USER_ACCOUNT).set(USER_ACCOUNT.STATUS, UserStatus.DEACTIVATED)
                .where(USER_ACCOUNT.ID.eq(deactivatedId)).execute();
        willReturn(new GoogleIdentity("sub-deact-" + tag, deactivatedEmail, true, "Deactivated", null))
                .given(googleTokenVerifier).verify("cred-deact");

        Response deactResp = client.post(ApiPaths.AUTH + "/google", null, Map.of("credential", "cred-deact"));
        assertThat(deactResp.status()).isEqualTo(403);
        assertThat(deactResp.errorCode()).isEqualTo(ErrorCode.ACCOUNT_DEACTIVATED.name());

        // 3. Soft-deleted account -> 409 EMAIL_ALREADY_USED (does not revive the deleted account)
        String deletedEmail = "deleted_" + tag + "@gmail.com";
        long deletedId = createPasswordUser("deleted_" + tag, deletedEmail, RoleCode.BUYER);
        dsl.update(USER_ACCOUNT).set(USER_ACCOUNT.DELETED_AT, OffsetDateTime.now())
                .where(USER_ACCOUNT.ID.eq(deletedId)).execute();
        willReturn(new GoogleIdentity("sub-del-" + tag, deletedEmail, true, "Deleted", null))
                .given(googleTokenVerifier).verify("cred-del");

        Response delResp = client.post(ApiPaths.AUTH + "/google", null, Map.of("credential", "cred-del"));
        assertThat(delResp.status()).isEqualTo(409);
        assertThat(delResp.errorCode()).isEqualTo(ErrorCode.EMAIL_ALREADY_USED.name());

        // 4. Account already linked to a different Google subject -> 409 GOOGLE_ALREADY_LINKED
        String linkedEmail = "already_" + tag + "@gmail.com";
        long linkedUserId = createPasswordUser("already_" + tag, linkedEmail, RoleCode.BUYER);
        dsl.insertInto(USER_IDENTITY)
                .set(USER_IDENTITY.USER_ID, linkedUserId)
                .set(USER_IDENTITY.PROVIDER, "GOOGLE")
                .set(USER_IDENTITY.PROVIDER_SUBJECT, "original-sub-" + tag)
                .execute();
        willReturn(new GoogleIdentity("different-sub-" + tag, linkedEmail, true, "Different Sub", null))
                .given(googleTokenVerifier).verify("cred-diff-sub");

        Response diffSubResp = client.post(ApiPaths.AUTH + "/google", null, Map.of("credential", "cred-diff-sub"));
        assertThat(diffSubResp.status()).isEqualTo(409);
        assertThat(diffSubResp.errorCode()).isEqualTo(ErrorCode.GOOGLE_ALREADY_LINKED.name());
    }

    @Test
    @DisplayName("enforces status, soft-delete, and ADMIN/SUPPORT checks on already-linked Google identities")
    void enforcesStatusSoftDeleteAndRolesOnAlreadyLinkedIdentities() {
        // 1. Already-linked user becomes SUSPENDED -> 403 ACCOUNT_SUSPENDED
        String suspSub = "linked-sub-susp-" + tag;
        long suspUserId = createLinkedGoogleUser("l_susp_" + tag, "l_susp_" + tag + "@gmail.com", suspSub);
        dsl.update(USER_ACCOUNT).set(USER_ACCOUNT.STATUS, UserStatus.SUSPENDED)
                .where(USER_ACCOUNT.ID.eq(suspUserId)).execute();
        willReturn(new GoogleIdentity(suspSub, "l_susp_" + tag + "@gmail.com", true, "Linked Suspended", null))
                .given(googleTokenVerifier).verify("cred-linked-susp");

        Response suspResp = client.post(ApiPaths.AUTH + "/google", null, Map.of("credential", "cred-linked-susp"));
        assertThat(suspResp.status()).isEqualTo(403);
        assertThat(suspResp.errorCode()).isEqualTo(ErrorCode.ACCOUNT_SUSPENDED.name());

        // 2. Already-linked user becomes DEACTIVATED -> 403 ACCOUNT_DEACTIVATED
        String deactSub = "linked-sub-deact-" + tag;
        long deactUserId = createLinkedGoogleUser("l_deact_" + tag, "l_deact_" + tag + "@gmail.com", deactSub);
        dsl.update(USER_ACCOUNT).set(USER_ACCOUNT.STATUS, UserStatus.DEACTIVATED)
                .where(USER_ACCOUNT.ID.eq(deactUserId)).execute();
        willReturn(new GoogleIdentity(deactSub, "l_deact_" + tag + "@gmail.com", true, "Linked Deactivated", null))
                .given(googleTokenVerifier).verify("cred-linked-deact");

        Response deactResp = client.post(ApiPaths.AUTH + "/google", null, Map.of("credential", "cred-linked-deact"));
        assertThat(deactResp.status()).isEqualTo(403);
        assertThat(deactResp.errorCode()).isEqualTo(ErrorCode.ACCOUNT_DEACTIVATED.name());

        // 3. Already-linked user is soft-deleted -> 403 ACCOUNT_DEACTIVATED
        String delSub = "linked-sub-del-" + tag;
        long delUserId = createLinkedGoogleUser("l_del_" + tag, "l_del_" + tag + "@gmail.com", delSub);
        dsl.update(USER_ACCOUNT).set(USER_ACCOUNT.DELETED_AT, OffsetDateTime.now())
                .where(USER_ACCOUNT.ID.eq(delUserId)).execute();
        willReturn(new GoogleIdentity(delSub, "l_del_" + tag + "@gmail.com", true, "Linked Deleted", null))
                .given(googleTokenVerifier).verify("cred-linked-del");

        Response delResp = client.post(ApiPaths.AUTH + "/google", null, Map.of("credential", "cred-linked-del"));
        assertThat(delResp.status()).isEqualTo(403);
        assertThat(delResp.errorCode()).isEqualTo(ErrorCode.ACCOUNT_DEACTIVATED.name());

        // 4. Already-linked user is later granted ADMIN or SUPPORT -> 403 GOOGLE_SIGNIN_NOT_ALLOWED
        for (RoleCode privilegedRole : List.of(RoleCode.ADMIN, RoleCode.SUPPORT)) {
            String roleTag = "l_" + privilegedRole.name().toLowerCase() + "_" + tag;
            String roleSub = "linked-sub-" + roleTag;
            long roleUserId = createLinkedGoogleUser(roleTag, roleTag + "@gmail.com", roleSub);
            short roleId = roleRepository.findIdByCode(privilegedRole).orElseThrow();
            roleRepository.grant(roleUserId, roleId, null);

            willReturn(new GoogleIdentity(roleSub, roleTag + "@gmail.com", true, "Linked Staff", null))
                    .given(googleTokenVerifier).verify("cred-linked-" + roleTag);

            Response roleResp = client.post(ApiPaths.AUTH + "/google", null,
                    Map.of("credential", "cred-linked-" + roleTag));
            assertThat(roleResp.status()).isEqualTo(403);
            assertThat(roleResp.errorCode()).isEqualTo(ErrorCode.GOOGLE_SIGNIN_NOT_ALLOWED.name());
        }
    }

    @Test
    @DisplayName("rejects POST /auth/google when Origin is not in the allow-list with 403 ACCESS_DENIED")
    void rejectsDisallowedOriginWith403() {
        Response response = client.post(
                ApiPaths.AUTH + "/google",
                null,
                Map.of("credential", "any-token"),
                Map.of("Origin", "https://evil-attacker.example.com"));

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.errorCode()).isEqualTo(ErrorCode.ACCESS_DENIED.name());
    }

    private long createPasswordUser(String username, String email, RoleCode... grantedRoles) {
        long userId = dsl.insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.EMAIL, email)
                .set(USER_ACCOUNT.USERNAME, username)
                .set(USER_ACCOUNT.DISPLAY_NAME, username)
                .set(USER_ACCOUNT.PASSWORD_HASH, passwordEncoder.encode(PASSWORD))
                .set(USER_ACCOUNT.STATUS, UserStatus.ACTIVE)
                .returningResult(USER_ACCOUNT.ID)
                .fetchSingle(USER_ACCOUNT.ID);
        for (RoleCode code : grantedRoles) {
            short roleId = roleRepository.findIdByCode(code).orElseThrow();
            roleRepository.grant(userId, roleId, null);
        }
        return userId;
    }

    private long createLinkedGoogleUser(String username, String email, String providerSubject) {
        long userId = dsl.insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.EMAIL, email)
                .set(USER_ACCOUNT.USERNAME, username)
                .set(USER_ACCOUNT.DISPLAY_NAME, username)
                .set(USER_ACCOUNT.STATUS, UserStatus.ACTIVE)
                .returningResult(USER_ACCOUNT.ID)
                .fetchSingle(USER_ACCOUNT.ID);
        short buyerRoleId = roleRepository.findIdByCode(RoleCode.BUYER).orElseThrow();
        roleRepository.grant(userId, buyerRoleId, null);
        dsl.insertInto(USER_IDENTITY)
                .set(USER_IDENTITY.USER_ID, userId)
                .set(USER_IDENTITY.PROVIDER, "GOOGLE")
                .set(USER_IDENTITY.PROVIDER_SUBJECT, providerSubject)
                .execute();
        return userId;
    }
}
