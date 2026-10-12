@TCG-7 @CR1 @User_Login_and_Authentication_System
Feature: CR1 - User Login & Authentication System (TCG-7)

  # ==========================================================================
  # User Story: US-01 & US-02 (TCG-78, TCG-79) - Registration & Login Flows
  # ==========================================================================

  @CR1 @US-02 @Positive
  Scenario: US-02-P1 - Successful registration for a new buyer account
    Given A prospective user has unique email "newbie@example.com" and password "Password123!"
    When The user submits registration with username "newbie_trader" and display name "Newbie Trader"
    Then The account is created successfully with status 201
    And The system returns a valid JWT access token and refresh token

  @CR1 @US-01 @Positive
  Scenario: US-01-P1 - Successful login with valid email and password
    Given A registered user exists with email "alice@example.com" and password "AliceSecret123!"
    When The user signs in with email "alice@example.com" and password "AliceSecret123!"
    Then The login is successful with status 200
    And The response contains signed tokens and user profile for "alice@example.com"

  @CR1 @US-01 @Negative
  Scenario: US-01-N1 - Login rejected with incorrect password
    Given A registered user exists with email "bob@example.com" and password "BobSecret123!"
    When The user attempts to sign in with email "bob@example.com" and wrong password "WrongPass999!"
    Then The login is rejected with status 401 and error code "INVALID_CREDENTIALS"

  @CR1 @US-02 @Negative
  Scenario: US-02-N1 - Registration rejected when email is already in use
    Given A registered user exists with email "existing@example.com" and password "Pass12345!"
    When A new user tries to register with the same email "existing@example.com"
    Then The registration is rejected with status 409 and error code "EMAIL_ALREADY_USED"

  @CR1 @US-02 @Negative
  Scenario: US-02-N2 - Registration rejected when password is shorter than 8 characters
    Given A prospective user provides password "short" which is under 8 characters
    When The user submits registration with email "shorty@example.com"
    Then The registration is rejected with status 400 and error code "VALIDATION_FAILED"

  @CR1 @Security @Negative
  Scenario: CR1-SEC-01 - Buyer role is blocked from accessing Admin verification queue (403 Forbidden)
    Given An authenticated user with role "BUYER"
    When The buyer attempts to access the admin endpoint "/api/v1/admin/verifications"
    Then Access is denied with status 403 and error code "ACCESS_DENIED"

  @CR1 @Critical @MultiDevice
  Scenario: CR1-CRIT-01 - Multi-device logout-all invalidates refresh tokens on all devices
    Given A registered user is logged in on 2 separate devices
    When The user calls logout-all from the primary device
    Then The logout-all request succeeds with status 200
    And The second device attempting token refresh is rejected with status 401 and error code "INVALID_TOKEN"

  @CR1 @US-02 @Edge_Case
  Scenario: US-02-E1 - Registration email with mixed case is normalized to lowercase cleanly
    Given A prospective user enters email with mixed case "MixedCase.User@example.com"
    When The user registers with password "ValidPassword123!" and username "spaceman"
    Then The account is created successfully with sanitized lowercase email "mixedcase.user@example.com"
