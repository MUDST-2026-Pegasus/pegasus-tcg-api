@CR13 @TCG-599 @Phase2
Feature: CR13 Post-purchase reorder and order summary
  GET /orders/{id}/reorder-items lists a completed order's items for the client to re-add;
  GET /orders/{id}/summary.pdf renders the buyer copy from the order snapshot.

  Background:
    Given a buyer "Ann"
    And a buyer "Bob"
    And a seller "Sam"
    And seller "Sam" lists 5 "Pikachu" from "Pokemon" at "120.00"

  @US-71 @US-71-P1 @TCG-600 @Positive
  Scenario: US-71-P1 A completed order lists its items for reorder
    Given "Ann" has a completed order for 2 "Pikachu"
    When "Ann" calls GET "/api/v1/orders/{order}/reorder-items"
    Then the API answers 200
    And the answer's "items" has 1 item
    And the answer's "items.0.productName" is "{product:Pikachu}"
    And the answer's "items.0.originalQuantity" is "2"
    And the answer's "items.0.originalPrice" is the number 120.00

  @US-71 @US-71-N1 @TCG-600 @Negative
  Scenario: US-71-N1 Another buyer's order cannot be reordered
    Given "Ann" has a completed order for 1 "Pikachu"
    When "Bob" calls GET "/api/v1/orders/{order}/reorder-items"
    Then the API answers 404

  @US-71 @US-71-X1 @TCG-600 @Edge_Case @Extra
  Scenario: US-71-X1 An order that is not completed cannot be reordered yet
    Given "Ann" has placed an order for 1 "Pikachu"
    When "Ann" calls GET "/api/v1/orders/{order}/reorder-items"
    Then the API answers 400
    And the API error code is "INVALID_ORDER_STATUS"

  @US-72 @US-72-P1 @TCG-601 @Positive
  Scenario: US-72-P1 The buyer downloads their order summary as PDF
    Given "Ann" has a completed order for 1 "Pikachu"
    When "Ann" downloads "/api/v1/orders/{order}/summary.pdf"
    Then the download is a PDF that mentions "Buyer copy"

  @US-72 @US-72-N1 @TCG-601 @Negative
  Scenario: US-72-N1 Another buyer and a guest cannot download it
    Given "Ann" has a completed order for 1 "Pikachu"
    When "Bob" downloads "/api/v1/orders/{order}/summary.pdf"
    Then the download answers 404
    When "a guest" downloads "/api/v1/orders/{order}/summary.pdf"
    Then the download answers 401

  @US-72 @US-72-E1 @TCG-601 @Edge_Case
  Scenario: US-72-E1 The purchased name stays on the summary after the product is renamed
    Given "Ann" has a completed order for 1 "Pikachu"
    When the product "Pikachu" is renamed to "Renamed later"
    And "Ann" downloads "/api/v1/orders/{order}/summary.pdf"
    Then the download is a PDF that mentions "{product:Pikachu}"
