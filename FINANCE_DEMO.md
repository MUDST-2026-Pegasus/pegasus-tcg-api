# FIN demo with Postman

Use `finance.postman_collection.json` against the local API at `http://localhost:8080/api/v1`.

## Start the backend

If the API is already running on port 8080, keep it running and go straight to Postman. Check it with:

```bash
curl http://localhost:8080/api/v1/games
```

To start the entire stack through Docker, run these commands in a terminal:

```bash
cd /Users/game/Desktop/devops/Pegasus/pegasus-tcg-api
make docker-run
make ps
```

`make docker-run` builds the backend and starts PostgreSQL, MinIO and the API. The first build takes time. Wait for Compose to finish before using Postman. Do not start `make run` while the Docker backend occupies port 8080.

For development with Java on the host instead, use:

```bash
cd /Users/game/Desktop/devops/Pegasus/pegasus-tcg-api
docker compose up -d --wait postgres minio
make run
```

`make run` keeps that terminal open while serving the API. `make up` currently starts all Compose services, including the backend build; it does not only start PostgreSQL and MinIO.

If this is a fresh database, apply migrations before seeding the demo accounts:

```bash
make db-migrate
make seed-catalog
```

The local database used to verify this collection already has these accounts, so seeding again is unnecessary:

| Role | Email | Local seed password |
| --- | --- | --- |
| Buyer | buyer@example.com | Password123! |
| Seller | seller@example.com | Password123! |
| Admin | admin@example.com | Password123! |

## Import and run in Postman

1. Import `finance.postman_collection.json` as a new collection. Its name is **Pegasus full FIN demo — item to payout**. The older collection has a different name and fewer requests.
2. Keep **No Environment** selected unless your environment intentionally overrides this collection. All required variables are in the collection.
3. Run folders **00 through 10 in order**. Within each folder, send requests from top to bottom. For a short presentation, folders **00–04** cover the main purchase and payout flow; folders **05–10** demonstrate after-sales and rejected actions.
4. For every `PUT PNG`, check **Body → binary**. The JSON contains this local file path:
   `/Users/game/Desktop/devops/Pegasus/pegasus-tcg-api/src/test/resources/db/seed-images/games/pokemon.png`.
   Select that file again if Postman asks for file access. It is exactly **40,058 bytes**, matching `imageSizeBytes`. If you choose another file, update the size and content type.
5. You can use **Run collection** with **1 iteration** after checking file access. Do not run individual later folders before their setup folders.

The collection creates all demo catalog and listing data. It uses existing accounts, creates a demo bank verification, stocks eight cards and creates five separate orders. Tokens, upload keys, catalog IDs, listing IDs, order IDs, return IDs, review IDs and payout IDs are saved by scripts. Refund queues select the refund for the current demo seller order automatically.

| Folder | What to show | Result |
| --- | --- | --- |
| 00 | Initialize a new run and login as buyer/seller/admin | Tokens saved automatically |
| 01 | Submit demo bank details, admin review and approval | Seller payout account exists |
| 02 | Create game, category, product, variant, image and commission rule | New catalog item and 5% rule for its game |
| 03 | Create listing, stock units, publish, shipping option, buyer address | Purchasable listing and checkout inputs |
| 04 | Cart, checkout, slip upload, admin approval, shipping, receipt, review, payout | Completed order, seller earnings, paid payout |
| 05 | New order with partial refund; buyer keeps the card | Refund processed and remaining earnings released |
| 06 | New order with full refund and ship-back | Returned unit restocked, full refund processed |
| 07 | Reject a payment slip, retry payment, cancel before shipment | Replacement payment succeeds, cancellation refund processed |
| 08 | New order whose return request is rejected | Return rejected and order completed |
| 09 | Reject a payout and fail another payout during processing | Reservations removed without charging a paid transfer |
| 10 | Duplicate/ownership/role checks, history and ledger | Expected errors pass tests; demo commission rule deactivated |

In folder 04, repeated payment verification returns **409** deliberately. Folder 10 deliberately expects **403**, **404** and **409** for forbidden or duplicate actions. Check **Test Results**: these responses are successful negative tests.

## What to say during the presentation

- The buyer pays the platform first. Each seller order has its own escrow.
- Approving a slip confirms receipt; the API does not call a bank.
- Confirming delivery releases earnings after commission. Watch the seller wallet before and after completion.
- A payout request reserves funds. The balance is debited when the admin marks the transfer paid.
- Refund approval creates an obligation. The platform balance changes when the admin confirms the refund as processed.
- A full return can restock the physical unit; a partial refund without ship-back keeps the card with the buyer.
- Only a completed seller order can receive a review; a hidden review disappears from the public response.

The PNG used for verification/slips/evidence is a fake local test image. All bank transfers in this demo are simulated by admin confirmation; use the collection only with local demo data.

## Restarting or troubleshooting

- `UNAUTHENTICATED`: rerun the relevant **Login buyer/seller/admin** request. Access tokens expire after 15 minutes. Do not rerun Initialize just to refresh a token.
- Missing-variable error: run the earlier requests that populate it. The collection stops instead of sending an empty `/orders/` URL.
- Start a new demo by running folder 00 from the beginning. A new run ID makes catalog slugs, SKUs and idempotency keys unique. Each full run creates new local records.
- If interrupted during seller verification, inspect `/sellers/me/verifications` or the admin verification queue and finish that pending review before submitting another verification.
- An uploaded PNG needs a fresh presigned URL if it expires: rerun its Presign request and then PUT.
- Stop host Java with `Ctrl+C`. Stop Docker services without deleting data with `make down`. `make docker-stop` removes volumes and should not be used to preserve this demo's data.

## Verification performed

On 2026-10-11, all **158 requests** were executed in collection order against the local API, including the upload bytes and the collection scripts. All **348 assertions** passed. Two additional final-wallet assertions were then added and checked against the resulting live wallet. This execution used an API runner, not the Postman desktop UI.

Database checks after the run also passed:

- Every wallet's net ledger equals its available plus pending balance.
- Platform pending equals the sum of unreleased and unrefunded escrow.
- Payment inflows minus refund/payout outflows equal total wallet balances.

With the tested 5% rule, THB 1,000 item price, THB 50 shipping and THB 500 payout, the first run ended with **PLATFORM 150.00 / pending 0.00** and **SELLER 2,460.00 / pending 0.00**. Subsequent runs add to the existing balances.
