package com.pegasus.pegasustcgapi.acceptance.steps;

import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.ApiClient.Response;
import com.pegasus.pegasustcgapi.support.TestData;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.Locale;
import java.util.Map;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Cucumber step definitions for CR1_login.feature (TCG-389 Acceptance Tests).
 */
public class AuthLoginSteps {

    @LocalServerPort
    private int port;

    @Autowired
    private TestData data;

    @Autowired
    private DSLContext dsl;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private ApiClient client;
    private Response lastResponse;
    private String savedEmail;
    private String savedPassword;
    private String primaryAccessToken;
    private String secondDeviceRefreshToken;
    private AuthPrincipal buyerPrincipal;

    @Before
    public void setUp() {
        client = new ApiClient(port);
    }

    @Given("A prospective user has unique email {string} and password {string}")
    public void prospectiveUserWithUniqueEmailAndPassword(String email, String password) {
        this.savedEmail = email.replace("@example.com", "_" + data.tag() + "@example.com");
        this.savedPassword = password;
    }

    @When("The user submits registration with username {string} and display name {string}")
    public void submitRegistrationWithUsernameAndDisplayName(String username, String displayName) {
        String uniqueUsername = username + "_" + data.tag();
        Map<String, Object> body = Map.of(
                "email", savedEmail,
                "username", uniqueUsername,
                "displayName", displayName,
                "password", savedPassword
        );
        lastResponse = client.post("/api/v1/auth/register", null, body);
    }

    @Then("The account is created successfully with status {int}")
    public void accountCreatedWithStatus(int expectedStatus) {
        assertThat(lastResponse.status()).isEqualTo(expectedStatus);
        assertThat(lastResponse.body().path("success").asBoolean()).isTrue();
    }

    @Then("The system returns a valid JWT access token and refresh token")
    public void responseReturnsTokens() {
        assertThat(lastResponse.data().path("tokens").path("accessToken").asText()).isNotBlank();
        assertThat(lastResponse.data().path("tokens").path("refreshToken").asText()).isNotBlank();
    }

    @Given("A registered user exists with email {string} and password {string}")
    public void registeredUserExists(String email, String password) {
        String tag = data.tag();
        this.savedEmail = email.replace("@example.com", "_" + tag + "@example.com");
        this.savedPassword = password;
        String username = "user_" + tag;

        dsl.insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.EMAIL, savedEmail)
                .set(USER_ACCOUNT.USERNAME, username)
                .set(USER_ACCOUNT.DISPLAY_NAME, "Registered " + tag)
                .set(USER_ACCOUNT.PASSWORD_HASH, passwordEncoder.encode(password))
                .execute();
    }

    @When("The user signs in with email {string} and password {string}")
    public void userSignsIn(String email, String password) {
        Map<String, String> body = Map.of(
                "email", savedEmail,
                "password", password
        );
        lastResponse = client.post("/api/v1/auth/login", null, body);
    }

    @Then("The login is successful with status {int}")
    public void loginSuccessfulWithStatus(int expectedStatus) {
        assertThat(lastResponse.status()).isEqualTo(expectedStatus);
        assertThat(lastResponse.body().path("success").asBoolean()).isTrue();
    }

    @Then("The response contains signed tokens and user profile for {string}")
    public void responseContainsTokensAndUserProfile(String originalEmail) {
        assertThat(lastResponse.data().path("tokens").path("accessToken").asText()).isNotBlank();
        assertThat(lastResponse.data().path("user").path("email").asText()).isEqualTo(savedEmail);
    }

    @When("The user attempts to sign in with email {string} and wrong password {string}")
    public void attemptSignInWithWrongPassword(String email, String wrongPassword) {
        Map<String, String> body = Map.of(
                "email", savedEmail,
                "password", wrongPassword
        );
        lastResponse = client.post("/api/v1/auth/login", null, body);
    }

    @Then("The login is rejected with status {int} and error code {string}")
    public void loginRejectedWithStatusAndErrorCode(int expectedStatus, String expectedErrorCode) {
        assertThat(lastResponse.status()).isEqualTo(expectedStatus);
        assertThat(lastResponse.errorCode()).isEqualTo(expectedErrorCode);
    }

    @When("A new user tries to register with the same email {string}")
    public void registerWithDuplicateEmail(String email) {
        Map<String, Object> body = Map.of(
                "email", savedEmail,
                "username", "diff_user_" + data.tag(),
                "displayName", "Diff User",
                "password", "Password123!"
        );
        lastResponse = client.post("/api/v1/auth/register", null, body);
    }

    @Then("The registration is rejected with status {int} and error code {string}")
    public void registrationRejectedWithStatusAndErrorCode(int expectedStatus, String expectedErrorCode) {
        assertThat(lastResponse.status()).isEqualTo(expectedStatus);
        assertThat(lastResponse.errorCode()).isEqualTo(expectedErrorCode);
    }

    @Given("A prospective user provides password {string} which is under 8 characters")
    public void userProvidesShortPassword(String shortPassword) {
        this.savedPassword = shortPassword;
    }

    @When("The user submits registration with email {string}")
    public void submitsRegistrationWithEmail(String email) {
        String uniqueEmail = email.replace("@example.com", "_" + data.tag() + "@example.com");
        Map<String, Object> body = Map.of(
                "email", uniqueEmail,
                "username", "shorty_" + data.tag(),
                "displayName", "Shorty",
                "password", savedPassword
        );
        lastResponse = client.post("/api/v1/auth/register", null, body);
    }

    @Given("An authenticated user with role {string}")
    public void authenticatedUserWithRole(String role) {
        this.buyerPrincipal = switch (role.toUpperCase(Locale.ROOT)) {
            case "BUYER" -> data.buyer();
            case "SELLER" -> data.seller().principal();
            case "ADMIN" -> data.admin();
            default -> throw new IllegalArgumentException("Unknown role: " + role);
        };
    }

    @When("The buyer attempts to access the admin endpoint {string}")
    public void buyerAttemptsToAccessAdminEndpoint(String path) {
        String bearer = data.bearer(buyerPrincipal);
        lastResponse = client.get(path, bearer);
    }

    @Then("Access is denied with status {int} and error code {string}")
    public void accessDeniedWithStatusAndErrorCode(int expectedStatus, String expectedErrorCode) {
        assertThat(lastResponse.status()).isEqualTo(expectedStatus);
        assertThat(lastResponse.errorCode()).isEqualTo(expectedErrorCode);
    }

    @Given("A registered user is logged in on 2 separate devices")
    public void userLoggedInOnTwoDevices() {
        String tag = data.tag();
        String email = "device_user_" + tag + "@example.com";
        String password = "MultiPassword123!";
        String username = "device_user_" + tag;

        dsl.insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.EMAIL, email)
                .set(USER_ACCOUNT.USERNAME, username)
                .set(USER_ACCOUNT.DISPLAY_NAME, "Device User")
                .set(USER_ACCOUNT.PASSWORD_HASH, passwordEncoder.encode(password))
                .execute();

        Map<String, String> loginBody = Map.of("email", email, "password", password);

        // Device 1
        Response resp1 = client.post("/api/v1/auth/login", null, loginBody, Map.of("User-Agent", "Device1-Web"));
        assertThat(resp1.status()).isEqualTo(200);
        primaryAccessToken = "Bearer " + resp1.data().path("tokens").path("accessToken").asText();

        // Device 2
        Response resp2 = client.post("/api/v1/auth/login", null, loginBody, Map.of("User-Agent", "Device2-Mobile"));
        assertThat(resp2.status()).isEqualTo(200);
        secondDeviceRefreshToken = resp2.data().path("tokens").path("refreshToken").asText();
    }

    @When("The user calls logout-all from the primary device")
    public void userCallsLogoutAll() {
        lastResponse = client.post("/api/v1/auth/logout-all", primaryAccessToken, null);
    }

    @Then("The logout-all request succeeds with status {int}")
    public void logoutAllSucceeds(int expectedStatus) {
        assertThat(lastResponse.status()).isEqualTo(expectedStatus);
        assertThat(lastResponse.body().path("success").asBoolean()).isTrue();
    }

    @Then("The second device attempting token refresh is rejected with status {int} and error code {string}")
    public void secondDeviceAttemptingRefreshIsRejected(int expectedStatus, String expectedErrorCode) {
        Response refreshResp = client.post("/api/v1/auth/refresh", null, Map.of("refreshToken", secondDeviceRefreshToken));
        assertThat(refreshResp.status()).isEqualTo(expectedStatus);
        assertThat(refreshResp.errorCode()).isEqualTo(expectedErrorCode);
    }

    @Given("A prospective user enters email with mixed case {string}")
    public void userEntersEmailWithMixedCase(String rawEmail) {
        this.savedEmail = rawEmail;
    }

    @When("The user registers with password {string} and username {string}")
    public void userRegistersWithPasswordAndUsername(String password, String username) {
        String tag = data.tag();
        String mixedEmail = savedEmail.replace("@example.com", "_" + tag + "@example.com");
        String uniqueUsername = username + "_" + tag;

        Map<String, Object> body = Map.of(
                "email", mixedEmail,
                "username", uniqueUsername,
                "displayName", "Space Man",
                "password", password
        );
        lastResponse = client.post("/api/v1/auth/register", null, body);
        this.savedEmail = mixedEmail.toLowerCase(java.util.Locale.ROOT);
    }

    @Then("The account is created successfully with sanitized lowercase email {string}")
    public void accountCreatedWithSanitizedEmail(String expectedSuffix) {
        assertThat(lastResponse.status()).isEqualTo(201);
        assertThat(lastResponse.data().path("user").path("email").asText()).isEqualTo(savedEmail);
    }
}
