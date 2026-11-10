@CR12 @TCG-592 @Phase2
Feature: CR12 Promotions and discount codes
  Admins manage Pegasus codes, sellers manage their own shop codes, and buyers preview
  a code against their basket with POST /api/v1/checkout/promo-preview (nothing is reserved).
  Checkout-time changes (US-68) and refund allocation (US-69) are covered by
  CheckoutPromotionIntegrationTest and are not repeated here.

  Background:
    Given a buyer "Ann"
    And a seller "Sam"
    And a seller "Tia"
    And an admin "Ada"
    And seller "Sam" lists 3 "Pikachu" from "Pokemon" at "400.00"
    And seller "Tia" lists 3 "Luffy" from "One Piece" at "600.00"

  @US-65 @US-65-P1 @TCG-593 @Positive
  Scenario: US-65-P1 An admin creates a Pegasus code with explicit limits
    When "Ada" calls POST "/api/v1/admin/promotions" with:
      """
      {"code": "{code:PEG}", "name": "Pegasus ten", "discountType": "PERCENT", "discountValue": 10,
       "maxDiscount": 200, "minSpend": 0, "usageLimitTotal": 50, "usageLimitPerUser": 1,
       "visibility": "PUBLIC", "startsAt": "{now+0h}", "endsAt": "{now+7d}"}
      """
    Then the API answers 201
    And the answer's "code" is "{code:PEG}"
    And the answer's "usageLimitTotal" is "50"

  @US-65 @US-65-N1 @TCG-593 @Negative
  Scenario Outline: US-65-N1 Validation rejects an invalid code: <case>
    When "Ada" calls POST "/api/v1/admin/promotions" with:
      """
      {"code": "<code>", "name": "Bad", "discountType": "FIXED", "discountValue": 10,
       "usageLimitTotal": <limit>, "startsAt": "{now+0h}", "endsAt": "{now+7d}"}
      """
    Then the API answers 400

    Examples:
      | case                 | code      | limit |
      | code too short       | AB        | 10    |
      | symbols in the code  | BAD-CODE! | 10    |
      | usage limit below 1  | {code:LIM} | 0    |

  @US-65 @US-65-E1 @TCG-593 @Edge_Case
  Scenario: US-65-E1 A code that has not started yet does not discount the basket
    Given "Ann" has 1 "Pikachu" in the basket
    When "Ada" calls POST "/api/v1/admin/promotions" with:
      """
      {"code": "{code:SOON}", "name": "Later", "discountType": "FIXED", "discountValue": 50,
       "visibility": "PUBLIC", "startsAt": "{now+2d}", "endsAt": "{now+7d}"}
      """
    Then the API answers 201
    When "Ann" calls POST "/api/v1/checkout/promo-preview" with:
      """
      {"platformCode": "{code:SOON}"}
      """
    Then the API answers 200
    And the answer's "platformDiscountTotal" is the number 0
    And the answer's "grandTotal" is the number 400.00

  @US-66 @US-66-P1 @TCG-594 @Positive
  Scenario: US-66-P1 A seller creates a code for their own shop
    When "Sam" calls POST "/api/v1/sellers/me/promotions" with:
      """
      {"code": "{code:SAM}", "name": "Sam fifty", "discountType": "FIXED", "discountValue": 50,
       "visibility": "PUBLIC", "startsAt": "{now+0h}", "endsAt": "{now+7d}"}
      """
    Then the API answers 201
    And the answer's "code" is "{code:SAM}"

  @US-66 @US-66-N1 @TCG-594 @Negative
  Scenario: US-66-N1 Another seller cannot delete that code
    When "Sam" calls POST "/api/v1/sellers/me/promotions" with:
      """
      {"code": "{code:SAM}", "name": "Sam fifty", "discountType": "FIXED", "discountValue": 50,
       "startsAt": "{now+0h}", "endsAt": "{now+7d}"}
      """
    And the answer's "id" is saved as "sam code"
    And "Tia" calls DELETE "/api/v1/sellers/me/promotions/{saved:sam code}"
    Then the API answers 404

  @US-66 @US-66-E1 @TCG-594 @Edge_Case
  Scenario: US-66-E1 A shop code only discounts that shop's items
    Given "Ann" has 1 "Pikachu" in the basket
    And "Ann" has 1 "Luffy" in the basket
    When "Sam" calls POST "/api/v1/sellers/me/promotions" with:
      """
      {"code": "{code:SAM}", "name": "Sam fifty", "discountType": "FIXED", "discountValue": 50,
       "visibility": "PUBLIC", "startsAt": "{now-1h}", "endsAt": "{now+7d}"}
      """
    Then the API answers 201
    When "Ann" calls POST "/api/v1/checkout/promo-preview" with:
      """
      {"sellerCodes": {"{shop:Sam}": "{code:SAM}"}}
      """
    Then the API answers 200
    And the answer's "sellerDiscountTotal" is the number 50.00
    And the answer's "grandTotal" is the number 950.00

  @US-67 @US-67-P1 @TCG-595 @Positive
  Scenario: US-67-P1 The buyer previews a Pegasus code before checkout
    Given "Ann" has 1 "Pikachu" in the basket
    When "Ada" calls POST "/api/v1/admin/promotions" with:
      """
      {"code": "{code:PEG}", "name": "Pegasus ten", "discountType": "PERCENT", "discountValue": 10,
       "maxDiscount": 200, "visibility": "PUBLIC", "startsAt": "{now-1h}", "endsAt": "{now+7d}"}
      """
    And "Ann" calls POST "/api/v1/checkout/promo-preview" with:
      """
      {"platformCode": "{code:PEG}"}
      """
    Then the API answers 200
    And the answer's "codes.0.status" is "APPLIED"
    And the answer's "platformDiscountTotal" is the number 40.00
    And the answer's "grandTotal" is the number 360.00

  @US-67 @US-67-N1 @TCG-595 @Negative
  Scenario: US-67-N1 An unknown code is not applied
    Given "Ann" has 1 "Pikachu" in the basket
    When "Ann" calls POST "/api/v1/checkout/promo-preview" with:
      """
      {"platformCode": "{code:NOPE}"}
      """
    Then the API answers 200
    And the answer's "codes.0.status" is "PROMO_NOT_FOUND"
    And the answer's "grandTotal" is the number 400.00

  @US-67 @US-67-X1 @TCG-595 @Edge_Case @Extra
  Scenario: US-67-X1 A minimum spend the basket does not reach leaves the total unchanged
    Given "Ann" has 1 "Pikachu" in the basket
    When "Ada" calls POST "/api/v1/admin/promotions" with:
      """
      {"code": "{code:MIN}", "name": "Big spender", "discountType": "FIXED", "discountValue": 100,
       "minSpend": 1000, "visibility": "PUBLIC", "startsAt": "{now-1h}", "endsAt": "{now+7d}"}
      """
    And "Ann" calls POST "/api/v1/checkout/promo-preview" with:
      """
      {"platformCode": "{code:MIN}"}
      """
    Then the API answers 200
    And the answer's "codes.0.shortfall" is the number 600.00
    And the answer's "grandTotal" is the number 400.00

  @US-70 @US-70-P1 @TCG-598 @Positive
  Scenario: US-70-P1 A collected code shows in the buyer's coupons
    When "Ada" calls POST "/api/v1/admin/promotions" with:
      """
      {"code": "{code:KEEP}", "name": "Keep me", "discountType": "FIXED", "discountValue": 20,
       "visibility": "PUBLIC", "claimable": true, "startsAt": "{now-1h}", "endsAt": "{now+7d}"}
      """
    And "Ann" calls POST "/api/v1/me/promotions/claim-by-code" with:
      """
      {"code": "{code:KEEP}"}
      """
    Then the API answers 200
    When "Ann" calls GET "/api/v1/me/promotions"
    Then the API answers 200
    And the answer's "items.0.code" is "{code:KEEP}"

  @US-70 @US-70-N1 @TCG-598 @Negative
  Scenario: US-70-N1 An unknown code cannot be collected
    When "Ann" calls POST "/api/v1/me/promotions/claim-by-code" with:
      """
      {"code": "{code:GHOST}"}
      """
    Then the API answers 404
