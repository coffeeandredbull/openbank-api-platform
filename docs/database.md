# Database Design (Planned)

> This document describes the **planned** database design. No actual database
> code, JPA entities, schemas, or migrations exist yet. Design notes here guide
> later phases; the implementation may refine names and detail when built in
> each service phase.

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
Credentials (client id/secret hashes, API keys) and subscriptions remain planned
for the Subscription Service and will reference applications by ID.

### Subscription Service

| Entity | Key fields (planned) | Notes |
| --- | --- | --- |
| `credentials` | id, application_id, api_key_hash / client_id, client_secret_hash, scopes, created_at, revoked | one per application; only hashes of secrets stored |
| `api_versions_tiers` | id, tier_key, name, rate_limit (requests/period), burst | tier definitions (may live with API Mgmt) |
| `subscriptions` | id, application_id, api_version_id, tier_id, status (PENDING/ACTIVE/DENIED/REVOKED), subscribed_at, revoked_at | the link that grants access; references applications (API Mgmt) and api versions by ID |

> The `applications` entity is **implemented** in the API Management Service
> (Phase 11) — see above. The Subscription Service will reference applications
> by ID when credentials/subscriptions are built.

### Account Service

| Entity | Key fields (planned) | Notes |
| --- | --- | --- |
| `accounts` | id, owner_user_id, account_number, type (e.g. CURRENT/SAVINGS), currency, status, created_at | |
| `account_balances` | id, account_id, available, booked/current, currency, version | version for optimistic locking on balance updates |

### Payment Service

| Entity | Key fields (planned) | Notes |
| --- | --- | --- |
| `payments` | id, from_account_id, to_account_id/beneficiary, amount, currency, status (INITIATED/APPROVED/COMPLETED/REJECTED), reference, created_at, completed_at | references accounts owned by Account Service |

### Transaction Service

| Entity | Key fields (planned) | Notes |
| --- | --- | --- |
| `transactions` | id, account_id, payment_id, amount, direction (DEBIT/CREDIT), currency, balance_after, type, occurred_at | ledger; the record of every balance-affecting event |

### Analytics Service

| Entity | Key fields (planned) | Notes |
| --- | --- | --- |
| `request_logs` | id, api_version_id, application_id, user_id, path, http_status, latency_ms, method, occurred_at | telemetry intake |
| `daily_aggregates` | id, day, api_version_id, application_id, request_count, error_count, p50/p95 latency | precomputed aggregates for dashboard queries |

## Important Relationships

- `users` **1─n** `applications` (a developer owns applications; the
  relationship is logical — `applications.owner_user_id` references an Identity
  Service user ID with no foreign key, since the tables live in different
  services).
- `applications` **1—n** `credentials` — an application holds one
  primary credential set; rotation can create a new generation.
- `applications` **n—m** `api_versions` **through** `subscriptions` (an app can
  be subscribed to many API versions; each subscription references a tier).
- `users` **n—m** `accounts` — the Account Service resolves ownership by
  owner user id.
- `accounts` **1—n** `account_balances` (one active balance row, versioned for
  optimistic locking; history/normalization decided in the Account phase).
- `payments` **n—1** `accounts` — a payment references source and destination
  accounts (cross-service reference by ID only).
- `payments` **1—n** `transactions` — a completed payment yields transaction
  entries (typically a debit and a credit).
- `accounts` **1—n** `transactions` — transaction history per account.
- Analytics rows reference API versions and applications **by ID** (cross
  service; ingested via telemetry).

## Data Ownership Summary

| Data | Owner service |
| --- | --- |
| Users, roles, credentials, refresh tokens | Identity |
| API catalog, versions, tiers, developer applications | API Management |
| Credentials, subscriptions | Subscription |
| Accounts, balances | Account |
| Payments | Payment |
| Transactions / ledger | Transaction |
| Request logs, aggregates | Analytics |
| Caches, tokens, rate-limit counters | Redis (ephemeral — owned by platform infrastructure, not a system of record) |

## PostgreSQL Strategy

- Shared instance; per-service schemas; only the owning service writes its own
  tables.
- ACID transactions are used for money-adjacent flows (payment → balance →
  ledger). Since cross-service references have no DB-level FK, multi-service
  flows may need compensating/validation steps in the (shared) transaction
  boundary or explicit order of operations — the concrete approach is decided
  during the Payment/Transaction phases with root-cause-first diligence.
- Constraints, unique keys, and indexes (e.g. unique `username`, unique
  `account_number`, index on `transactions(account_id, occurred_at)`,
  index on `request_logs(occurred_at)`) are defined per service phase.
- DB migrations will be versioned and reproducible (e.g. Flyway/Liquibase)
  unless a lighter approach is justified; decision is pending and will be
  proposed before the first service phase.
- Consistent timezone handling (UTC) for timestamps.