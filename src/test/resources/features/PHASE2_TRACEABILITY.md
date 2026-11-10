# CR10–CR16 traceability (TCG-672)

Source: `pegasus-tcg-document/features/CR10–CR16` — 70 scenarios.
Run: `./gradlew test -x flywayMigrate -x jooqCodegen` → Cucumber report `build/reports/cucumber/cucumber.html`.

Status:
- **Cucumber**: a scenario in this folder exercises it over HTTP and passes.
- **IT**: implemented and already covered by the named integration test; not duplicated in Cucumber.
- **Partial**: the backend implements part of it; the rest is UI-only.
- **Ignored**: not implemented in the API (or UI-only / process-only); not tested and not a failure.

| CR | Scenario | Jira | Status | Where / why |
|---|---|---|---|---|
| CR10 | US-58-P1 | TCG-584 | Cucumber | CR10 feature (cross-buyer PDF + reorder → 404) |
| CR10 | US-58-N1 | TCG-584 | Cucumber | CR10 feature (5 routes → 401) |
| CR10 | US-58-E1 | TCG-584 | Cucumber | invalid/expired token → 401 |
| CR10 | US-59-P1 | TCG-585 | Cucumber | seller → admin promotions/report 403, admin 200 |
| CR10 | US-59-N1 | TCG-585 | Cucumber | other seller edits code → 404 |
| CR10 | US-59-E1 | TCG-585 | Cucumber | buyer → seller route 404 SELLER_NOT_FOUND, admin route 403 |
| CR10 | US-60-P1 | TCG-586 | IT | CheckoutPromotionIntegrationTest.twoCheckoutsRacingForTheLastUseGetOneOrder |
| CR10 | US-60-N1 | TCG-586 | IT | CheckoutPromotionIntegrationTest.theLastUseTakenMidCheckoutIsPromoChangedAndRollsBack |
| CR10 | US-60-E1 | TCG-586 | IT | CheckoutPromotionIntegrationTest.paymentTimeoutGivesTheUseBack |
| CR10 | US-61-P1 | TCG-587 | Partial | export denial in CR10/CR15 features; secret/dependency scanning is CI (TCG-580) |
| CR10 | US-61-N1 | TCG-587 | Cucumber | seller export → 403 |
| CR10 | US-61-E1 | TCG-587 | Cucumber | unsupported export format → 400 |
| CR11 | US-62-P1 | TCG-589 | Cucumber | game + text filter |
| CR11 | US-62-N1 | TCG-589 | Cucumber | empty page |
| CR11 | US-62-E1 | TCG-589 | Ignored | pagination reset is UI state |
| CR11 | US-63-P1 | TCG-590 | Cucumber | typo → correctedQuery |
| CR11 | US-63-N1 | TCG-590 | Cucumber | correction keeps game filter |
| CR11 | US-63-N2 | TCG-590 | Cucumber | no close term → empty, no correction |
| CR11 | US-63-E1 | TCG-590 | Cucumber | exact match, no correction |
| CR11 | US-64-P1 | TCG-591 | Cucumber | Thai name_local finds product (no separate alias table) |
| CR11 | US-64-N1 | TCG-591 | Cucumber | unmatched Thai term → empty |
| CR11 | US-64-E1 | TCG-591 | Cucumber | Thai match limited to selected game |
| CR12 | US-65-P1 | TCG-593 | Cucumber | admin creates code with limits |
| CR12 | US-65-N1 | TCG-593 | Cucumber | 3 invalid inputs → 400 |
| CR12 | US-65-E1 | TCG-593 | Cucumber | not-started code gives no discount |
| CR12 | US-66-P1 | TCG-594 | Cucumber | seller creates own code |
| CR12 | US-66-N1 | TCG-594 | Cucumber | other seller delete → 404 |
| CR12 | US-66-E1 | TCG-594 | Cucumber | shop code only discounts that shop |
| CR12 | US-67-P1 | TCG-595 | Cucumber | preview applies 10% |
| CR12 | US-67-N1 | TCG-595 | Cucumber | unknown code → PROMO_NOT_FOUND |
| CR12 | US-67-E1 | TCG-595 | Partial | preview is recomputed per request; cart-change refresh is UI. Extra US-67-X1 covers min-spend shortfall |
| CR12 | US-68-P1 | TCG-596 | IT | CheckoutPromotionIntegrationTest.checkoutWritesTheDiscountSplitAndHoldsEachCode |
| CR12 | US-68-N1 | TCG-596 | IT | CheckoutPromotionIntegrationTest.everyCheckoutErrorCodeIsReportedPerCode |
| CR12 | US-68-E1 | TCG-596 | IT | CheckoutPromotionIntegrationTest.aChangedTotalPlacesNothing |
| CR12 | US-69-P1 | TCG-597 | IT | CheckoutPromotionIntegrationTest.checkoutWritesTheDiscountSplitAndHoldsEachCode (allocation) |
| CR12 | US-69-N1 | TCG-597 | Ignored | no refund-amount validation against allocated discount found in API |
| CR12 | US-69-E1 | TCG-597 | Ignored | per-item refund using discount share not found in API |
| CR12 | US-70-P1 | TCG-598 | Cucumber | claimed code in My coupons |
| CR12 | US-70-N1 | TCG-598 | Cucumber | unknown code claim → 404 |
| CR12 | US-70-E1 | TCG-598 | Ignored | needs time travel past endsAt over HTTP; EXPIRED tab exists but not tested here |
| CR13 | US-71-P1 | TCG-600 | Partial | API lists completed order items; adding to cart is done by the client |
| CR13 | US-71-N1 | TCG-600 | Cucumber | other buyer → 404 |
| CR13 | US-71-E1 | TCG-600 | Ignored | reorder-items has no availability flags; UI concern |
| CR13 | US-71-E2 | TCG-600 | Ignored | stock capping happens when the client adds to cart |
| CR13 | US-72-P1 | TCG-601 | Cucumber | buyer PDF |
| CR13 | US-72-N1 | TCG-601 | Cucumber | other buyer 404, guest 401 |
| CR13 | US-72-E1 | TCG-601 | Cucumber | snapshot name survives product rename |
| CR14 | US-73-P1 | TCG-603 | Ignored | no out-of-stock alert entity/lifecycle in API (dashboard low-stock list only) |
| CR14 | US-73-N1 | TCG-603 | Ignored | as above |
| CR14 | US-73-E1 | TCG-603 | Ignored | as above |
| CR14 | US-74-P1 | TCG-604 | Ignored | as above |
| CR14 | US-74-N1 | TCG-604 | Ignored | as above |
| CR14 | US-74-E1 | TCG-604 | Ignored | as above |
| CR15 | US-75-P1 | TCG-606 | Cucumber | CR15 feature (Bangkok week, 3–9 Aug report) |
| CR15 | US-75-N1 | TCG-606 | Ignored | no discrepancy/reconciliation output in report |
| CR15 | US-75-E1 | TCG-606 | IT | AdminWeeklyStockProfitServiceTest.respectsBangkokTimezoneBoundaryBetweenSundayNightAndMondayMidnight |
| CR15 | US-76-P1 | TCG-607 | Cucumber | CR15 feature (GMV/profit/commission totals) |
| CR15 | US-76-N1 | TCG-607 | IT | AdminWeeklyStockProfitServiceTest.filtersOnlyCompletedOrdersAndCalculatesThreePercentCommissionAndNetProfit |
| CR15 | US-76-E1 | TCG-607 | Ignored | refunds are not reported as a separate period item |
| CR15 | US-77-P1 | TCG-608 | Cucumber | CR15 feature (admin view + CSV/XLSX export) |
| CR15 | US-77-N1 | TCG-608 | Cucumber | CR15 feature (buyer/seller 403) |
| CR15 | US-77-N2 | TCG-608 | Cucumber | CR15 feature (guest 401) |
| CR15 | US-77-E1 | TCG-608 | Cucumber | CR15 feature (export filename uses requested week 2026_32) |
| CR16 | US-78-P1 | TCG-610 | Cucumber | 3 completed sales counted, stock 7 |
| CR16 | US-78-E1 | TCG-610 | IT | INSUFFICIENT_DATA status covered in StockForecastServiceTest |
| CR16 | US-78-N1 | TCG-610 | Cucumber | sold out → OUT_OF_STOCK, stock 0 |
| CR16 | US-78-E2 | TCG-610 | Cucumber | no sales → NO_SALES, no date |
| CR16 | US-79-P1 | TCG-611 | Partial | estimate shown; stored history for later accuracy comparison not found |
| CR16 | US-79-N1 | TCG-611 | Cucumber | other seller LISTING_NOT_FOUND, buyer 404 |
| CR16 | US-79-E1 | TCG-611 | Ignored | earlier forecast is not retained |

Extra cases beyond the doc: US-71-X1 (reorder of an uncompleted order → 400 INVALID_ORDER_STATUS), US-67-X1 (min-spend shortfall).
