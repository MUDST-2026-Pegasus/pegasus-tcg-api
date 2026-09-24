@CR4 @CR7
Feature: Place an order, pay for it and receive the card
  As a buyer
  I want my card held for me while I pay, and my money held until the card arrives
  So that neither I nor the seller can be cheated

  Background:
    Given the platform commission is 3%
    And the payment timeout is 5 minutes
    And seller "Brock" has these cards on sale:
      | card         | game    | rarity | condition | stock | price  |
      | Charizard ex | Pokemon | SAR    | NM        | 1     | 175.50 |
    And buyer "Misty" is signed in with a saved address
    And "Misty" has 1 "Charizard ex" in the cart

  @CR4 @TCG-94
  Scenario: Checking out holds the card for the buyer
    When "Misty" checks out
    Then the response status is 201
    And the order is "PENDING_PAYMENT"
    And "Charizard ex" has 0 cards available and 1 reserved

  @CR4 @TCG-94
  Scenario: Checking out needs an Idempotency-Key
    When "Misty" checks out without an Idempotency-Key
    Then the response status is 400
    And the error code is "IDEMPOTENCY_KEY_REQUIRED"
    And "Charizard ex" has 1 cards available and 0 reserved

  @CR7 @TCG-122
  Scenario: An order not paid within 5 minutes is cancelled and the card goes back on sale
    Given "Misty" has checked out
    When 5 minutes and 1 second pass and the payment timeout sweep runs
    Then the order is "CANCELLED"
    And the cancellation was recorded by the system
    And "Charizard ex" has 1 cards available and 0 reserved

  @CR7 @TCG-122
  Scenario: An order paid in time is not cancelled by the sweep
    Given "Misty" has checked out
    And "Misty" has paid for the order
    When 5 minutes and 1 second pass and the payment timeout sweep runs
    Then the order is "PAID"

  @CR7 @TCG-123 @TCG-124
  Scenario: The seller is paid, minus commission, only once the buyer confirms
    Given "Misty" has checked out
    And "Misty" has paid for the order
    When "Brock" ships the order with tracking "TH1234567890"
    Then the response status is 200
    And escrow has not been released
    When "Misty" confirms the order was received
    Then the response status is 200
    And the order is "COMPLETED"
    And the commission is 5.27 and "Brock" nets 170.23
    And escrow was released to "Brock" once

  @CR7 @TCG-117
  Scenario: A cancelled order cannot be paid
    Given "Misty" has checked out
    And "Misty" has cancelled the order
    When "Misty" pays for the order
    Then the response status is 409
    And the error code is "ORDER_STATUS_TRANSITION"

  @CR7 @TCG-117
  Scenario: An unpaid order cannot be shipped
    Given "Misty" has checked out
    When "Brock" ships the order with tracking "TH1234567890"
    Then the response status is 409
    And the error code is "ORDER_STATUS_TRANSITION"
