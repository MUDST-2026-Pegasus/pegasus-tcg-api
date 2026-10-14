package com.pegasus.pegasustcgapi.controller;

import static com.pegasus.pegasustcgapi.jooq.tables.AuthToken.AUTH_TOKEN;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.RoleCode;
import com.pegasus.pegasustcgapi.model.UserStatus;
import com.pegasus.pegasustcgapi.repository.RoleRepository;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.ApiClient.Response;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

@DisplayName("Admin user API (TCG-423)")
class AdminUserApiIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "Password123!";

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RoleRepository roles;

    private ApiClient client;
    private AuthPrincipal admin;
    private String adminBearer;
    private String tag;

    @BeforeEach
    void setUp() {
        client = new ApiClient(port);
        admin = data.admin();
        adminBearer = data.bearer(admin);
        tag = data.tag();
    }

    // ── authorization ────────────────────────────────────────────

    @Test
    @DisplayName("a non-admin gets 403 on every endpoint")
    void nonAdminIsForbidden() {
        String buyer = data.bearer(data.buyer());
        long target = account("target", "Target", UserStatus.ACTIVE);

        assertForbidden(client.get("/api/v1/admin/users", buyer));
        assertForbidden(client.get("/api/v1/admin/users/" + target, buyer));
        assertForbidden(client.patch("/api/v1/admin/users/" + target + "/status", buyer,
                Map.of("status", "SUSPENDED")));
        assertThat(statusOf(target)).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("an anonymous caller gets 401")
    void anonymousIsUnauthenticated() {
        assertThat(client.get("/api/v1/admin/users", null).status()).isEqualTo(401);
    }

    // ── list / search / filter ───────────────────────────────────

    @Test
    @DisplayName("admin lists users with roles and no password field")
    void listsUsers() {
        long id = account("lister", "Lister", UserStatus.ACTIVE);
        roles.grant(id, roleId(RoleCode.BUYER), null);

        Response response = client.get("/api/v1/admin/users?q=" + tag, adminBearer);

        assertThat(response.status()).isEqualTo(200);
        JsonNode item = response.data().path("items").get(0);
        assertThat(item.path("id").asLong()).isEqualTo(id);
        assertThat(item.path("roles").get(0).asText()).isEqualTo("BUYER");
        assertThat(item.has("passwordHash")).isFalse();
        assertThat(item.has("password")).isFalse();
    }

    @Test
    @DisplayName("searches by email, username and display name, case-insensitively")
    void searchesThreeFields() {
        long byEmail = account("alpha", "Plain", UserStatus.ACTIVE);
        long byName = accountWith("zeta_" + tag, "zeta_" + tag + "@example.com", "Quokka" + tag);

        assertThat(ids(search("q=ALPHA_" + tag))).containsExactly(byEmail);
        assertThat(ids(search("q=" + "alpha_" + tag + "@EXAMPLE.com"))).containsExactly(byEmail);
        assertThat(ids(search("q=quokka" + tag))).containsExactly(byName);
        assertThat(ids(search("q=zeta_" + tag))).containsExactly(byName);
    }

    @Test
    @DisplayName("treats % and _ in the search text literally")
    void wildcardsAreLiteral() {
        long id = account("plainz", "Plain", UserStatus.ACTIVE);

        // Unescaped, "%" and the first "_" would both match "plainz_<tag>".
        assertThat(ids(search("q=%25" + tag))).isEmpty();
        assertThat(ids(search("q=plain__" + tag))).isEmpty();
        assertThat(ids(search("q=plainz_" + tag))).containsExactly(id);
    }

    @Test
    @DisplayName("filters by role and by status")
    void filtersByRoleAndStatus() {
        long seller = account("sell", "S", UserStatus.ACTIVE);
        long suspendedBuyer = account("buy", "B", UserStatus.SUSPENDED);
        roles.grant(seller, roleId(RoleCode.SELLER), null);
        roles.grant(suspendedBuyer, roleId(RoleCode.BUYER), null);

        assertThat(ids(search("q=" + tag + "&role=SELLER"))).containsExactly(seller);
        assertThat(ids(search("q=" + tag + "&status=SUSPENDED"))).containsExactly(suspendedBuyer);
        assertThat(ids(search("q=" + tag + "&role=SELLER&status=SUSPENDED"))).isEmpty();
        assertThat(ids(search("q=" + tag))).containsExactlyInAnyOrder(seller, suspendedBuyer);
    }

    @Test
    @DisplayName("paginates in the database, newest first")
    void paginates() {
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(account("page" + i, "P" + i, UserStatus.ACTIVE));
        }

        JsonNode first = search("q=" + tag + "&page=0&size=2");
        JsonNode last = search("q=" + tag + "&page=2&size=2");

        assertThat(first.path("totalItems").asLong()).isEqualTo(5);
        assertThat(first.path("totalPages").asInt()).isEqualTo(3);
        assertThat(ids(first)).containsExactly(created.get(4), created.get(3));
        assertThat(ids(last)).containsExactly(created.get(0));
    }

    @Test
    @DisplayName("get returns one user, 404 for an unknown id")
    void getOne() {
        long id = account("one", "One", UserStatus.SUSPENDED);

        Response found = client.get("/api/v1/admin/users/" + id, adminBearer);
        Response missing = client.get("/api/v1/admin/users/999999999", adminBearer);

        assertThat(found.status()).isEqualTo(200);
        assertThat(found.data().path("status").asText()).isEqualTo("SUSPENDED");
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.errorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND.name());
    }

    // ── suspend ──────────────────────────────────────────────────

    @Test
    @DisplayName("suspending kicks the user out: refresh tokens revoked, refresh and login refused")
    void suspendKicksUserOut() {
        String email = "victim_" + tag + "@example.com";
        long id = accountWith("victim_" + tag, email, "Victim");
        String refresh1 = login(email);
        String refresh2 = login(email);
        assertThat(activeRefreshTokens(id)).isEqualTo(2);

        Response suspend = client.patch("/api/v1/admin/users/" + id + "/status", adminBearer,
                Map.of("status", "SUSPENDED"));

        assertThat(suspend.status()).isEqualTo(200);
        assertThat(suspend.data().path("status").asText()).isEqualTo("SUSPENDED");
        assertThat(activeRefreshTokens(id)).isZero();
        for (String refresh : List.of(refresh1, refresh2)) {
            assertThat(client.post("/api/v1/auth/refresh", null, Map.of("refreshToken", refresh)).status())
                    .isEqualTo(401);
        }
        Response login = client.post("/api/v1/auth/login", null, Map.of("email", email, "password", PASSWORD));
        assertThat(login.status()).isEqualTo(403);
        assertThat(login.errorCode()).isEqualTo(ErrorCode.ACCOUNT_SUSPENDED.name());
    }

    @Test
    @DisplayName("restoring lets the user sign in again")
    void restoreAllowsLogin() {
        String email = "back_" + tag + "@example.com";
        long id = accountWith("back_" + tag, email, "Back");
        client.patch("/api/v1/admin/users/" + id + "/status", adminBearer, Map.of("status", "SUSPENDED"));

        Response restore = client.patch("/api/v1/admin/users/" + id + "/status", adminBearer,
                Map.of("status", "ACTIVE"));

        assertThat(restore.status()).isEqualTo(200);
        assertThat(client.post("/api/v1/auth/login", null,
                Map.of("email", email, "password", PASSWORD)).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("an admin cannot suspend themselves")
    void adminCannotSuspendSelf() {
        Response response = client.patch("/api/v1/admin/users/" + admin.userId() + "/status", adminBearer,
                Map.of("status", "SUSPENDED"));

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.errorCode()).isEqualTo(ErrorCode.ACCESS_DENIED.name());
        assertThat(statusOf(admin.userId())).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("rejects a missing or unknown status with 400")
    void rejectsBadStatus() {
        long id = account("bad", "Bad", UserStatus.ACTIVE);

        assertThat(client.patch("/api/v1/admin/users/" + id + "/status", adminBearer, Map.of()).status())
                .isEqualTo(400);
        assertThat(client.patch("/api/v1/admin/users/" + id + "/status", adminBearer,
                Map.of("status", "BANNED")).status()).isEqualTo(400);
    }

    // ── helpers ──────────────────────────────────────────────────

    private void assertForbidden(Response response) {
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.errorCode()).isEqualTo(ErrorCode.ACCESS_DENIED.name());
    }

    private JsonNode search(String query) {
        Response response = client.get("/api/v1/admin/users?" + query, adminBearer);
        assertThat(response.status()).isEqualTo(200);
        return response.data();
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }

    /** Username and email carry the test tag, so a search on it sees only this test's rows. */
    private long account(String prefix, String displayName, UserStatus status) {
        String name = prefix + "_" + tag;
        long id = accountWith(name, name + "@example.com", displayName);
        dsl.update(USER_ACCOUNT).set(USER_ACCOUNT.STATUS, status).where(USER_ACCOUNT.ID.eq(id)).execute();
        return id;
    }

    private long accountWith(String username, String email, String displayName) {
        return dsl.insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.EMAIL, email)
                .set(USER_ACCOUNT.USERNAME, username)
                .set(USER_ACCOUNT.DISPLAY_NAME, displayName)
                .set(USER_ACCOUNT.PASSWORD_HASH, passwordEncoder.encode(PASSWORD))
                .returningResult(USER_ACCOUNT.ID)
                .fetchSingle(USER_ACCOUNT.ID);
    }

    private String login(String email) {
        Response response = client.post("/api/v1/auth/login", null, Map.of("email", email, "password", PASSWORD));
        assertThat(response.status()).isEqualTo(200);
        return response.data().path("tokens").path("refreshToken").asText();
    }

    private int activeRefreshTokens(long userId) {
        return dsl.selectCount().from(AUTH_TOKEN)
                .where(AUTH_TOKEN.USER_ID.eq(userId))
                .and(AUTH_TOKEN.REVOKED_AT.isNull())
                .fetchOne(0, int.class);
    }

    private UserStatus statusOf(long userId) {
        return dsl.select(USER_ACCOUNT.STATUS).from(USER_ACCOUNT)
                .where(USER_ACCOUNT.ID.eq(userId)).fetchSingle(USER_ACCOUNT.STATUS);
    }

    private short roleId(RoleCode code) {
        return roles.findIdByCode(code).orElseThrow();
    }
}
