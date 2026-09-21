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

### Subscriptions

- Developers can register **applications** (name, description, owner).
- An application can be **subscribed** to an API version under a rate-limit
  **tier**.
- Subscriptions can be approved/denied (per tier policy) and revoked.
- Credentials (API key / client ID/secrets) are issued per application and can
  be rotated.
- The Subscription Service decides whether a given application may call a given
  API version.

### Accounts (banking domain)

- Account entities (number, type, owner, currency) and balances are stored and
  exposed.
- Balance reads and writes are authorized.
- Accounts validate against business rules relevant to payment operations.

### Payments

- Payment instructions (from account, to account/beneficiary, amount, currency,
  reference) can be initiated.
- Payment approval/validation business rules are applied before funds move.
- Successful payments update balances and produce transaction records.

### Transactions

- Every balance-affecting event produces a transaction record.
- Transaction history can be queried per account with paging and date filters.

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
  apps, subscriptions, accounts) unless their role allows otherwise.
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

- Applications and subscriptions keyed to API version + tier.
- Tier defines allowed rates (requests/second or per hour, burst).
- Subscription statuses: `PENDING`, `ACTIVE`, `DENIED`, `REVOKED`.
- Credentials are bound to an application, not a user.

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