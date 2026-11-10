@CR16 @TCG-665 @Phase2 @Bonus
Feature: CR16 Smart restock prediction
  GET /sellers/me/listings/{id}/stock-forecast estimates days to zero from completed sales in the last 30 days.

  Background:
    Given a buyer "Ann"
    And a seller "Sam"
    And a seller "Tia"
    And seller "Sam" lists 10 "Mew" from "Pokemon" at "50.00"

  @US-78 @US-78-P1 @TCG-610 @Positive
  Scenario: US-78-P1 Recent completed sales give a forecast
    Given "Ann" has a completed order for 3 "Mew"
    When "Sam" calls GET "/api/v1/sellers/me/listings/{listing:Mew}/stock-forecast"
    Then the API answers 200
    And the answer's "completedSales30Days" is "3"
    And the answer's "availableStock" is "7"

  @US-78 @US-78-E2 @TCG-610 @Edge_Case
  Scenario: US-78-E2 With a zero sales rate there is no stock-out date
    When "Sam" calls GET "/api/v1/sellers/me/listings/{listing:Mew}/stock-forecast"
    Then the API answers 200
    And the answer's "status" is "NO_SALES"
    And the answer's "estimatedStockoutDate" is absent

  @US-78 @US-78-N1 @TCG-610 @Negative
  Scenario: US-78-N1 A sold-out listing shows no future stock-out date
    Given "Ann" has a completed order for 10 "Mew"
    When "Sam" calls GET "/api/v1/sellers/me/listings/{listing:Mew}/stock-forecast"
    Then the API answers 200
    And the answer's "status" is "OUT_OF_STOCK"
    And the answer's "availableStock" is "0"

  @US-79 @US-79-N1 @TCG-611 @Negative
  Scenario: US-79-N1 Another seller cannot read the forecast
    When "Tia" calls GET "/api/v1/sellers/me/listings/{listing:Mew}/stock-forecast"
    Then the API error code is "LISTING_NOT_FOUND"
    # A buyer has no seller profile, so the seller route answers 404 SELLER_NOT_FOUND.
    When "Ann" calls GET "/api/v1/sellers/me/listings/{listing:Mew}/stock-forecast"
    Then the API answers 404
    And the API error code is "SELLER_NOT_FOUND"
