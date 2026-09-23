# Database Design (Planned)

> This document describes the **planned** database design. Early implementation
> phases created initial tables (see "Implemented so far" notes throughout):
> Identity Service tables, API Management tables (`apis`, `api_versions`,
> `applications`, `subscriptions`, `credentials`), and Payment Service tables
> (`accounts`, `payments`, `transactions`). Design notes here guide later
> phases; the implementation may refine names and detail when built in each
> service phase.

## Strategy

- **One shared PostgreSQL instance** (per the architecture goal) with each
  service owning its own schema/tables. Service **logical** ownership is
  strict even though the physical database is shared.
- Databases accessed through **Spring Data JPA** repositories.
- **Relationships across services** are referenced by identifier (e.g. a
  payment references an account ID, a subscription references an API-version
  ID) but are **not** enforced by cross-schema foreign keys. Each service
  validates IDs with the owning service. Rationale: keeps services decoupled
  and the ownership model explainable, at the acceptable cost of no DB-level
  cross-schema integrity.
- **Redis is not a system of record.** Nothing below is stored in Redis; it
  only holds caches, tokens, and rate-limit counters.
- Plural, snake_case table names and precise constraints are decisions for the
  implementation phases; not yet committed to.

## Planned Entities (by owning service)

### Identity Service

| Entity | Key fields (planned) | Notes |
| --- | --- | --- |
| `users` | id, username, email, password_hash, roles, created_at, enabled | credentials stored only as salted hashes |
| `roles` | id, name | e.g. `USER`, `ADMIN` |
| `user_roles` | user_id, role_id | many-to-many join |
| `refresh_tokens` | id, user_id, token_hash, expires_at, revoked | server-side handling; hash stored, not raw token |
| (client registry) | id, client_id, client_secret_hash, name, type, scopes, redirect_uris, enabled | OAuth2-style application credentials for the platform itself |

### API Management Service

| Entity | Key fields (planned) | Notes |
| --- | --- | --- |
| `apis` | id, name, description, base_path, owner, created_at | top-level catalog item |
| `api_versions` | id, api_id, version (e.g. `1.0`, `2.0`), version_base_path, backend_url/route, status (PUBLISHED/DEPRECATED/RETIRED), documentation_url, created_at | routing target metadata |

**Implemented so far (Phases 8–10):** `apis` and `api_versions` exist as JPA
entities. `apis`: `name`/`description`/`context_path`/`created_at`/`updated_at`.
`api_versions`: `id`/`api_id`/`version`/`lifecycle`/`created_at`/`updated_at`.
One logical API can have **multiple versions**; an `api_versions` row belongs to
exactly one `apis` row via a many-to-one `api_id` foreign key. Version strings
are unique **scoped to the API** (DB unique constraint on `(api_id, version)`),
so the same version value may exist under different APIs.

The `api_versions` **lifecycle** column (Phase 10) stores the version lifecycle
as a **string** (`CREATED`, `PUBLISHED`, `DEPRECATED`, `RETIRED`), never as an
ordinal, and defaults to `CREATED` for every new row. New versions always start
in `CREATED`; a caller cannot pick the initial state. Lifecycle transitions are
validated in the service layer, not by the database: `CREATED → PUBLISHED`,
`CREATED → RETIRED`, `PUBLISHED → DEPRECATED`, `DEPRECATED → RETIRED`.
Persisting the state is metadata-only — the database has no logic about whether
deprecation or retirement affects routing or access (those components do not
exist yet). A lifecycle change updates `updated_at`; `created_at` is
write-once (`updatable = false`).

Version base paths, backend routing, and documentation links are **not** part of
Phase 9/10 — they remain planned fields. The `subscriptions` reference
`api_versions` by ID (cross-service reference, consistent with the ownership
model).

**Implemented so far (Phase 11) — `applications`:** developer applications are
owned by the **API Management Service** (they previously appeared only in the
planned Subscription Service section below; the registry was built now in the
API Management Service because that is where API-centric self-service lives).
Actual implemented columns: `id`, `name`, `description` (nullable),
`owner_user_id`, `created_at`, `updated_at`. `owner_user_id` is a logical
reference to an Identity Service user — there is **no** `users` table here, no
cross-service foreign key, and no uniqueness constraint on `name` (the same name
may be reused by the same or different developers). Ownership is enforced by the
service layer (`owner_user_id` from the JWT `sub` claim), never taken from the
request body. `created_at` is write-once; `updated_at` changes on update.
Credentials (client id/secret hashes) are **implemented** (Phase 13 — see below);
subscription tiers/status and any further Subscription-Service-owned material
remain planned and will reference applications by ID.

**Implemented so far (Phase 12) — `subscriptions`:** the Application →
Subscription → API Version link is now implemented in the **API Management
Service** (it previously appeared only in the planned Subscription Service
section below). Actual implemented columns: `id`, `application_id`,
`api_version_id`, `created_at`, `updated_at`. Both `application_id` and
`api_version_id` are `NOT NULL` **foreign keys** to `applications` and
`api_versions` respectively — this is allowed because all three tables live in
the same service. A **unique constraint** on `(application_id, api_version_id)`
(DB-level `uc_subscription_application_api_version`) prevents duplicate
subscriptions; the service performs the same check up front and maps any race
condition to `409 SUBSCRIPTION_ALREADY_EXISTS`. A subscription has **no**
lifecycle state, status, tier, credential, or rate-limit fields — it is the
link only; credentials, tiers, and status/lifecycle remain planned. Two
subscriptions may target the same API version (different applications) and the
same application may subscribe to multiple API versions. `created_at` is
write-once; `updated_at` is set on creation (both equal at creation time).

**Implemented so far (Phase 13) — `credentials`:** application credentials are
now implemented in the **API Management Service** (they previously appeared
only in the planned Subscription Service section below). Actual implemented
columns: `id`, `application_id`, `client_id`, `client_secret_hash`,
`created_at`, `updated_at`. `application_id` is a `NOT NULL` **foreign key** to
`applications` (same service, allowed); `client_id` and `client_secret_hash`
are `NOT NULL`. A **unique constraint** on `client_id` (DB-level
`uc_credential_client_id`) guarantees each credential's client id is unique;
the service also checks for existing ids up front and, if a collision somehow
occurs, regenerates and retries instead of exposing a database error. Only the
BCrypt **hash** of the client secret is stored — the plaintext secret is
returned once at creation and is never persisted, retrieved, or logged. A
credential has **no** status, expiry, scopes, permissions, rate limit, or
gateway configuration fields; credential rotation/revocation/status remain
planned. `created_at` is write-once; `updated_at` is set on creation (both
equal at creation time). Ownership is logical: Credential → Application →
`owner_user_id`, with no user table and no cross-service foreign key.

### Subscription Service

| Entity | Key fields (planned) | Notes |
| --- | --- | --- |
| `credentials` | id, application_id, api_key_hash / client_id, client_secret_hash, scopes, created_at, revoked | one per application; only hashes of secrets stored. **Note:** the base credential (`id`/`application_id`/`client_id`/`client_secret_hash`/timestamps) is already **implemented** in the API Management Service (Phase 13) — see above; scopes, rotation/status (`revoked`) remain planned |
| `api_versions_tiers` | id, tier_key, name, rate_limit (requests/period), burst | tier definitions (may live with API Mgmt) |
| `subscriptions` | id, application_id, api_version_id, status (PENDING/ACTIVE/DENIED/REVOKED), tier_id, subscribed_at, revoked_at | the link that grants access; references applications (API Mgmt) and api versions by ID. **Note:** the base link (`id`/`application_id`/`api_version_id`/timestamps) is already **implemented** in the API Management Service (Phase 12) — see above; tier, status, and lifecycle remain planned |

> The `applications` entity is **implemented** in the API Management Service
> (Phase 11), the Application → Subscription → API Version link in Phase 12, and
> application **credentials** in Phase 13 — see above. The Subscription Service
> will reference applications and subscriptions by ID when tiers/status are
> built, and will verify the Phase 13 credentials when the gateway authenticates
> an application.

### Account Service

The Account domain is **implemented** (Phase 14) in the **Payment Service**
(not a separate service). It previously appeared as a planned standalone
service below; the implementation deliberately hosts Account, Payment, and
Transaction together because they share one database and money rules.

| Entity | Implemented columns | Notes |
| --- | --- | --- |
| `accounts` | id, owner_user_id, currency, created_at, updated_at | one account per user: `owner_user_id` is `NOT NULL` with a **unique constraint** (`uc_account_owner_user_id`) — no null owner, no duplicate accounts for one user. `currency` is `NOT NULL`, default `LKR`, client-supplied value must match `[A-Z]{3}`. **No balance** and no balance table (deliberately out of scope; balances are planned future work). `owner_user_id` is a logical reference to an Identity Service user — no `users` table, no cross-service foreign key |

### Payment Service

| Entity | Implemented columns | Notes |
| --- | --- | --- |
| `payments` | id, account_id, amount, currency, description, status, created_at, updated_at | created against an owned account. `account_id` is a `NOT NULL` **foreign key** to `accounts` (same service). `amount` is `numeric(19,2)`; `currency` is `NOT NULL` and taken from the account — the client cannot choose it. `description` is nullable (length ≤ 500). `status` is a persisted **string** (`PENDING`/`COMPLETED`/`FAILED`) always starting `PENDING` — no lifecycle endpoints yet; `COMPLETED`/`FAILED` values exist only as future transition targets. `created_at`/`updated_at` timestamps (UTC). No processing/approval/gateway/lifecycle fields yet |

### Transaction Service

| Entity | Implemented columns | Notes |
| --- | --- | --- |
| `transactions` | id, account_id, payment_id, type, amount, currency, created_at | the record that a payment was made. `account_id` and `payment_id` are `NOT NULL` **foreign keys** to `accounts` and `payments` (same service); the service requires the payment to belong exactly to that account. `type` is a persisted **string**, always `PAYMENT` (client cannot choose it). `amount` is `numeric(19,2)`; `currency` comes from the account. `created_at` only (no `updated_at` — a transaction is immutable). A payment does **not** automatically create a transaction; a transaction must be created explicitly. No `direction`/`balance_after`/`occurred_at` columns yet — those await balance support |

### Analytics Service

| Entity | Implemented columns | Notes |
| --- | --- | --- |
| `runtime_analytics_events` | id, event_timestamp, api_context, api_version, http_method, status_code, latency_ms, authentication_type, user_id, application_id | one row per managed API invocation (Phase 23, Slices 3–5). Append-only, immutable. `event_timestamp` is an `Instant`; `status_code` is an int constrained to 100–599; `latency_ms` is a non-negative long; `authentication_type` is a persisted string enum (`JWT` / `CLIENT_CREDENTIAL`); `user_id`/`application_id` are plain identifiers (nullable for JWT callers). No request/response bodies, tokens, headers, or secrets are ever stored. Indexed on `event_timestamp` (Phase 23, Slice 6) for time-windowed aggregation. Usage summaries are computed **on demand** in PostgreSQL (`GET /analytics/usage`, Slice 6) — no precomputed aggregate tables exist yet.

## Important Relationships

- `users` **1─n** `applications` (a developer owns applications; the
  relationship is logical — `applications.owner_user_id` references an Identity
  Service user ID with no foreign key, since the tables live in different
  services).
- `applications` **1—n** `credentials` — an application holds one or more
  credential sets; each `credentials` row references its application via a
  DB-level foreign key and stores only the BCrypt hash of the client secret.
  Implemented (Phase 13) with a unique constraint on `client_id`. Rotation
  (new generations) and revocation/status remain planned.
- `applications` **n—m** `api_versions` **through** `subscriptions` (an app can
  be subscribed to many API versions; a subscription references one application
  and one API version). Implemented (Phase 12) with real DB-level foreign keys
  from `subscriptions` to both tables plus a unique constraint on
  `(application_id, api_version_id)`. Tier binding remains planned.
- `users` **1—1/n** `accounts` — one account per user; `accounts.owner_user_id`
  is unique and references an Identity Service user ID with no foreign key,
  since the tables live in different services. Implemented (Phase 14).
- `accounts` **1—n** `payments` — a payment references one account via a
  DB-level foreign key (same service). Implemented (Phase 14); the planned
  destination/beneficiary account reference remains future work.
- `payments` **1—n** `transactions` — a transaction records exactly one
  payment and must reference the same account as that payment (enforced in the
  service, plus a DB-level FK to `payments`). Implemented (Phase 14); the
  planned debit/credit balance-affecting entries remain future work.
- `accounts` **1—n** `transactions` — transaction history per account (DB-level
  FK). Implemented (Phase 14).
- Analytics rows reference applications and users **by ID** (cross service;
  ingested via the gateway's telemetry; API context/version are stored as
  strings on the event).

## Data Ownership Summary

| Data | Owner service |
| --- | --- |
| Users, roles, credentials, refresh tokens | Identity |
| API catalog, versions, tiers, developer applications, subscriptions, credentials | API Management |
| Credential verification (gateway), tiers, rate limiting | Subscription |
| Accounts | Payment (Account domain) |
| Payments | Payment |
| Transactions / ledger | Payment (Transaction domain) |
| Request logs, aggregates | Analytics |
| Caches, tokens, rate-limit counters | Redis (ephemeral — owned by platform infrastructure, not a system of record) |

## PostgreSQL Strategy

- Shared instance; per-service schemas; only the owning service writes its own
  tables.
- ACID transactions are used for money-adjacent flows. Within one service
  (e.g. Payment Service create-payment/create-transaction) DB-level foreign
  keys and unique constraints are used; **cross-service** references (e.g.
  `owner_user_id`, application → API version before Phase 12) are plain ID
  columns with **no cross-schema FK** and are validated in the service layer —
  root-cause-first, keeping services decoupled.
- Constraints, unique keys, and indexes (e.g. unique `username`, unique
  `owner_user_id` per account, FK index on `payments(account_id)`,
  FK index on `transactions(account_id)` and `transactions(payment_id)`) are
  defined per service phase.
- DB migrations will be versioned and reproducible (e.g. Flyway/Liquibase)
  unless a lighter approach is justified; decision is pending and will be
  proposed before the first migration-heavy phase. Today services use Hibernate
  `ddl-auto: update` for development.
- Consistent timezone handling (UTC) for timestamps.