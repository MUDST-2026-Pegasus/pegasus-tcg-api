package com.pegasus.pegasustcgapi.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.AuthUser;
import com.pegasus.pegasustcgapi.model.RoleCode;
import com.pegasus.pegasustcgapi.model.UserStatus;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.JwtService;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.ApiClient.Response;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * [CRITICAL] Role-Based Authorization Guard Integration Test (TCG-389).
 * Verifies that role BUYER attempting to invoke /api/v1/sellers/** and /api/v1/admin/**
 * is rejected with 403 Forbidden in all cases, unauthenticated requests return 401,
 * and authorized roles (ADMIN/SELLER) are permitted.
 */
@DisplayName("[CRITICAL] Role-Based Authorization Guard Security Tests")
class RoleAuthorizationGuardSecurityTest extends PostgresIntegrationTest {

    @Autowired
    private JwtService jwtService;

    private ApiClient client;
    private String buyerBearer;
    private String adminBearer;
    private String unverifiedBuyerBearer;

    @BeforeEach
    void setUpGuards() {
        client = new ApiClient(port);

        // Buyer principal
        AuthPrincipal buyer = data.buyer();
        buyerBearer = data.bearer(buyer);

        // Admin principal
        String adminTag = "admin_" + data.tag();
        long adminId = dsl.insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.EMAIL, adminTag + "@example.com")
                .set(USER_ACCOUNT.USERNAME, adminTag)
                .set(USER_ACCOUNT.DISPLAY_NAME, "Admin Guard")
                .set(USER_ACCOUNT.PASSWORD_HASH, "hash")
                .returningResult(USER_ACCOUNT.ID)
                .fetchSingle(USER_ACCOUNT.ID);

        AuthUser adminUser = new AuthUser(
                adminId, adminTag + "@example.com", adminTag, "Admin Guard", "hash",
                null, null, null, UserStatus.ACTIVE, (short) 0, null, null,
                OffsetDateTime.now(), Set.of(RoleCode.BUYER, RoleCode.ADMIN));
        adminBearer = "Bearer " + jwtService.issue(adminUser).value();

        // Unverified applicant / seller (has seller profile with status NOT_APPLIED)
        AuthPrincipal unverifiedUser = data.buyer();
        dsl.insertInto(SELLER_PROFILE)
                .set(SELLER_PROFILE.USER_ID, unverifiedUser.userId())
                .set(SELLER_PROFILE.STATUS, "NOT_APPLIED")
                .execute();
        unverifiedBuyerBearer = data.bearer(unverifiedUser);
    }

    @Nested
    @DisplayName("Admin Endpoints Guard (/api/v1/admin/**)")
    class AdminEndpointsGuard {

        @Test
        @DisplayName("[CRITICAL] Negative: BUYER calling GET /api/v1/admin/verifications receives 403 Forbidden")
        void buyerCannotAccessAdminVerifications() {
            Response response = client.get("/api/v1/admin/verifications", buyerBearer);

            assertThat(response.status()).isEqualTo(403);
            assertThat(response.errorCode()).isEqualTo(ErrorCode.ACCESS_DENIED.name());
        }

        @Test
        @DisplayName("[CRITICAL] Negative: BUYER calling POST /api/v1/admin/verifications/1/approve receives 403 Forbidden")
        void buyerCannotApproveVerification() {
            Response response = client.post("/api/v1/admin/verifications/1/approve", buyerBearer, null);

            assertThat(response.status()).isEqualTo(403);
            assertThat(response.errorCode()).isEqualTo(ErrorCode.ACCESS_DENIED.name());
        }

        @Test
        @DisplayName("[CRITICAL] Negative: BUYER calling POST /api/v1/admin/verifications/1/reject receives 403 Forbidden")
        void buyerCannotRejectVerification() {
            Map<String, String> body = Map.of("reason", "Fraudulent document");
            Response response = client.post("/api/v1/admin/verifications/1/reject", buyerBearer, body);

            assertThat(response.status()).isEqualTo(403);
            assertThat(response.errorCode()).isEqualTo(ErrorCode.ACCESS_DENIED.name());
        }

        @Test
        @DisplayName("[CRITICAL] Negative: BUYER calling POST /api/v1/users/1/roles receives 403 Forbidden")
        void buyerCannotGrantRoles() {
            Map<String, String> body = Map.of("role", "ADMIN");
            Response response = client.post("/api/v1/users/1/roles", buyerBearer, body);

            assertThat(response.status()).isEqualTo(403);
            assertThat(response.errorCode()).isEqualTo(ErrorCode.ACCESS_DENIED.name());
        }

        @Test
        @DisplayName("Negative: Unauthenticated caller calling GET /api/v1/admin/verifications receives 401 Unauthorized")
        void unauthenticatedCallerCannotAccessAdminVerifications() {
            Response response = client.get("/api/v1/admin/verifications", null);

            assertThat(response.status()).isEqualTo(401);
            assertThat(response.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED.name());
        }

        @Test
        @DisplayName("Positive: ADMIN calling GET /api/v1/admin/verifications receives 200 OK")
        void adminCanAccessAdminVerifications() {
            Response response = client.get("/api/v1/admin/verifications", adminBearer);

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body().path("success").asBoolean()).isTrue();
        }
    }

    @Nested
    @DisplayName("Seller Protected Endpoints Guard (/api/v1/sellers/**)")
    class SellerEndpointsGuard {

        @Test
        @DisplayName("[CRITICAL] Negative: Seller with unverified profile calling POST /api/v1/sellers/me/listings receives 403 Forbidden")
        void unverifiedBuyerCannotCreateListing() {
            Map<String, Object> body = Map.of(
                    "catalogVariantId", 1L,
                    "price", "100.00"
            );
            Response response = client.post("/api/v1/sellers/me/listings", unverifiedBuyerBearer, body);

            assertThat(response.status()).isEqualTo(403);
            assertThat(response.errorCode()).isEqualTo(ErrorCode.SELLER_NOT_VERIFIED.name());
        }

        @Test
        @DisplayName("Negative: Buyer without seller profile calling GET /api/v1/sellers/me/listings receives 404 Not Found")
        void buyerWithoutProfileCannotReadListings() {
            Response response = client.get("/api/v1/sellers/me/listings", buyerBearer);

            assertThat(response.status()).isEqualTo(404);
            assertThat(response.errorCode()).isEqualTo(ErrorCode.SELLER_NOT_FOUND.name());
        }

        @Test
        @DisplayName("[CRITICAL] Negative: Seller with unverified profile calling POST /api/v1/sellers/me/shipping-options receives 403 Forbidden")
        void unverifiedBuyerCannotAddShippingOption() {
            Map<String, Object> body = Map.of(
                    "name", "EMS",
                    "baseFee", "50.00",
                    "perItemFee", "10.00"
            );
            Response response = client.post("/api/v1/sellers/me/shipping-options", unverifiedBuyerBearer, body);

            assertThat(response.status()).isEqualTo(403);
            assertThat(response.errorCode()).isEqualTo(ErrorCode.SELLER_NOT_VERIFIED.name());
        }

        @Test
        @DisplayName("Negative: Unauthenticated caller calling GET /api/v1/sellers/me/listings receives 401 Unauthorized")
        void unauthenticatedCallerCannotAccessSellerListings() {
            Response response = client.get("/api/v1/sellers/me/listings", null);

            assertThat(response.status()).isEqualTo(401);
            assertThat(response.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED.name());
        }

        @Test
        @DisplayName("Positive: Verified seller can access GET /api/v1/sellers/me/listings successfully (200 OK)")
        void verifiedSellerCanAccessListings() {
            TestData.Seller verifiedSeller = data.seller();
            String sellerBearer = data.bearer(verifiedSeller.principal());

            Response response = client.get("/api/v1/sellers/me/listings", sellerBearer);

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body().path("success").asBoolean()).isTrue();
        }
    }
}
