@CR2 @CR3
Feature: Browse the catalogue and fill the cart
  As a buyer
  I want to find cards by name, game and condition and put them in my cart
  So that I can buy them, without ever asking for more than the seller has

  Background:
    Given seller "Ash" has these cards on sale:
      | card                   | game     | rarity | condition | stock | price   |
      | Charizard ex           | Pokemon  | SAR    | NM        | 2     | 1500.00 |
      | Pikachu                | Pokemon  | C      | LP        | 5     | 20.00   |
      | Blue-Eyes White Dragon | Yu-Gi-Oh | UR     | NM        | 1     | 900.00  |

  @CR2 @TCG-87
  Scenario: Search the catalogue by part of a card name
    When a guest searches the "Pokemon" catalogue for "chariz"
    Then the response status is 200
    And the catalogue results are exactly "Charizard ex"

  @CR2 @TCG-85
  Scenario: Browse the market for one game
    When a guest browses the market for "Yu-Gi-Oh"
    Then the response status is 200
    And the market shows exactly "Blue-Eyes White Dragon"

  @CR2 @TCG-90
  Scenario Outline: Filter the market by card condition
    When a guest browses the market for "Pokemon" in condition "<condition>"
    Then the market shows exactly "<shown>"

    Examples:
      | condition | shown        |
      | NM        | Charizard ex |
      | LP        | Pikachu      |

  @CR2 @TCG-90
  Scenario: A condition nobody is selling shows an empty market
    When a guest browses the market for "Pokemon" in condition "MP"
    Then the response status is 200
    And the market is empty

  @CR3 @TCG-91
  Scenario: A guest's first item starts a guest cart
    When a guest adds 1 "Pikachu" to the cart
    Then the response status is 201
    And the response carries a cart session key

  @CR3 @TCG-91 @ECC
  Scenario Outline: A quantity within the stock goes into the cart
    Given buyer "Misty" is signed in
    When "Misty" adds <quantity> "Pikachu" to the cart
    Then the response status is 201
    And "Misty"'s cart holds <quantity> "Pikachu" for <subtotal>

    Examples:
      | quantity | subtotal |
      | 1        | 20.00    |
      | 5        | 100.00   |

  @CR3 @TCG-91 @ECC
  Scenario Outline: A quantity outside the stock is refused
    Given buyer "Misty" is signed in
    When "Misty" adds <quantity> "Pikachu" to the cart
    Then the response status is <status>
    And the error code is "<code>"
    And "Misty"'s cart is empty

    Examples:
      | quantity | status | code              |
      | 0        | 400    | VALIDATION_FAILED |
      | -1       | 400    | VALIDATION_FAILED |
      | 6        | 409    | INSUFFICIENT_STOCK |
