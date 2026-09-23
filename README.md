# OpenBank API Platform

## Project Overview

OpenBank API Platform is an **educational enterprise API-management platform**
inspired by the concepts found in enterprise API-management products such as
WSO2 API Manager. It is **NOT a clone** of any product. It is an original,
from-scratch implementation built to demonstrate how such platforms work, so
that every architectural decision can be explained and defended in a technical
interview.

The platform demonstrates a broad set of enterprise concerns working together,
including API management, API gateways, authentication, authorization, JWT and
OAuth2 concepts, API subscriptions, API versioning, rate limiting, Redis,
PostgreSQL, microservices, Docker, CI/CD, Kubernetes, and API analytics.

## Purpose

- Provide a working, understandable example of an end-to-end API management
  platform.
- Show how API providers expose, secure, version, meter, and analyze their APIs
  through a developer portal and an API gateway.
- Demonstrate authentication (JWT / OAuth2 concepts) and fine-grained
  authorization (RBAC, subscriptions, scopes).
- Illustrate microservice architecture with shared infrastructure
  (PostgreSQL, Redis) and the trade-offs that come with it.
- Serve as an interview-ready project where every design choice has an explicit
  rationale.

## High-Level Architecture

```
Developer Portal (frontend, React + TypeScript)
              |
              v
        API Gateway (Spring Cloud Gateway)
        - request routing, JWT validation,
          rate limiting, subscription enforcement
              |
              v
  ----------------------------- shared infra -----------------------------
  PostgreSQL (system of record)     Redis (caching, tokens, rate limits)
  ----------------------------- -----------------------------------------
              |
   Backend microservices (Spring Boot)
   1. Identity Service
   2. API Management Service
   3. Payment Service (Account + Payment + Transaction domains)
   4. Analytics Service
```

All client traffic enters through the API Gateway. The Developer Portal is the
user-facing React application that developers use to discover APIs, subscribe
to them, and manage credentials.

> **Status note:** The Identity Service (registration, login, JWT issuance,
> bearer authentication, RBAC), the API Management Service (API catalog
> foundation, API versioning, API version lifecycle, developer application,
> subscription and credential management), the Payment Service foundation
> (Account, Payment, and Transaction domains), the API Gateway (Phase 15 —
> routing, upstream failure handling, health; Phase 16 — **JWT authentication
> at the gateway**; Phase 17 — **subscription enforcement for JWT-authenticated
> managed API invocations**; Phase 20 — runtime upstream resolution hardening;
> Phase 21 — **client-credential authentication, application-scoped
> subscription enforcement, and Redis-backed application rate limiting**;
> Phase 22 — **trusted identity headers supplied to managed APIs from verified
> gateway authentication**), and
> **shared Redis infrastructure (Phase 18 — connectivity + health monitoring,
> now with real rate-limit counters since Phase 21)** are implemented. The
> remaining services and the portal are planned. See the
> [architecture document](docs/architecture.md) for details and each file in
> [`docs/`](docs/) for requirements, database design, security model, and API
> design.

## Technology Stack

| Layer | Technology |
| --- | --- |
| Backend | Java 21, Spring Boot, Spring Security, Spring Data JPA |
| Data | PostgreSQL, Redis |
| Gateway | Spring Cloud Gateway |
| Frontend | React, TypeScript |
| Testing | JUnit, Mockito, Spring Boot Test, Testcontainers |
| Infrastructure | Docker, Docker Compose, GitHub Actions, Kubernetes |
| API documentation | OpenAPI |

These choices are fixed per `AGENTS.md` and are not changed without asking.
Kafka, RabbitMQ, Elasticsearch, GraphQL, gRPC, service discovery, cloud
infrastructure, and AI features are explicitly out of scope unless requested.

## Current Development Status

- **Phase 1 — Project documentation:** repo initialized, `AGENTS.md` and
  documentation created.
- **Phases 2–7 — Identity Service:** user registration, BCrypt password
  hashing, login, HS256 JWT access-token issuance, stateless bearer request
  authentication, and role-based authorization (`ADMIN`/`DEVELOPER`) with a
  complete unit + integration test suite.
- **Phase 8 — API Management Service (API catalog foundation):** the service
  validates identity-service-issued JWTs locally, allows an `ADMIN` to register
  an API, and lets `ADMIN`/`DEVELOPER` browse the catalog.
- **Phase 9 — API Management Service (API versioning):** multiple versions per
  logical API over a many-to-one `ApiVersion` entity. `ADMIN`/`DEVELOPER` can
  create versions (`POST /apis/{apiId}/versions`), list them
  (`GET /apis/{apiId}/versions`), and fetch one
  (`GET /apis/{apiId}/versions/{versionId}`). Version strings are unique per API
  (duplicates → `409`), and reading a version through the wrong API returns
  `404`. No lifecycle, subscriptions, credentials, or gateway routing yet.
- **Phase 10 — API Management Service (API version lifecycle):** lifecycle
  management for API versions. Every version carries a lifecycle state
  (`CREATED → PUBLISHED → DEPRECATED → RETIRED`) persisted as a string and
  starting at `CREATED` (the caller cannot choose the initial state). An
  ADMIN-only endpoint `PATCH /apis/{apiId}/versions/{versionId}/lifecycle`
  moves a version along the allowed transitions; any other transition — including
  re-applying the current state — returns `409 INVALID_LIFECYCLE_TRANSITION`.
  Ownership rules carry over from Phase 9 (cross-API reads/updates → `404`
  without leaking existence). Lifecycle is metadata-only for now; it does not yet
  control gateway routing, subscriptions, or deprecation/retirement enforcement.
- **Phase 11 — API Management Service (developer applications):** developers
  and admins can manage their applications with `POST /applications`,
  `GET /applications`, `GET /applications/{applicationId}`, and
  `PATCH /applications/{applicationId}`. Ownership is enforced from the JWT
  `sub` claim (`ownerUserId`), never from the request body; the service keeps no
  user rows (logical cross-service reference to the Identity Service). All
  reads/updates are owner-scoped — accessing another user's application returns
  `404 APPLICATION_NOT_FOUND` with no existence leak. Names are not unique.
  `ADMIN` owns what it creates and has no global access. No credentials,
  subscriptions, or deletion yet (planned).
- **Phase 12 — API Management Service (subscriptions):** applications now link
  to API versions. `POST /subscriptions` subscribes an owned application to an
  API version, `GET /subscriptions/{subscriptionId}` and `GET /subscriptions`
  read them back. The owner is always derived from the JWT `sub` claim — the
  request body only carries `applicationId` / `apiVersionId` and the service
  verifies that the application belongs to the caller; `ADMIN` owns what it
  creates and has no global access (cross-owner access → `404`, no existence
  leak). A subscription is unique per (application, API version): duplicates
  return `409 SUBSCRIPTION_ALREADY_EXISTS`, enforced both in the service and by
  a database unique constraint. Each subscription has no lifecycle, credentials,
  rate limit, or gateway state (planned later) — it is the Application →
  Subscription → API Version link only.
- **Phase 13 — API Management Service (credentials):** applications now get
  credentials. `POST /credentials` generates a `clientId` (random UUID) and a
  cryptographically strong `clientSecret` server-side, hashes the secret with
  BCrypt, and stores **only the hash**. The plaintext secret is returned
  **exactly once**, in the `201 Created` response; `GET /credentials/{id}` and
  `GET /credentials` return only the `clientId` and never the secret or its
  hash. The owner is always derived from the JWT `sub` claim through the
  owning application (Credential → Application → ownerUserId); `ADMIN` owns
  what it creates and has no global access (cross-owner → `404
  CREDENTIAL_NOT_FOUND`, no existence leak). `clientId` is unique (service
  pre-check + DB unique constraint, retried on collision). The gateway will use
  these credentials later to authenticate an application — that enforcement is a
  later phase.
- **Phase 14 — Payment Service (Account, Payment, and Transaction
  foundations):** a new `payment-service` hosts three related financial domains.
  An **Account** belongs to exactly one user (`owner_user_id` unique — one
  account per user), has no balance, and defaults its currency to `LKR`.
  **Payments** are created against an owned account and always start as
  `PENDING`; the currency is taken from the account (never the client), and no
  real payment is processed. **Transactions** record payments: creating one
  requires an account owned by the caller and a payment that belongs to exactly
  that account, always records `PAYMENT` type and the account's currency, and
  never changes any account balance (there is none). Ownership is always derived
  from the JWT `sub` claim; the service keeps no user rows. All reads are
  owner-scoped (`404`, no existence leak), `ADMIN` owns what it creates with no
  global access, and the API never exposes balances, client secrets, or other
  users' data. No real payment processing, balances/wallets, refunds,
  settlement, or lifecycle endpoints yet (planned).
- **Phase 15 — API Gateway (routing foundations):** a new `gateway-service`
  (Spring Cloud Gateway, port `8080`) becomes the single entry point. It
  routes nine path prefixes to the backend services without rewriting the
  URI: `/users/**` and `/auth/**` → Identity, `/apis/**`, `/applications/**`,
  `/subscriptions/**`, and `/credentials/**` → API Management, and
  `/accounts/**`, `/payments/**`, and `/transactions/**` → Payment. Upstream
  base URLs come from the `IDENTITY_SERVICE_URL`, `API_MANAGEMENT_SERVICE_URL`,
  and `PAYMENT_SERVICE_URL` environment variables (development defaults:
  Identity `http://localhost:8081`, API Management `http://localhost:8081`,
  Payment `http://localhost:8082`). Method, path, query, body, and headers are
  forwarded untouched; backend 4xx/5xx responses pass through unchanged. When
  an upstream is unreachable or times out (2 s connect, 5 s response), the
  gateway returns `503` with code `UPSTREAM_SERVICE_UNAVAILABLE` and a generic
  message — never the upstream host, port, URL, or stack trace. Each routed
  request logs method, path, route id, status, and duration but never the
  `Authorization` header or any body. `GET /actuator/health` is the only
  exposed actuator endpoint. **No authentication happens at the gateway in
  this phase** — backend JWT enforcement is unchanged. Development quirk: the
  gateway's Identity default URL (`http://localhost:8081`) collides with API
  Management's default port (`8081`), while the Identity Service's own default
  port is `8080`; running all three locally therefore requires overriding one
  of them (for example `IDENTITY_SERVICE_URL=http://localhost:8080`).
- **Phase 16 — API Gateway (JWT authentication):** the gateway now
  authenticates every request itself using the same HS256 JWT convention the
  backend services already issue and validate: the `JWT_SECRET` environment
  variable (identical to the backends' `app.jwt.secret`, minimum 256 bits) plus
  the Nimbus JOSE library. Only `POST /users`, `POST /auth/login`, and
  `GET /actuator/health` are public; every other routed path (`/users*`,
  `/auth*`, `/apis/**`, `/applications/**`, `/subscriptions/**`,
  `/credentials/**`, `/accounts/**`, `/payments/**`, `/transactions/**`)
  requires a valid `Authorization: Bearer <JWT>` with a signed, unexpired token
  carrying `sub` and `role` claims (ADMIN or DEVELOPER). Missing, unparseable,
  tampered, expired, or wrong-claim tokens are rejected with a generic
  `401` + code `UNAUTHENTICATED` (`message: "Authentication is required"`) —
  the gateway never reveals which rule failed. Valid requests are forwarded
  with the `Authorization` header unchanged, and each backend service still
  validates the JWT itself (defense in depth). The gateway issues no tokens
  and performs no business authorization (no subscription or credential
  checks) — that remains planned. Gateway logs never contain the
  `Authorization` header or token material.
- **Phase 17 — API Gateway (subscription enforcement):** managed API
  invocations are now gated on an active subscription. A new route
  `/runtime/apis/**` forwards to the **managed API target**
  (`MANAGED_API_TARGET_URL`, default `http://localhost:8084`) with the full
  original path — this represents a *consumed* versioned API, distinct from the
  platform-management routes (`/apis/**`, `/applications/**`, etc.), which
  remain unencumbered and behave exactly as in Phases 15–16. Each
  `/runtime/apis/**` request must present a valid JWT (Phase 16 rule — missing
  or invalid tokens still get `401 UNAUTHENTICATED`); the gateway then derives
  the caller's user id **only** from the JWT `sub` claim and asks the API
  Management Service whether that user owns an application subscribed to the
  requested API version. The check goes through an authenticated internal
  endpoint `GET /internal/subscription-check?contextPath=...&version=...` that
  the gateway calls directly against the service (it is **not** reachable via
  any gateway route) and queries PostgreSQL directly — the system of record,
  with **no cache** (Redis remains out of scope here). A valid JWT without a
  subscription (including a subscription held by another user) is rejected
  with `403` + code `SUBSCRIPTION_REQUIRED`; an unreachable, timed-out, or
  failed check is rejected with `503` + code `SUBSCRIPTION_SERVICE_UNAVAILABLE`
  (fail closed — an unsubscribed request is never forwarded). The `path` is
  parsed as `/runtime/apis/{context}/{version}/...`; the check is
  lifecycle-agnostic (no `PUBLISHED` requirement) and delivers no admin
  bypass. All 401/403/503 rejection bodies are generic and never expose the
  token, upstream URLs, or check responses, and gateway logs still never
  contain the `Authorization` header or any body. Application
  **client-credential** enforcement (the Phase 13 credentials) and rate
  limiting remain planned.
- **Phase 18 — Shared Redis infrastructure (connectivity + health):** all four
  services (gateway, identity, api-management, payment) now include Redis as
  **plumbed infrastructure only** — **no caching, rate limiting, sessions,
  token revocation, or subscription caching uses it yet**, and PostgreSQL
  remains the sole system of record. Each service reads the Redis coordinates
  from the `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` environment variables
  (`spring.data.redis.*`, development defaults `localhost:6379` with no
  password). An empty/missing `REDIS_PASSWORD` deliberately never becomes a
  literal password (Spring Data Redis maps an empty value to *no password*, so
  no `AUTH` command is attempted; a config test asserts this). The **gateway's**
  `application.yml` declares **no `password:` key at all** (an existing test
  guards that the gateway configuration never contains secret-bearing keys) —
  if a Redis password is needed it is supplied purely through the environment.
  **Health is split into two views.** The default `GET /actuator/health` never
  depends on Redis — it reports `UP` whether or not Redis is reachable, so a
  Redis-less deployment stays releasable during this infrastructure-only phase.
  The dedicated `GET /actuator/health/redisHealth` group reports Redis
  `UP`/`DOWN` (with the native Redis health indicator) so Redis reachability is
  still observable per service. Spring Boot 3.5.16 **cannot exclude a
  contributor from the default health group by configuration** (the default
  group is always built with an include-all predicate), so each service
  registers a small `HealthEndpointGroups` bean built **only on public Actuator
  APIs** (`HealthEndpointGroups.of(...)`): the primary group filters out
  `redis`, and a named `redisHealth` group (`show-components: always`) exposes
  it. Config tests verify host/port binding, the no-password guard, default
  health `UP` without Redis, and `redisHealth` `DOWN` (→ `503`) when Redis is
  unreachable; Testcontainers connectivity tests run a real Redis
  (`redis:7-alpine`, `--requirepass`) and assert `PING`→`PONG`, `redisHealth`
  `UP`, and that **no health response ever contains the Redis password**.
  Payment allows `GET /actuator/health` (previously it denied every unlisted
  path), and the three backend services now expose the `health` actuator
  endpoint (previously only the gateway did). **Nothing in Phase 18 stores,
  caches, or rates anything in Redis.**
- **Phase 20 — API Gateway (runtime upstream resolution hardening):** the
  managed-API invocation route `/runtime/apis/**` keeps forwarding with the
  full original path, method, query, body, and headers unchanged, but the
  upstream target is now **validated at startup**. `MANAGED_API_TARGET_URL`
  (default `http://localhost:8084`) must resolve to an absolute `http(s)` URL
  with a host and must not contain credentials/userinfo, a fragment, or
  whitespace; an invalid value aborts gateway startup with a generic message
  that never echoes the configured value. The upstream is
  **configuration-only**: the gateway never derives a target host from query
  parameters, request headers, the `Authorization` header, or path input — it
  is not a dynamic API registry and never queries PostgreSQL — so it cannot be
  turned into an open proxy. An unreachable or timed-out managed target still
  returns `503` + code `UPSTREAM_SERVICE_UNAVAILABLE` with a fixed, leak-free
  message, and the authentication → subscription enforcement → rate limiting →
  forwarding order is unchanged.
- **Phase 21 — API Gateway (client credentials, application subscriptions &
  rate limiting):** managed API invocations (`/runtime/apis/**`) now accept
  **application client credentials** — `Authorization: Basic
  base64(clientId:clientSecret)` using the Phase 13 credentials — as an
  alternative to a user JWT. The gateway decodes the pair, derives the API
  version from the path (`/runtime/apis/{context}/{version}/...`), and calls the
  API Management Service's internal, self-authenticating endpoint
  `GET /internal/credential-check?contextPath={context}&version={version}` with
  the **same Basic header** (it is not reachable through any gateway route). The
  service looks the credential up by `clientId`, verifies the secret with
  BCrypt `matches` against the stored hash, and returns, from a **direct
  PostgreSQL query** (the system of record — no cache),
  `{"authenticated":true,"applicationId":N,"ownerUserId":N,"subscribed":bool}`
  (failures return `{"authenticated":false}`); the plaintext secret or its hash
  is never stored, returned, forwarded, or logged anywhere in the exchange.
  Outcomes: unknown/malformed credentials → `401` + code
  `CLIENT_CREDENTIAL_INVALID`; the check is unreachable, returns 5xx, or a
  malformed body → `503` + code `CREDENTIAL_SERVICE_UNAVAILABLE` (**fail
  closed** — an unverified request is never forwarded); valid credentials but
  the **authenticated application itself** is not subscribed to the target API
  version → `403` + code `SUBSCRIPTION_REQUIRED` (subscription is decided by
  the same single check — no second network call — and identity is the
  application, never a user); a malformed runtime path with Basic → `403`
  (mirroring the JWT flow). The Basic header is **stripped before forwarding**,
  so the consumed backend never sees the credentials. JWT (user) invocation is
  unchanged (Bearer forwarded intact, `Bearer` + `Basic` together → the
  client-credential flow wins), subscription enforcement and rate limiting take
  the same application identity, and platform-management routes still require a
  Bearer JWT (Basic there → `401` `UNAUTHENTICATED`). Both flows are now
  **rate limited via Redis** — fixed-window counters per API version, keyed
  `rate_limit:user:{userId}:{context}:{version}` (user flow) and
  `rate_limit:app:{applicationId}:{context}:{version}` (application flow, added
  here): the limit (`RATE_LIMIT_REQUESTS`, default `100`) is evaluated before
  routing, over-limit requests get `429` + code `RATE_LIMIT_EXCEEDED` with a
  `Retry-After` header, and a Redis failure returns `503` + code
  `RATE_LIMIT_SERVICE_UNAVAILABLE` (fail closed) — this is Redis's **first
  business use**. All new rejection bodies keep the stable
  `{timestamp,status,error,path,code,message,fieldErrors}` shape and never
  expose the credentials, hashes, upstream URLs, or internal responses.
- **Phase 22 — API Gateway (trusted identity headers):** after successful
  authentication of a `runtime` managed-API invocation (`/runtime/apis/**`),
  the gateway supplies the caller's **verified identity** to the consumed API
  as gateway-generated request headers. The JWT/user flow adds `X-User-Id`
  (from the `sub` claim) and `X-Roles` (from the `role` claim) while continuing
  to forward `Authorization: Bearer <jwt>` unchanged; the client-credential/
  application flow adds `X-User-Id` (the credential's `ownerUserId`),
  `X-Application-Id` (the credential's `applicationId`), and `X-Client-Id` (the
  credential's `clientId`) while continuing to strip the Basic header. These
  headers are **never trusted from the client**: a reusable
  `TrustedIdentityHeaderSanitizer` strips any client-supplied `X-User-Id`,
  `X-Roles`, `X-Application-Id`, or `X-Client-Id` values before trusted values
  are added, so a spoofed identity can never reach the managed API. The headers
  are applied **only** on `/runtime/apis/**` (platform-management routes receive
  none), the filter order is unchanged, and each backend still validates the
  JWT locally / re-checks ownership (defense in depth). Verified by focused
  unit tests and an end-to-end integration test against a real managed
  upstream.
- **Planned phases (subject to change):** subscription tiers, credential
  rotation/revocation and status, Redis-backed token revocation and caches,
  the remaining services, the Developer Portal, shared infrastructure
  (PostgreSQL/Redis via Docker Compose), CI/CD (GitHub Actions) and Kubernetes
  manifests will be built in small, explicitly requested phases and verified
  (compile + tests) at each step.

## Planned Features

- **Identity Service:** user registration, login, issuance and validation of
  JWT access tokens, OAuth2-style flows, role and scope management.
- **API Management Service:** registry of published APIs, API versions,
  endpoint metadata, documentation, and lifecycle state (e.g. published,
  deprecated).
- **Subscription Service:** (with the API Management Service) API subscription
  tiers, rate-limit enforcement, and gateway-side credential verification.
  Basic application, subscription, and application credential management is
  already implemented in the API Management Service (Phases 11–13). The API
  Gateway already authenticates applications with the issued client credentials
  and enforces the application's subscription and API rate limit (Phase 21).
- **Account / Payment / Transaction:** the `payment-service` already hosts the
  Account, Payment, and Transaction foundations (Phase 14) — one account per
  user, `PENDING` payment creation, and transaction records of type `PAYMENT`.
  Planned extensions: real payment processing, balances/wallets, refunds,
  settlement, and richer transaction history.
- **Analytics Service:** aggregated request usage and performance metrics
  published from gateway/service activity.
- **API Gateway:** central entry point — **routing is implemented (Phase
  15)**, **JWT authentication is implemented (Phase 16)**, **subscription
  enforcement for managed API invocations is implemented (Phases 17 and 21 —
  JWT and application client credentials)**, **Redis-backed rate limiting is
  implemented (per-user and per-application, Phase 21)**, **trusted identity
  headers supplied to managed APIs are implemented (Phase 22 — gateway-derived
  `X-User-Id`/`X-Roles` for users and `X-User-Id`/`X-Application-Id`/
  `X-Client-Id` for applications, runtime routes only, never trusted from the
  client)**, and **Redis
  connectivity is plumbed (Phase 18) and now used for rate-limit counters**;
  request observability for the Analytics Service remains planned.
- **Developer Portal:** React/TypeScript UI to browse APIs, register, create
  applications, subscribe, and view usage analytics.
- **Shared infrastructure:** PostgreSQL as system of record; Redis is **connected
  but not yet used for any business function** (Phase 18 — caching, token
  storage, and rate-limit counters remain planned).
- **CI/CD & orchestration:** Docker images, Docker Compose for local
  development, GitHub Actions pipelines, Kubernetes manifests.

## Design Principles

- Original implementation, not a WSO2 clone. Concepts are borrowed and
  reimplemented from scratch; code and design remain explainable.
- Thin controllers, business logic in services, DB access in repositories,
  DTOs at API boundaries, centralized error handling, validation of all
  external input, appropriate HTTP status codes.
- No placeholder functionality: no fake authentication, no fake analytics, no
  hardcoded fake security. Anything not implemented is documented as planned.

## Documentation

- [Architecture](docs/architecture.md) — services, gateway, portal, shared
  infrastructure, request flow.
- [Requirements](docs/requirements.md) — functional and non-functional
  requirements.
- [Database](docs/database.md) — planned entities, ownership, PostgreSQL
  strategy.
- [Security](docs/security.md) — authentication, JWT, RBAC, secrets, rate
  limiting, security boundaries.
- [API design](docs/api-design.md) — REST conventions, status codes, DTOs,
  errors, versioning.

## Repository Layout

```
README.md
AGENTS.md
docs/
├── architecture.md
├── requirements.md
├── database.md
├── security.md
└── api-design.md
identity-service/    (implemented — Phases 2–7)
api-management-service/  (implemented — Phases 8–13, API catalog + versioning + lifecycle + applications + subscriptions + credentials)
payment-service/     (implemented — Phase 14, Account + Payment + Transaction foundations)
gateway-service/     (implemented — Phases 15–22, routing + JWT/client-credential authentication + subscription enforcement + Redis rate limiting + trusted identity headers)
```