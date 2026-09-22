# Requirements

> This document records the **planned** requirements of the OpenBank API
> Platform. Nothing here is implemented yet. Requirements are grouped into
> functional and non-functional categories and are intended to guide each
> build phase.

## Functional Requirements

### Identity

- Users can register with a unique username/e-mail and a password.
- Users can log in and receive a signed JWT access token.
- Logged-in users can view and update their profile.
- Admin users can manage users and assign roles.
- The Identity Service is the authoritative owner of users and credentials.

### API Management

- Administrators can publish APIs (name, base path, owner) and create multiple
  **versions** of an API (major.minor, endpoint base path, lifecycle state).
- Published APIs carry a description/documentation link.
- APIs can transition lifecycle states (e.g. published → deprecated →
  retired).
- The API Management Service is the source of truth for "which API versions
  exist and how they are routed".
- Developers can manage their **applications** (implemented, Phase 11): create
  (`POST /applications`), list own, fetch one, and partially update
  (`PATCH`). Applications are owned per user (owner from the JWT `sub` claim);
  names are not unique; no deletion in this phase.

### Subscriptions

- Applications are **implemented** (Phase 11, in the API Management Service);
  subscriptions are **implemented** (Phase 12, in the API Management Service);
  application credentials are **implemented** (Phase 13, in the API Management
  Service); tiers, credential lifecycle, and gateway enforcement remain
  planned.
- An application can be **subscribed** to an API version (implemented, Phase 12):
  `POST /subscriptions` creates the Application → Subscription → API Version
  link (`applicationId` + `apiVersionId`), `GET /subscriptions/{subscriptionId}`
  and `GET /subscriptions` read it back. Owner is derived from the JWT `sub`
  claim (never from the request body); a subscription is unique per
  (application, API version) — duplicates are rejected with `409`. Each
  subscription is metadata-only: no lifecycle, status, credentials, rate limit,
  or tier yet.
- Applications hold **credentials** (implemented, Phase 13): `POST /credentials`
  issues a `clientId` + `clientSecret` generated server-side; only the BCrypt
  hash is stored; the plaintext secret is returned exactly once at creation.
  `GET /credentials/{id}` and `GET /credentials` never return the secret or its
  hash. Ownership flows Credential → Application → owner (JWT `sub`).
- An application can be **subscribed** to an API version under a rate-limit
  **tier** (planned).
- Subscriptions can be approved/denied (per tier policy) and revoked (planned).
- Credential rotation, revocation, and status are **planned** (Phase 13 has no
  credential lifecycle).
- The Subscription Service decides whether a given application may call a given
  API version (planned; the gateway enforcement layer, which will authenticate
  applications with the Phase 13 credentials).

### Accounts (banking domain)

**Implemented (Phase 14, Payment Service — Account domain):**
- Each user has exactly **one** account (`owner_user_id` unique). It carries a
  currency (default `LKR`, value must match `[A-Z]{3}`) and **no balance**.
- `POST /accounts` (409 on duplicate), `GET /accounts/{id}` (404 on missing or
  not owned), `GET /accounts` (own accounts only, empty when none).
- The owner is always derived from the JWT `sub` claim; the body never supplies
  an owner. No update, no delete, no balance endpoints yet.
- **Planned extension:** account numbers/types/status, balances, balance
  authorization, and business rules for payments.

### Payments

**Implemented (Phase 14, Payment Service — Payment domain):**
- `POST /payments` creates a payment against an **owned** account (404
  `ACCOUNT_NOT_FOUND` otherwise) with an amount and optional description. The
  status is always `PENDING` and the currency always the account's — the client
  cannot choose either. No real payment is processed. A payment does **not**
  create a transaction.
- `GET /payments/{id}` (404 if not owned) and `GET /payments` (own, ordered by
  id).
- **Planned extension:** real processing/approval workflows, `to_account`/
  beneficiary, reference, completion timestamps, and status transitions.

### Transactions

**Implemented (Phase 14, Payment Service — Transaction domain):**
- `POST /transactions` records that a payment was made: it requires an account
  the caller owns (404 `ACCOUNT_NOT_FOUND` otherwise) and a payment that
  belongs to exactly that account (404 `PAYMENT_NOT_FOUND` otherwise, no
  existence leak). The type is always `PAYMENT` and the currency the account's —
  the client cannot choose either.
- `GET /transactions/{id}` (404 if not owned) and `GET /transactions` (own
  accounts, ordered by id).
- **Planned extension:** balances `balance_after`, debit/credit direction,
  paging, and date filters, plus automatic ledger entries when payments are
  processed for real.

### Analytics

- Request usage is collected (endpoint, status, latency, application).
- Aggregated metrics (requests per period, top APIs, error rates) can be
  queried for the Developer Portal.

### Developer Portal

- Browse API catalog and documentation.
- Register, log in, manage a profile.
- Create applications, subscribe to API versions, view subscription status.
- View usage analytics for owned applications.

## Non-Functional Requirements

- **Testability:** every service must have unit tests (JUnit, Mockito) and
  integration tests (Spring Boot Test, Testcontainers) with the real
  PostgreSQL/Redis topologies used in CI.
- **Security:** see [security.md](security.md) and the Authorization section
  below; security is enforced by default (fail closed), never fail open.
- **Performance:** API calls add bounded latency; hot lookups (API catalog,
  subscription checks) may be cached in Redis; rate-limit decisions must be
  fast.
- **Availability:** services are designed to be run in Docker Compose for local
  development and Kubernetes for deployment later; Kubernetes is planned, not
  yet present.
- **Observability:** gateway and services emit request/response telemetry that
  feeds analytics; no external observability stack is in scope unless asked.
- **Configurability:** environment-specific settings (datasources, Redis,
  secrets) come from environment variables, never hardcoded.
- **Maintainability:** thin controllers, services for business logic,
  repositories for DB access, DTOs at boundaries, centralized error handling,
  validated external input.

## Authentication Requirements

- All API calls (outside public discovery endpoints) require authentication.
- Primary mechanism: **JWT Bearer access tokens** issued by the Identity
  Service after a login/credential exchange.
- Token must carry user identity, roles, and scopes and must be validated
  (signature, expiry, issuer, audience) by the API Gateway.
- Server-side token revocation support via Redis-backed blacklist/refresh
  tokens (planned).
- Passwords are stored only as salted hashes (see [security.md](docs/security.md)).

## Authorization Requirements

- **RBAC:** users have roles (e.g. `USER`, `ADMIN`) that grant access to
  administrative vs. self-service operations.
- **Resource ownership:** users may only act on resources they own (their own
  apps, subscriptions, accounts) unless their role allows otherwise. For
  subscriptions (Phase 12) and credentials (Phase 13) the owner is always
  derived from the JWT `sub` claim — the service verifies the target application
  belongs to the caller and exposes cross-owner access as `404` (no existence
  leak). The same rule applies to accounts, payments, and transactions in the
  Payment Service (Phase 14): the caller's `sub` resolves the owned account and
  everything (payments, transactions) hangs off it; `ADMIN` owns what it creates
  and has no global access in any service.
- **Scopes in tokens:** JWT scopes gate specific operations (e.g.
  `accounts:read`, `payments:write`).
- **Subscription-based authorization:** even a valid token cannot invoke an API
  version unless the caller's application holds an active subscription to it.

## API Management Requirements

- Versioned APIs with explicit lifecycle states.
- Catalog endpoints (`GET /apis`, `GET /apis/{id}/versions`) are public;
  management endpoints are admin-only.
- Routing maps each API version to a backend service + base path at the
  gateway.

## Subscription Requirements

- Applications and subscriptions are implemented in the API Management Service
  (Phases 11–12). Subscriptions reference the owning application and an API
  version; the link is unique per (application, API version).
- Tier defines allowed rates (requests/second or per hour, burst) — planned,
  bound to credentials issued per application.
- Subscription statuses: `PENDING`, `ACTIVE`, `DENIED`, `REVOKED` — planned;
  Phase 12 subscriptions carry no lifecycle state.
- Credentials are bound to an application, not a user — planned.

## Rate Limiting Requirements

- Limits enforced per application (or per API-key) per the subscribed tier.
- Enforced **before** routing to backend services (at the gateway, via Redis).
- Response on exceed: `429 Too Many Requests` with respect /
  `Retry-After` headers.
- Rate-limit data lives in Redis only; it is not a system of record.

## Analytics Requirements

- Collect usage telemetry from the gateway (and services) — endpoint,
  application, status code, latency, timestamp.
- Provide aggregate queries (counts by hour, per API, per application, error
  rates) for the portal.
- No fake analytics: dashboards must reflect real recorded telemetry.

## Testing Requirements

- Unit tests: services and business rules (JUnit, Mockito).
- Integration tests: repositories/web layers against real PostgreSQL and Redis
  via Testcontainers.
- Security tests: una authenticated/unauthorized access is rejected (fail
  closed).
- Each phase compiles and passes its relevant tests before being considered
  done.

## Deployment Requirements

- Docker images per service; Docker Compose to run the whole platform locally
  (PostgreSQL, Redis, gateway, services, portal).
- GitHub Actions CI: build, test (with Testcontainers), publish images.
- Kubernetes manifests planned for a later phase (deployments, services,
  configmaps/secrets). Kubernetes is planned, not yet implemented.
- Secrets supplied via environment variables / orchestration secrets, never
  committed to the repository.