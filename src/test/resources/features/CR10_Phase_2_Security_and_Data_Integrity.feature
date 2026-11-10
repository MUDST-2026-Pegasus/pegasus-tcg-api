@CR10 @TCG-583 @Phase2
Feature: CR10 Phase 2 security and data integrity
  Each check runs over HTTP with a real signed token against the current API.
  US-60 (concurrent redemption) is covered by CheckoutPromotionIntegrationTest and is not repeated here.

  Background:
    Given a buyer "Ann"
    And a buyer "Bob"
    And a seller "Sam"
    And a seller "Tia"
    And an admin "Ada"
    And seller "Sam" lists 5 "Pikachu" from "Pokemon" at "100.00"

  @US-58 @US-58-P1 @TCG-584 @Positive
  Scenario: US-58-P1 A buyer cannot read or reorder another buyer's order
    Given "Ann" has a completed order for 1 "Pikachu"
    When "Bob" downloads "/api/v1/orders/{order}/summary.pdf"
    Then the download answers 404
    When "Bob" calls GET "/api/v1/orders/{order}/reorder-items"
    Then the API answers 404
    And the API error code is "ORDER_NOT_FOUND"
    When "Ann" calls GET "/api/v1/orders/{order}/reorder-items"
    Then the API answers 200

  @US-58 @US-58-N1 @TCG-584 @Negative
  Scenario Outline: US-58-N1 Authentication is required for <path>
    Given "Ann" has a completed order for 1 "Pikachu"
    When "a guest" calls GET "<path>"
    Then the API answers 401

    Examples:
      | path                                    |
      | /api/v1/orders/{order}/reorder-items    |
      | /api/v1/me/promotions                   |
      | /api/v1/sellers/me/promotions           |
      | /api/v1/sellers/me/listings/stock-forecast |
      | /api/v1/admin/promotions                |

  @US-58 @US-58-E1 @TCG-584 @Edge_Case
  Scenario: US-58-E1 A token that is no longer valid must sign in again
    Given "Ann" has a completed order for 1 "Pikachu"
    When "an expired session" calls GET "/api/v1/orders/{order}/reorder-items"
    Then the API answers 401

  @US-59 @US-59-P1 @TCG-585 @Positive
  Scenario: US-59-P1 Seller and admin boundaries apply to promotions and reports
    When "Sam" calls GET "/api/v1/admin/promotions"
    Then the API answers 403
    When "Sam" calls GET "/api/v1/admin/reports/weekly-stock-profit?startDate=2026-08-03&endDate=2026-08-09"
    Then the API answers 403
    When "Ada" calls GET "/api/v1/admin/promotions"
    Then the API answers 200
    When "Sam" calls GET "/api/v1/sellers/me/promotions"
    Then the API answers 200

  @US-59 @US-59-N1 @TCG-585 @Negative
  Scenario: US-59-N1 A seller cannot edit another seller's discount code
    When "Sam" calls POST "/api/v1/sellers/me/promotions" with:
      """
      {"code": "{code:SAM}", "name": "Sam ten off", "discountType": "FIXED", "discountValue": 10,
       "startsAt": "{now+0h}", "endsAt": "{now+7d}"}
      """
    Then the API answers 201
    When the answer's "id" is saved as "sam code"
    And "Tia" calls PUT "/api/v1/sellers/me/promotions/{saved:sam code}" with:
      """
      {"code": "{code:SAM}", "name": "Hijacked", "discountType": "FIXED", "discountValue": 99,
       "startsAt": "{now+0h}", "endsAt": "{now+7d}"}
      """
    Then the API answers 404

  @US-59 @US-59-E1 @TCG-585 @Edge_Case
  Scenario: US-59-E1 The current role is checked: a buyer cannot use seller or admin promotion routes
    # A buyer has no seller profile, so the seller route answers 404 SELLER_NOT_FOUND rather than 403.
    When "Ann" calls GET "/api/v1/sellers/me/promotions"
    Then the API answers 404
    And the API error code is "SELLER_NOT_FOUND"
    When "Ann" calls POST "/api/v1/admin/promotions" with:
      """
      {"code": "{code:ANN}", "name": "Not allowed", "discountType": "FIXED", "discountValue": 10,
       "startsAt": "{now+0h}", "endsAt": "{now+7d}"}
      """
    Then the API answers 403

  @US-61 @US-61-N1 @TCG-587 @Negative
  Scenario: US-61-N1 A seller cannot export the weekly report
    When "Sam" calls GET "/api/v1/admin/reports/weekly-stock-profit/export?startDate=2026-08-03&endDate=2026-08-09"
    Then the API answers 403

  @US-61 @US-61-E1 @TCG-587 @Edge_Case
  Scenario: US-61-E1 An export in an unsupported format is rejected
    When "Ada" calls GET "/api/v1/admin/reports/weekly-stock-profit/export?startDate=2026-08-03&endDate=2026-08-09&format=exe"
    Then the API answers 400
