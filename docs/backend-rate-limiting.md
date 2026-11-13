# Backend rate limiting

Local work item created from the request to add backend rate limiting; no external ticket ID was supplied.

## Scope and approach

- Only `pegasus-tcg-api` changes, on `feat/backend-rate-limiting` from `develop`.
- Reuse the existing fixed-window limiter, client-address policy and security error envelope.
- Apply a shared IP budget before bearer authentication, after CORS, across `/api/v1/**`.
- Keep auth and promotion budgets independent.
- Bound limiter memory and serialize window changes so concurrent requests cannot exceed the cap.

## Acceptance criteria

1. API calls from one address share a configurable positive limit, default 300 per minute.
2. Exceeding it stops downstream work and returns 429, `RATE_LIMITED` and `Retry-After: 60`.
3. Different addresses have independent budgets, and quota resets at the 60-second boundary.
4. Preflight, monitoring and documentation calls remain available when the API quota is exhausted.
5. Allowed browser origins receive CORS headers on rejection and can read `Retry-After`.
6. Direct public callers cannot rotate their quota with spoofed forwarded headers.
7. Concurrent calls respect the cap; active-address churn cannot grow the map beyond 10,000 keys.
8. Existing auth and promotion behavior remains covered by regression tests.

## Operational limits

This follows the existing per-instance memory architecture, with no Redis or deployment changes.
Replicas and restarts do not share counters. A shared cluster-wide limit would require a shared
store or ingress quota. Fixed windows allow a burst around reset boundaries. Private peers
are trusted proxies under the existing policy; restrict app ingress accordingly. When the key
capacity is exhausted, new callers receive 429 until expired keys are reclaimed (sweep at most
once a second while full). `Retry-After: 60` is a conservative delay, not a precise countdown.

## Validation

Dedicated tests cover the acceptance criteria, including the actual Spring Security chain
without a database. Run the rate-limit tests, client-address tests and CORS tests with Gradle.

Validation completed: main and test sources compiled; targeted JUnit Jupiter tests passed,
including the real security-chain test. `git diff --check` passed. Existing generated jOOQ
sources were reused with `-x jooqCodegen -x flywayMigrate`; no database migration ran.

The first filtered Gradle invocation also discovered Cucumber scenarios despite class filters;
83 scenarios failed during application logging initialization (`StructuredLogFormatterFactory`).
The relevant tests were then isolated to the `junit-jupiter` engine with a temporary local
Gradle init script. This does not establish a passing full acceptance suite.
