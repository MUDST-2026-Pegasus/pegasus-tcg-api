# TCG-755 API Contract & Cross-module Integration — Report

- API commit: c874f58 (branch feat/TCG-755-contract-integration) · Web snapshot: pegasus-tcg-web 2bafd7b
- Env: JDK 25, Testcontainers postgres:17-alpine + pgsty/minio:RELEASE.2026-08-04T00-00-00Z, synthetic accounts per test (TestData tag), OpenAPI 3.1.0
- Run: `./gradlew crossModuleTest` (only @Tag cross-module; order-independent). Default `./gradlew test` excludes @Tag known-defect.
- Refresh FE snapshot: `python3 -I scripts/contract/extract_fe_endpoints.py ../pegasus-tcg-web > src/test/resources/contract/fe-endpoints.tsv` (+ `--error-codes` → fe-error-codes.txt)
- Evidence: build/tcg755/openapi.json, build/tcg755/undocumented-nulls.txt, build/reports/tests/crossModuleTest

## API/consumer mapping
src/test/resources/contract/fe-endpoints.tsv — 257 call sites / 246 unique METHOD+path, each with consumer file. 251/257 document a JSON 2xx schema.

## Results
| Case | Result |
|---|---|
| Every FE endpoint published with same method (excluding listed gaps) | Pass |
| FE API_ERROR_CODES all exist in ErrorCode (52) | Pass |
| Envelope / PageResponse / error body shape | Pass |
| Schema validator self-test (non-vacuous) | Pass |
| Auth → Cart → Checkout → Inventory → Order → Payment (slip in MinIO, admin-only retrieval, PDF) | Pass |
| Checkout rollback on inventory failure | Pass |
| Ownership / idempotency / invalid-transition guards, no partial rows | Pass |
| Return → Refund → Wallet → Payout (evidence in MinIO, ledger = balance) | Pass |
| Return guards | Pass |
| DEF-755-01 / 02-03 / 05 known-defect tests | Fail (expected, see defects) |
| Promotion → Order (HTTP journey) | Not Run — skipped for time; service-level coverage exists in CheckoutPromotionIntegrationTest |
| Auction → Winner → Order (HTTP journey) | Not Run — skipped for time; service-level coverage in AuctionCheckoutIntegrationTest |
| FE Vitest/Cypress | N/A — no FE code touched |

## Defects
| ID | Sev | Producer / consumer | Steps | Expected / Actual |
|---|---|---|---|---|
| DEF-755-01 | Low | BE OrderController + MockPaymentController both @Tag("Orders") | GET /v1/api-docs | unique tags / "Orders" twice → strict OpenAPI tools reject doc |
| DEF-755-02 | Medium | FE auth.api.ts forgotPassword → BE | POST /api/v1/auth/password/forgot | endpoint exists / not published (only /reset, /change); hook unused by any page, so reset flow unreachable |
| DEF-755-03 | Low | FE checkout.api.ts getOrderDetails | GET /api/v1/checkout/orders/{id} | route exists or dead code removed / no route, never called |
| DEF-755-04 | Low | BE springdoc | 2xx bodies documented as `*/*` (278 ops) | application/json |
| DEF-755-05 | Medium | BE springdoc (OpenAPI 3.1) | e.g. GET /auth/me bio/phone/avatarUrl null | nullable types / declared non-null (43 fields seen, see undocumented-nulls.txt); generated clients would break, hand-written FE types OK |

No business code changed.
