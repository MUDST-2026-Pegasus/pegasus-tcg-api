@CR11 @TCG-588 @Phase2
Feature: CR11 Product discovery and tolerant search
  The public catalogue search (GET /api/v1/catalog/products) matches names with ILIKE and,
  only when nothing matches, retries with the closest catalogue term and reports correctedQuery.

  Background:
    Given a seller "Sam"
    And seller "Sam" lists 3 "Charizard" from "Pokemon" at "900.00"
    And seller "Sam" lists 2 "Zoro" from "One Piece" at "300.00"

  @US-62 @US-62-P1 @TCG-589 @Positive
  Scenario: US-62-P1 Game filter and search text narrow the same result
    When "a guest" calls GET "/api/v1/catalog/products?gameId={game:Pokemon}&q={product:Charizard}"
    Then the API answers 200
    And the answer's "totalItems" is "1"
    And the answer's "items.0.name" is "{product:Charizard}"

  @US-62 @US-62-N1 @TCG-589 @Negative
  Scenario: US-62-N1 A filter combination with no match is an empty page
    When "a guest" calls GET "/api/v1/catalog/products?gameId={game:One Piece}&q={product:Charizard}"
    Then the API answers 200
    And the answer's "totalItems" is "0"
    And the answer's "items" has 0 items

  @US-63 @US-63-P1 @TCG-590 @Positive
  Scenario: US-63-P1 A minor misspelling still finds the product
    When "a guest" calls GET "/api/v1/catalog/products?q=Charizrd"
    Then the API answers 200
    And the answer's "correctedQuery" contains "Charizard"
    And the answer's "items" include the name "{product:Charizard}"

  @US-63 @US-63-N1 @TCG-590 @Negative
  Scenario: US-63-N1 A corrected search still respects the game filter
    When "a guest" calls GET "/api/v1/catalog/products?gameId={game:One Piece}&q=Charizrd"
    Then the API answers 200
    And the answer's "items" has 0 items

  @US-63 @US-63-N2 @TCG-590 @Negative
  Scenario: US-63-N2 Text with no close term returns nothing and no correction
    When "a guest" calls GET "/api/v1/catalog/products?q=Qwxzv9k"
    Then the API answers 200
    And the answer's "items" has 0 items
    And the answer's "correctedQuery" is absent

  @US-63 @US-63-E1 @TCG-590 @Edge_Case
  Scenario: US-63-E1 An exact match is answered without any correction
    When "a guest" calls GET "/api/v1/catalog/products?q={product:Zoro}"
    Then the API answers 200
    And the answer's "items.0.name" is "{product:Zoro}"
    And the answer's "correctedQuery" is absent

  @US-64 @US-64-P1 @TCG-591 @Positive
  Scenario: US-64-P1 The Thai name finds the English product
    Given the product "Charizard" also has the Thai name "ลิซาร์ดอนทดสอบ"
    When "a guest" calls GET "/api/v1/catalog/products?q=ลิซาร์ดอนทดสอบ"
    Then the API answers 200
    And the answer's "items" include the name "{product:Charizard}"

  @US-64 @US-64-E1 @TCG-591 @Edge_Case
  Scenario: US-64-E1 The Thai name only matches inside the selected game
    Given the product "Charizard" also has the Thai name "ลิซาร์ดอนทดสอบ"
    When "a guest" calls GET "/api/v1/catalog/products?gameId={game:One Piece}&q=ลิซาร์ดอนทดสอบ"
    Then the API answers 200
    And the answer's "items" has 0 items

  @US-64 @US-64-N1 @TCG-591 @Negative
  Scenario: US-64-N1 A Thai term with no matching product shows no false match
    When "a guest" calls GET "/api/v1/catalog/products?q=คำที่ไม่มีสินค้าใดตรง"
    Then the API answers 200
    And the answer's "items" has 0 items
    And the answer's "correctedQuery" is absent
