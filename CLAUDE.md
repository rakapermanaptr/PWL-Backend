# CLAUDE.md — PWL Cashier Backend Guidelines

This file is read by Claude at the start of every session in this project.
Keep it accurate and up to date — it is the single source of truth for
architecture, conventions, and constraints Claude must follow.

## Project Overview
- Service: `pwl-cashier` — central REST API for the **Prima Wash** Android POS
  (Kasir Prima Laundry), a multi-branch laundry chain in Jakarta.
- Clients: Android tablets (one per branch counter), shared by staff on shift.
  No web frontend — every owner function lives in the tablet app.
- Scale: 4 branches × ~150 transactions/day, ~10 devices. Design safe to 10×.
- Language: **all user-facing strings are Indonesian**; code, comments, and
  this document are English.
- Contract: `PRD Backend — REST API v1`, copied into this repo at
  `docs/PRD-Backend-REST-API.md` (source of truth lives in the Android repo at
  `docs/backend/`; re-copy it here whenever it changes). **The PRD wins over
  this file and over README.md.** Read the relevant PRD section before implementing an
  endpoint — it specifies validation order, exact error messages, and the
  full list of atomic side effects for each one.

## Tech Stack
- Language: Kotlin 100%, JVM 21
- Framework: Ktor Server 3.x (Netty engine)
- Database: PostgreSQL 16, accessed via Exposed + HikariCP
- Migrations: Flyway (versioned SQL in `src/main/resources/db/migration`)
- DI: Koin
- Serialization: kotlinx.serialization
- Auth: JWT access token + opaque refresh token; Argon2id + HMAC-SHA256 for PINs
- Queue: transactional outbox table in Postgres — no Redis, no broker. The
  table and its writes exist from day one; the worker that drains it to Meta is
  built last (see the final section)
- Testing: JUnit5, Kotest assertions, MockK, Testcontainers
- Docs: OpenAPI 3.1 (`openapi.yaml`)

## Module Structure
```
src/main/kotlin/id/primawash/api/
  Application.kt      -> entry point: plugins, DI, routing mount
  plugins/            -> Serialization, StatusPages, Auth, CallLogging, RateLimit
  common/             -> ErrorEnvelope, BusinessRuleException, Money, WibClock,
                         Idempotency, Pagination, AuditWriter
  db/                 -> Database.kt (Hikari + Exposed), Tables.kt, tx helpers
  auth/ branch/ staff/ catalog/ customer/ order/ shift/ wa/ report/ sync/
src/main/resources/db/migration/   -> V1__init.sql, V2__…
```

Every feature package uses the same four files with fixed responsibilities:

| File | Contains | Must not contain |
|---|---|---|
| `XxxRoutes.kt` | Route definitions, request parsing, role/branch authorization, response DTO mapping | Business rules, SQL |
| `XxxService.kt` | Business rules, DB transaction boundary, audit + outbox writes | Ktor types (`ApplicationCall`, `PipelineContext`) |
| `XxxRepository.kt` | Exposed queries | Business decisions |
| `XxxDto.kt` | `@Serializable` request/response types (camelCase) | Logic |

Rule: feature packages do not call each other's `Repository` directly. Cross-domain
work goes through the other package's `Service`, inside the caller's transaction.

## The Three Invariants

Nearly every review comment on this codebase traces back to one of these.

### 1. Server-authoritative
The client computes totals, points, and the next order number **for display
only**. The server recomputes everything from its own data and persists its own
result. Never trust a client-supplied `total`, `earnedPoints`, `subtotal`, or
order `number`.

`expectedTotal` and `unitPrice` in the request are **assertions to verify**, not
values to store:
- `unitPrice` ≠ current price list → `409 PRICE_CHANGED` with the new prices.
- `expectedTotal` ≠ server total → `409 TOTAL_MISMATCH`.

The one deliberate exception is offline sync: `POST /orders/sync` stores the
device's `unitPrice` snapshot because the customer already paid it, and flags
the order `PRICE_MISMATCH` instead of rejecting it.

### 2. One action = one database transaction
`POST /orders` writes — atomically — the order, its items, an `order_events`
row, points ledger entries, the customer's cached balance and visit count, the
reward's `used_count`, shift totals, a `cash_entries` row for cash payments,
`daily_sales`, one or more `audit_log` rows, and the WhatsApp outbox rows.
Either all of it lands or none of it does. Never split this across requests, and
never open a second connection inside a transaction.

### 3. WhatsApp only ever goes through the outbox
Business code writes a `wa_messages` row with status `QUEUED` inside the same
transaction. A separate worker polls and calls Meta. **Never call the Cloud API
from a request thread**, and never mark a message `SENT`/`TERKIRIM` because the
code path "should" have sent it — that bug exists in the current client and is
exactly what this backend replaces (delivery rate must be honest).

This invariant is **not** phased. The Meta integration is deliberately built last
(final section of this file), but the `QUEUED` rows are written from the moment
the triggering feature exists. Until the worker ships, rows simply stay `QUEUED` —
that is the correct, honest state, and it is what makes the deferral safe.

## Data & Time Rules
- **Money**: `Long` rupiah, `BIGINT` in Postgres. No floats, no decimals, ever.
- **Quantity**: `BigDecimal` / `NUMERIC(7,1)`. Must be a multiple of the
  service's `step` (0.5 for `kg`, 1.0 otherwise) and ≤ 999.
- **Rounding**: `round_half_up(qty × unitPrice)` computed in `BigDecimal`.
- **Timestamps**: store and return UTC with milliseconds. Never depend on the
  server's system timezone.
- **Business date**: `Asia/Jakarta`, derived from `capturedAt` — not from
  `now()`. This drives daily revenue, order numbering, and "today". An offline
  transaction synced tomorrow still counts toward the day it happened.
- **Loyalty rate**: always the `loyalty_rates` row effective at the order's
  `capturedAt`, never the current rate. `loyalty_rates` is history — rows are
  inserted, never updated.
- **Phone numbers**: stored as `phone` (display `0812-3390-4471`) plus
  `phone_digits` (unique, `08…`, 10–13 digits). WhatsApp needs E.164 without
  `+`: `6281233904471`. Masked in logs as `0812-****-4471`.
- **JSON is camelCase; database columns are snake_case.** Map explicitly.

## Error Handling
Every error uses one envelope, produced centrally in `StatusPages`:

```json
{ "error": { "code": "SHIFT_NOT_OPEN", "message": "…", "details": { … }, "requestId": "req_…" } }
```

- `message` is an **Indonesian sentence the cashier sees verbatim**. Copy the
  exact wording from PRD Appendix B — those strings come from the existing
  client use cases, so changing them changes the UX.
- Business rule violations are thrown as `BusinessRuleException(code, message,
  details)` from the `Service` layer and mapped to `422` (or `409` for
  conflicts) in `StatusPages`. Routes never build error responses by hand.
- Validate **in the order the PRD lists**. `POST /orders` checks branch → open
  shift → non-empty cart → items valid → prices unchanged → reward eligible →
  total matches. The first failure wins, and the client shows that message.
- `5xx` means retryable to the client. Never return `5xx` for a business rule.

## Security Rules
- PIN space is 4–6 digits — small enough to brute-force offline, so the
  protection is layered: `pin_lookup = HMAC-SHA256(PIN_PEPPER, pin)` for
  lookup (pepper lives in the secret manager, **never in the database**),
  `pin_hash = Argon2id(pin)` for verification, plus rate limiting and device
  binding.
- **Never log a PIN**, in any form, at any level. Redact bodies for `/auth/*`
  and `/staff*`.
- Rate limits: 5 wrong PINs per device in 5 min → `423` for 5 min; 5 consecutive
  wrong PINs per account → 15 min lock + audit. Every failed login is audited
  (without the PIN). General limit: 120 req/min per device.
- **Do not add a "does this PIN belong to an active account?" endpoint.** The
  client's `matchesActivePin()` auto-submit helper must stay client-side; as an
  API it is a PIN-guessing oracle that bypasses failed-login accounting.
- Authorization comes from the **token**, never from the request body. A cashier
  is scoped to their own branch; passing `branchId` for another branch is `403
  BRANCH_SCOPE`, not a filter hint.
- `reset-pin` returns the new PIN exactly once — never cached, never logged —
  and revokes all of that staff member's sessions.
- The application DB role has no `UPDATE`/`DELETE` on `audit_log`.

## Audit Rules
Every mutation writes an `audit_log` row **in the same transaction**, with
`staff_id`, `action_type` (from PRD Appendix C), `actor_name` ("Siti N.
(kasir)"), and an Indonesian `action` sentence matching the client's existing
format. `branch_id = null` means the entry applies to all branches and always
appears in branch-filtered queries. Never write audit rows from a background
job "after the fact" — if the transaction rolls back, the audit must too.

## Concurrency Rules
Two tablets in the same branch act on the same data constantly. Enforce in the
database, not just in code:

| Situation | Mechanism |
|---|---|
| Two devices open a shift | Partial unique index `shifts(branch_id) WHERE closed_at IS NULL` → one gets `409 SHIFT_ALREADY_OPEN` |
| Two devices advance the same order | Client sends `fromStatus`; mismatch → `409 STATUS_CHANGED` with the current order |
| Two orders at once in one branch | `order_number_counters` incremented with `UPDATE … RETURNING` inside the order transaction |
| Network retry of a mutation | `Idempotency-Key` header; same key + same body → same stored response, different body → `409 IDEMPOTENCY_MISMATCH` |
| Offline queue sent twice | `orders.client_tx_id` UNIQUE → second attempt returns `DUPLICATE` with the existing order |
| Concurrent outbox workers | `FOR UPDATE SKIP LOCKED` |

Status transitions move forward exactly one step:
`DITERIMA → PROSES → SIAP → SELESAI`. No skipping, no going back.

## Offline Sync Rules
`POST /orders/sync` handles transactions the customer **already paid for**. The
rule is: get them recorded, flag anything odd, never lose money.

- Process each transaction independently. One rejection does not stop the batch;
  the response is a per-transaction `CREATED` / `DUPLICATE` / `REJECTED` list.
- Never reject a paid transaction because the shift is closed. Attach it to the
  original shift if still open, else the branch's current open shift, else the
  original shift with flag `LATE_AFTER_SHIFT_CLOSE` + audit.
- Use the rate, business date, and order number of `capturedAt`, not of now.
- Inactive service or changed price → accept the snapshot, add a flag and audit.
- New customers created offline are upserted by phone; an existing phone wins and
  the client's UUID is mapped to the server id in the response.
- Point redemption is never allowed offline (`422 REDEEM_OFFLINE`).

## Known Client Bugs This Backend Must Not Reproduce
The PRD's findings T1–T12 exist because the client got these wrong. When
touching a related area, check the PRD section named here.

| # | Trap | Correct behavior |
|---|---|---|
| T1 | PIN hashed SHA-256 with a static salt | HMAC lookup + Argon2id + rate limit (§12.2) |
| T2 | Reactivating a staff account can duplicate an active PIN | `409 PIN_CONFLICT`; partial unique index (§8.3) |
| T3 | `matchesActivePin` as an API is a guessing oracle | Endpoint not provided (§8.1) |
| T4 | Order id `TBT-0829-015` has no year → collides next year | UUID `id` + display `number` unique per (branch, business date) (§7.3) |
| T5 | Sync requires an open shift, uses today's rate, stops at first failure | Shift and rate follow `capturedAt`; per-transaction results (§11) |
| T6 | WA marked `TERKIRIM` immediately, without sending | Outbox + worker + Meta webhook (§10). While the worker is unbuilt, `QUEUED` is the correct terminal state — never fake `SENT` |
| T7 | "Ready" notification keyed on the order's stamped `waStatus` | Check the customer's opt-in **at the moment the event happens** (§8.6) |
| T9 | Reward minimum spend documented but unenforced | Enforce optional `min_subtotal` server-side (§7.2) |
| T10 | Audit stores only the actor's name | Store `staff_id` + structured `action_type` (§7.2) |
| T11 | Dashboard metrics computed over all time | Explicit period, default 30 days (§9.4) |
| T12 | Product PRD says single-branch pilot, app is multi-branch | Multi-branch schema from day one; pilot controlled by the `active` flag |

## Testing Conventions
- Naming: `` `should reject order when no shift is open`() ``
- Every business rule in a `Service` needs a unit test before merging.
- Every endpoint needs a contract test against `openapi.yaml`.
- Integration tests run against real Postgres via Testcontainers — never against
  an in-memory database, because partial unique indexes and `SKIP LOCKED` are
  exactly what needs testing.
- The 20 acceptance scenarios in PRD §16 are the integration suite's backbone.
  Concurrency scenarios (#3, #6, #9, #10) must actually run in parallel.
- Mock the WhatsApp Cloud API; never hit Meta from a test.

## Code Style
- Official Kotlin style guide. `ktlint` + `detekt` enforced in CI.
- Max line length 120. No wildcard imports.
- KDoc on public `Service` functions describing the business rule and the PRD
  section it implements.

## Git & Commit Convention
- Conventional Commits: `feat:`, `fix:`, `refactor:`, `chore:`, `test:`
- Branch naming: `feature/<name>`, `bugfix/<name>`, `hotfix/<name>`
- A PR that changes the API surface updates `openapi.yaml` in the same PR.

## Do
- Read the relevant PRD section before writing an endpoint — the validation
  order and error strings are part of the contract.
- Put business rules in `Service`, SQL in `Repository`, HTTP concerns in `Routes`.
- Wrap each action in one transaction, including its audit and outbox writes.
- Enforce invariants with database constraints as well as code.
- Return Indonesian, cashier-ready `message` text copied from PRD Appendix B.
- Use `Long` for money and `BigDecimal` for quantity.
- Derive the business date from `capturedAt` in `Asia/Jakarta`.

## Don't
- Don't trust client-computed totals, points, or order numbers.
- Don't call the WhatsApp Cloud API outside the worker.
- Don't log PINs, raw tokens, or unmasked phone numbers.
- Don't read `branchId` or `role` from the request body — they come from the token.
- Don't edit a Flyway migration that has been merged; add a new one.
- Don't `UPDATE`/`DELETE` `audit_log`, or mutate `loyalty_rates` history.
- Don't reject an already-paid offline transaction — flag it instead.
- Don't invent new error codes or reword existing messages without updating the
  PRD and telling the Android team; the client displays them verbatim.
- Don't add a dependency, change the DI framework, or restructure packages
  without flagging it first.

## Useful Commands
```
./gradlew run                 # API on :8080
./gradlew run -Pworker        # WhatsApp worker + scheduler (final phase only)
./gradlew flywayMigrate
./gradlew seedPilot           # dev/staging only
./gradlew test
./gradlew integrationTest     # Testcontainers Postgres
./gradlew contractTest        # validate against openapi.yaml
./gradlew ktlintCheck detekt
docker compose up -d db
```

## Instructions for Claude When Generating Code
- Follow the four-file package layout (`Routes`/`Service`/`Repository`/`Dto`)
  and mirror an existing feature package rather than inventing a structure.
- Quote the PRD section you are implementing in the KDoc, and implement the
  validation checks in the documented order with the documented messages.
- Include unit tests for business rules and a contract test for any new endpoint.
- When an endpoint mutates data, list its atomic side effects in the PR
  description and confirm they all sit inside one transaction.
- Ask before introducing a library, changing the auth model, altering an error
  code or message, or restructuring packages.
- If the PRD and this file disagree, follow the PRD and flag the discrepancy.

## Meta / WhatsApp Integration — Built Last

The Meta integration is the **final phase** of v1, after auth, master data,
orders, shifts, sync, reports, and shadow mode. Everything else must be
buildable, testable, and runnable in shadow mode with **no Meta credentials at
all**. Milestone table and the operational runbook live in
[`README.md`](README.md#integrasi-meta--whatsapp-fase-akhir).

**What is deferred:** `WaCloudApiClient`, the worker process, retry/backoff,
`GET/POST /webhooks/whatsapp`, `/wa/failures*`, `/wa/summary`, `/wa/templates`,
and the daily scheduler.

**What is not deferred**, and must be correct from the feature that introduces
it:
- the `wa_messages` table and its Flyway migration;
- writing `QUEUED` rows inside the triggering business transaction (Invariant 3);
- checking the customer's opt-in **at the moment the event happens**, never from
  a `waStatus` stamped on the order (trap T7);
- walk-in and non-opted-in customers never get a row at all.

**Rules while the integration is unbuilt:**
- No code path may call the Cloud API, and no stub may pretend to. Do not add a
  "fake sender" that flips rows to `SENT` in dev — it trains exactly the bug
  (T6) this backend exists to fix.
- The API and its tests must boot without `WA_ACCESS_TOKEN`,
  `WA_PHONE_NUMBER_ID`, `WA_APP_SECRET`, or `WA_VERIFY_TOKEN`. Failing startup on
  a missing `WA_*` variable is a bug; the worker may require them, the API may
  not.
- `/wa/*` and `/webhooks/whatsapp` stay out of `openapi.yaml` until the PR that
  implements them.
- Error codes and Indonesian messages for WA failures are still specified in PRD
  Appendix B — do not invent them early and do not reword them later.

**When the phase starts:** follow PRD §10 for the worker, retry classification,
webhook signature verification, and `STOP`/`BERHENTI` opt-out. Mock the Cloud
API in every test. Before the worker is enabled in production, the backlog of
stale `QUEUED` rows accumulated during earlier milestones must be cancelled in a
one-off operational script (status `CANCELLED` + audit), or go-live will blast
days-old receipts and "cucian siap diambil" messages at real customers.
