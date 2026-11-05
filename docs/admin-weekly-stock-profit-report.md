# Admin weekly stock and profit report

`GET /api/v1/admin/reports/weekly-stock-profit` returns the report inside the standard API envelope. `startDate` and `endDate` must identify a Monday and the following Sunday. Date-time values are converted to `Asia/Bangkok` before the week is checked. Optional `gameCategory` is a game slug; `productType` accepts `single_card` or `sealed_box`.

The report includes GMV from completed seller orders, frozen seller commission, net profit computed as item revenue less the frozen item cost snapshots and allocated commission, remaining active listing stock, and `stockDepleted` (completed units sold during the week). Cancelled, refunded, returned, and disputed orders are excluded. Daily amounts distinguish single cards from sealed products. Game breakdowns include stock and profit shares. `wowChangePercent` compares GMV with the previous Monday-to-Sunday period and is `null` when the prior period has zero GMV.

`GET /api/v1/admin/reports/weekly-stock-profit/export` accepts the same filters plus `format=csv|xlsx` (CSV by default). The attachment name follows `pegasus_weekly_report_YYYY_WW`.

## Data and cross-repository impact

The implementation reads this repository's existing `seller_order`, `order_item`, `catalog_product`, `game`, and `listing` tables. Cost snapshots and commission values are already stored on order records. No shared model, API contract outside this service, or database migration is needed.

Migration `V20261105_1500__listing_stock_history.sql` records listing status and available quantity whenever either changes. Reports use the latest snapshot before the end of the selected week. The migration backfills the current state once; history for weeks ending before the migration cannot be reconstructed and those weeks return zero historical stock. No shared model or API contract in another repository changes. The requested `Requirement/PHASE2_GAP_AND_DELIVERY_PLAN.md` and original CR15 feature were not present in this checkout; this report's API contract and acceptance scenarios are recorded here and in the new CR15 feature file.
