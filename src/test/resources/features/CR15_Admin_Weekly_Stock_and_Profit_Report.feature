Feature: CR15 Admin weekly stock and profit report
  As an administrator
  I want weekly sales and stock figures in Bangkok time
  So that I can review completed sales, profit and available stock

  Scenario: An unauthenticated caller cannot read the weekly report
    When an unauthenticated caller requests the weekly stock and profit report
    Then the weekly report response is 401

  Scenario: An unauthenticated caller cannot export the weekly report
    When an unauthenticated caller requests the weekly stock and profit export
    Then the weekly report response is 401

  Scenario: A buyer cannot read the weekly report
    Given a buyer requests the weekly stock and profit report
    Then the weekly report response is 403
