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

### Subscription Service

| Entity | Key fields (planned) | Notes |
| --- | --- | --- |
| `applications` | id, owner_user_id, name, description, status, created_at | developer application |
| `credentials` | id, application_id, api_key_hash / client_id, client_secret_hash, scopes, created_at, revoked | one per application; only hashes of secrets stored |
| `api_versions_tiers` | id, tier_key, name, rate_limit (requests/period), burst | tier definitions (may live with API Mgmt) |
| `subscriptions` | id, application_id, api_version_id, tier_id, status (PENDING/ACTIVE/DENIED/REVOKED), subscribed_at, revoked_at | the link that grants access |

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

- `users` **1─n** `applications` (a developer owns applications).
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
| API catalog, versions, tiers | API Management |
| Applications, credentials, subscriptions | Subscription |
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