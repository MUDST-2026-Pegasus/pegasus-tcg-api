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

  Scenario: A seller cannot read the weekly report
    Given a seller requests the weekly stock and profit report
    Then the weekly report response is 403

  Scenario: An admin views the weekly stock and profit report for 3 to 9 August 2026 with consistent breakdown and totals
    Given the standard CR15 weekly dataset for August 2026 is seeded
    When an admin requests the weekly stock and profit report for "2026-08-03" to "2026-08-09"
    Then the weekly report response is 200
    And the weekly report hasData is true
    And the weekly report summary and totals match GMV 428500, profit 12855, commission 12855, initialStock 7654, soldCount 1470, remainingStock 6184, and wowChangePercent 18.2
    And the weekly report breakdownTable rows sum to the totals and satisfy remainingStock equals initialStock minus soldCount
    And the weekly report gameDistribution has shares 56, 24, and 20 percent

  Scenario: An admin views the empty state weekly report for 10 to 16 August 2026
    Given the standard CR15 weekly dataset for August 2026 is seeded
    When an admin requests the weekly stock and profit report for "2026-08-10" to "2026-08-16"
    Then the weekly report response is 200
    And the weekly report hasData is false
    And the weekly report summary has zero figures and wowChangePercent 0.0

  Scenario: An admin receives 400 Bad Request for invalid date range or malformed ISO date
    When an admin requests the weekly stock and profit report for "2026-08-10" to "2026-08-03"
    Then the weekly report response is 400
    When an admin requests the weekly stock and profit report for "not-an-iso-date" to "2026-08-09"
    Then the weekly report response is 400

  Scenario: An admin exports the weekly stock and profit report as CSV and XLSX
    Given the standard CR15 weekly dataset for August 2026 is seeded
    When an admin exports the weekly stock and profit report for "2026-08-03" to "2026-08-09" as "csv"
    Then the exported weekly report status is 200 with content type "text/csv" and filename "pegasus_weekly_report_2026_32.csv"
    And the exported CSV content matches the main report figures
    When an admin exports the weekly stock and profit report for "2026-08-03" to "2026-08-09" as "xlsx"
    Then the exported weekly report status is 200 with content type "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" and filename "pegasus_weekly_report_2026_32.xlsx"
