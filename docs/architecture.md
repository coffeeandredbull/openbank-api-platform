# Architecture

> This document describes the **planned** architecture of the OpenBank API
> Platform. It is the design target that later phases build toward, one small
> step at a time. Implemented parts (the Identity, API Management, and Payment
> services; the Phase 15–22 gateway: routing + JWT and client-credential
> authentication + subscription enforcement + Redis rate limiting + trusted
> identity headers; and the Phase 18 Redis infrastructure integration, now with
> real rate-limit counters since Phase 21)
> are marked in their sections; everything else remains planned.

## Overview

The platform is a microservice system with a single entry point (the API
Gateway) and a user-facing frontend (the Developer Portal). Four Spring Boot
backend services implement business capabilities. Two shared infrastructure
components — PostgreSQL and Redis — are used by those services. Note that
`AGENTS.md` describes six services; this document reflects the **evolved**
architecture: earlier plans separated Subscription, Account, Payment, and
Transaction into dedicated services, but the implementation deliberately
consolidates them to keep each service cohesive and each phase small and
verifiable:
- the API Management Service absorbed the developer application, subscription,
  and credential domains (Phases 11–13);
- the Payment Service hosts the Account, Payment, and Transaction domains
  (Phase 14), because they share one PostgreSQL database and money rules, and
  can be split later if a concrete engineering reason emerges.

Current services: Identity, API Management (incl. applications, subscriptions,
credentials), Payment (Account + Payment + Transaction domains), and Analytics.

```
                        +-----------------------+
                        |   Developer Portal     |
                        |   (React + TypeScript) |
                        +-----------+-----------+
                                    |
                                    | HTTPS (browser)
                                    v
                        +-----------------------+
                        |     API Gateway       |
                        |  Spring Cloud Gateway |
                        +-----------+-----------+
                                    |
                    +---------------+----------------+
                    |               |                |
                    v               v                v
            +---------------+   +---------------+   +---------------+
            | Identity      |   | API Mgmt      |   | Analytics     |
            | Service       |   | Service       |   | Service       |
            +-------+-------+   +-------+-------+   +-------^-------+
                    |               |                        |
                    |               +--------+               |
                    |                        |               |
                    v                        v               |
            +---------------+   +-----------------------+   |
            | PostgreSQL    |   | Payment Service       |   |
            | (system of    |   | (Account + Payment +  |---+
            |  record)      |   |  Transaction domains) |
            +---------------+   +-----------+-----------+
                                            |
                                            v
                                    +---------------+
                                    | Redis         |
                                    | (cache,       |
                                    |  tokens,      |
                                    |  rate limits) |
                                    +---------------+
```

## Service Responsibilities

| Service | Responsibility |
| --- | --- |
| **Identity Service** | Owns users, roles, and credentials. Handles registration, login, JWT access-token issuance and validation, OAuth2-style concepts (client registry, grant-type flows), and password hashing. The gateway consults it (directly or via pre-issued tokens) when validating tokens. |
| **API Management Service** | Owns the API catalog: published APIs, versions, endpoint metadata, documentation, and lifecycle state (published / deprecated / retired). It is the source of truth for "which API versions exist". Also owns the **developer application registry** (Phase 11), the **subscription registry** (Phase 12), and **application credentials** (Phase 13): for each owned application it issues a `clientId` + `clientSecret` (BCrypt-hashed at rest) that the gateway authenticates (Phase 21) and rate-limits against. Subscription **tiers** remain future work on top of this registry. Ownership of everything is enforced from JWT claims — the service never trusts a client-supplied owner. |
| **Payment Service** | Hosts the **Account**, **Payment**, and **Transaction** domains (Phase 14). An account belongs to exactly one user (no balance), a payment is created against an owned account and always starts `PENDING`, and a transaction records a payment for the caller's account. Ownership is always derived from the JWT `sub` claim; the service keeps no user rows. Real payment processing, balances, refunds, and settlement are future work. |
| **Analytics Service** | Collects and aggregates API usage and performance data (request counts, latency, status-code distribution) published by the gateway and services, and exposes queries for the Developer Portal. |

## Communication Between Services

- **All external traffic** enters through the API Gateway. Browser requests
  from the Developer Portal and direct API consumer calls both go through the
  gateway.
- **Between services**, services may call each other over HTTP using internal
  (gateway-routed or direct) calls. Today the Id/IAM→Payment relationship is
  logical only: the Payment Service validates identity-service-issued JWTs
  locally and references `ownerUserId` rather than holding user rows. A future
  Payment → Account or Payment → Transaction network call is deliberately not
  needed today because those domains share one service (and one database).
- **Gateway → API Management (Phases 17 and 21):** the gateway calls the API
  Management Service's internal endpoints directly (never through any gateway
  route) to verify a caller against PostgreSQL:
  `GET /internal/subscription-check` (Phase 17, JWT callers) and
  `GET /internal/credential-check` (Phase 21, application client credentials;
  returns credential + subscription verdict in one call). These are the only
  internal service-to-service calls today.
- **No message broker** (Kafka / RabbitMQ) is used. Any asynchronous need will
  be addressed with Redis or direct calls unless explicitly requested
  otherwise.
- **No service discovery** is planned; service locations are resolved via
  configuration (e.g. `SPRING_*` environment variables or Compose service
  names) unless a concrete need emerges.

## API Gateway Responsibilities

**Implemented (Phases 15–22).** The `gateway-service`
(Spring Cloud Gateway) listens on port `8080` and performs:

- **Single entry point**: all requests — from the Developer Portal and from
  external API consumers — enter through the gateway.
- **Routing**: routes requests to the correct backend service based on path,
  without rewriting the URI. Method, path, query, body, and headers are
  forwarded untouched. Upstream base URLs are configured per environment with
  the `IDENTITY_SERVICE_URL`, `API_MANAGEMENT_SERVICE_URL`,
  `PAYMENT_SERVICE_URL`, and `MANAGED_API_TARGET_URL` variables:
  - `/users/**`, `/auth/**` → Identity
  - `/apis/**`, `/applications/**`, `/subscriptions/**`, `/credentials/**` →
    API Management
  - `/accounts/**`, `/payments/**`, `/transactions/**` → Payment
  - `/runtime/apis/**` → managed API target (`MANAGED_API_TARGET_URL`, default
    `http://localhost:8084`) — Phase 17. This route represents *consumed*
    versioned APIs (an API consumer invoking a published API through the
    gateway) and is where subscription enforcement applies. Platform-management
    routes are **not** gated by subscriptions.
  - **Runtime upstream resolution (Phase 20)** is configuration-only and
    validated at startup: `MANAGED_API_TARGET_URL` must resolve to an absolute
    `http(s)` URL with a host and must not contain credentials/userinfo, a
    fragment, or whitespace. Malformed values abort gateway startup with a
    generic message that never echoes the configured value. The gateway never
    accepts an upstream host from query parameters, request headers, the
    `Authorization` header, or path input — it is not a dynamic API registry
    and never queries PostgreSQL. Failure behavior is unchanged: an
    unreachable or timed-out managed target returns `503` + code
    `UPSTREAM_SERVICE_UNAVAILABLE` with a fixed message.
- **Authentication (Phase 16)**: a global JWT filter validates the
  `Authorization: Bearer <jwt>` header before routing. Only `POST /users`,
  `POST /auth/login`, and `GET /actuator/health` are public; all other routed
  paths require a signed (HS256, shared `JWT_SECRET`), unexpired token with
  `sub` and `role` (ADMIN or DEVELOPER) claims. Failures return `401` + code
  `UNAUTHENTICATED` with a generic message and never reveal which check failed.
  Valid requests keep their `Authorization` header unchanged. The gateway
  issues no tokens. Each backend service still validates the JWT itself
  (defense in depth).
- **Subscription enforcement (Phase 17)**: after authentication, requests to
  `/runtime/apis/**` are additionally gated on an **active subscription**. The
  gateway derives the caller's user id **only** from the JWT `sub` claim (no
  client-supplied user id is ever trusted), identifies the requested API
  version from the path (`/runtime/apis/{context}/{version}/...`), and calls
  the API Management Service's internal
  `GET /internal/subscription-check?contextPath=...&version=...` endpoint with
  the same `Authorization` header. That endpoint is authenticated, is **not**
  reachable through any gateway route, and returns only `{"subscribed": bool}`
  from a **direct PostgreSQL query** (the system of record — no Redis, no
  cache). Outcomes:
  - subscribed → request is forwarded unchanged to the managed API target;
  - valid JWT but not subscribed (including a subscription owned by another
    user) → `403` + code `SUBSCRIPTION_REQUIRED`;
  - the check itself fails (unreachable, timeout, 5xx, malformed) → `503` +
    code `SUBSCRIPTION_SERVICE_UNAVAILABLE`. The gateway **fails closed**: an
    unverified or unsubscribed request is never forwarded.
  The check is **intentionally lifecycle-agnostic**: a subscription to the target
  API version satisfies the gate in any lifecycle state (`CREATED`, `PUBLISHED`,
  `DEPRECATED`, `RETIRED`). Lifecycle-based runtime blocking (e.g. only
  `PUBLISHED` versions invocable, deprecation/retirement sunset handling) is out
  of scope for Phase 17 and remains a later phase. There is no `ADMIN` bypass,
  and identity is never taken from query parameters or headers.
- **Client-credential authentication (Phase 21)**: managed API invocations
  (`/runtime/apis/**`) can be authenticated as an **application** with
  `Authorization: Basic base64(clientId:clientSecret)` (the Phase 13
  credentials) instead of a user JWT. The gateway:
  - derives the API version from the path (`/runtime/apis/{context}/{version}/...`)
    and calls the API Management Service's internal, self-authenticating
    `GET /internal/credential-check?contextPath=...&version=...` with the same
    Basic header (not routable through the gateway; `permitAll` in the service
    by design — the Basic header is the credential, not a bearer token);
  - treats the response `{"authenticated":bool,"applicationId":N,
    "ownerUserId":N,"subscribed":bool}` as both the **credential verdict** and
    the **subscription verdict** for the authenticated application (identity =
    application, never a user), decided against PostgreSQL directly (no cache);
  - **strips the Basic header** (decorated request) before forwarding, so the
    consumed backend never sees the client secret;
  - fails closed: invalid/malformed credentials → `401 CLIENT_CREDENTIAL_INVALID`;
    check unreachable/failed/malformed → `503 CREDENTIAL_SERVICE_UNAVAILABLE`;
    valid credentials but unsubscribed application (or malformed runtime path)
    → `403 SUBSCRIPTION_REQUIRED`. Management routes remain Bearer-only (Basic
    on `/apis/**` → `401 UNAUTHENTICATED`); a request with both Bearer and
    Basic uses the client-credential flow.
- **Trusted identity headers (Phase 22)**: for managed API invocations
  (`/runtime/apis/**`) the gateway supplies the caller's **verified identity**
  to the consumed API as gateway-generated request headers, added after
  successful authentication and before forwarding:
  - **JWT (user) flow** → `X-User-Id` (the JWT `sub` user id) and `X-Roles` (the
    JWT `role`, e.g. `ADMIN`/`DEVELOPER`); `Authorization: Bearer <jwt>` is
    still forwarded unchanged.
  - **Client-credential (application) flow** → `X-User-Id` (the credential's
    `ownerUserId`), `X-Application-Id` (the credential's `applicationId`), and
    `X-Client-Id` (the credential's `clientId`); no `X-Roles` is set, and the
    `Authorization: Basic ...` header remains stripped before forwarding.
  - The values are always **gateway-derived after authentication** (from JWT
    claims or the credential-check response) and are **never trusted from the
    client**: a reusable `TrustedIdentityHeaderSanitizer` strips any
    client-supplied `X-User-Id`, `X-Roles`, `X-Application-Id`, or `X-Client-Id`
    values from the inbound request before trusted values are added, so a
    spoofed identity can never reach the consumed API or influence it.
  - **Runtime path only**: the headers are added exclusively on
    `/runtime/apis/**`; platform-management routes (`/apis/**`,
    `/applications/**`, ...) receive none of them. Filter order is unchanged
    (`-210` client credentials → `-200` JWT → `-150` subscription → `-120` rate
    limit → `-100` global filter). Backend services still treat these headers
    as enriched context and continue to validate the JWT locally / re-check
    ownership (defense in depth).
- **Rate limiting (Phase 21 — Redis-backed)**: every `/runtime/apis/**` request
  is rate limited before routing with Redis fixed-window counters per API
  version — `rate_limit:user:{userId}:{context}:{version}` for JWT callers,
  `rate_limit:app:{applicationId}:{context}:{version}` for client-credential
  callers — configured by `RATE_LIMIT_REQUESTS` (default `100`) /
  `RATE_LIMIT_WINDOW_SECONDS` (default `60`). Exceeded → `429` + code
  `RATE_LIMIT_EXCEEDED` with `Retry-After`; Redis/counter failure → `503` + code
  `RATE_LIMIT_SERVICE_UNAVAILABLE` (fail closed). This is the first business
  use of the Phase 18 Redis infrastructure.
- **Upstream failure handling**: when an upstream is unreachable or exceeds the
  connect (2 s) or response (5 s) timeout, the gateway returns `503` with code
  `UPSTREAM_SERVICE_UNAVAILABLE` and a generic message — never the upstream
  host, port, URL, or stack trace. Backend application errors (4xx/5xx) pass
  through unchanged.
- **Request diagnostics**: each routed request logs method, path, route id,
  status, and duration. Login passwords and credentials are never touched or
  logged: the gateway never logs the `Authorization` header, JWT/token
  material, cookies, or any request/response body.
- **Health**: `GET /actuator/health` is the only exposed actuator endpoint.
  Since Phase 18 it reports `UP` **independently of Redis** (see "Redis Role"
  below); `GET /actuator/health/redisHealth` is the dedicated Redis view.
- The Phase 17–21 gateway performs **no CORS handling** and no business logic;
  tiered/policy-based rate limiting and observability remain planned (below).

**Planned (later phases):**

- **Subscription tiers / policy-based rate limiting**: per-subscription or
  per-tier limits on top of the current fixed per-API-version counters.
- **Observability**: emits request telemetry for the Analytics Service.
- The gateway stays thin about business logic; it routes and enforces, but does
  not implement account, payment, or transaction rules.

## Developer Portal Responsibilities

- **API discovery**: browse the API catalog, versions, and documentation.
- **Identity flows**: user registration, login, logout via the Identity
  Service (through the gateway).
- **Application management**: create developer applications and obtain
  credentials.
- **Subscription management**: subscribe an application to an API version with
  a chosen tier; view subscription status.
- **Usage analytics**: visualize request usage and performance data from the
  Analytics Service.
- It is a React + TypeScript SPA. It never stores secrets; credentials are
  displayed once and/or stored server-side by the platform.

## PostgreSQL Role

- **System of record.** Holds persistent business data: users/roles, API
  catalog and versions, applications, subscriptions, credentials, accounts,
  payments, and transactions (no balances yet — accounts have none).
- One shared PostgreSQL instance is planned (as in the architecture goal),
  with each service owning its own schema/tables (data ownership, see
  `database.md`).
- ACID transactions preserve consistency for money-adjacent operations
  (payments, balances, ledger entries).
- Repositories access it through Spring Data JPA.

## Redis Role

**Implemented (Phase 18 — infrastructure, plumbed and health-monitored; Phase
21 — first business use: gateway rate-limit counters).** All four services
(gateway, identity, api-management, payment) depend on Redis as *plumbed
infrastructure*: a Lettuce connection is configured and health-monitored (Phase
18), and since Phase 21 the **gateway stores fixed-window rate-limit counters in
Redis** for managed API invocations. Caching, sessions, token storage, and
subscription caching remain unused. PostgreSQL remains the **only** system of
record.

- **Configuration**: each service binds `spring.data.redis.host` / `port` /
  `password` from the `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD`
  environment variables (development defaults `localhost:6379`, no password).
  An empty or missing `REDIS_PASSWORD` never becomes a literal password —
  Spring Data Redis maps an empty value to *no password* (no `AUTH` command is
  attempted; a config test asserts the connection config carries no password).
  The gateway's `application.yml` declares **no `password:` key at all** (a
  test guards that gateway configuration never contains secret-bearing keys);
  a production Redis password is supplied purely via the environment.
- **Health split** (the platform's liveness must not depend on Redis while
  Redis is not yet used by any function, yet Redis reachability must be
  observable):
  - the default `GET /actuator/health` **excludes** Redis — it stays `UP`
    whether or not Redis is reachable (only the gateway and PostgreSQL drive
    the default aggregate);
  - the dedicated group `GET /actuator/health/redisHealth` reports Redis
    `UP`/`DOWN` (`503` when down) using the **native Spring Boot Redis health
    indicator**, with `show-components: always` so the `redis` component
    status is visible and `show-details: never` so nothing beyond status is
    exposed. The three backend services now expose the `health` actuator
    endpoint (previously only the gateway did); `payment-service` permits
    `GET /actuator/health`/`/actuator/health/**` (it otherwise denies all
    unlisted paths).
  - **Why a small `HealthEndpointGroups` bean:** Spring Boot 3.5.16 builds the
    default health group with an include-all predicate, so a contributor
    **cannot be excluded from `/actuator/health` by properties**
    (`management.endpoint.health.group.default.*` is never consulted by the
    default endpoint). Each service therefore registers a `HealthEndpointGroups`
    bean built on public Actuator APIs (`HealthEndpointGroups.of(primary,
    namedGroups)`) whose primary group filters out the `redis` contributor and
    a named `redisHealth` group re-includes it. This keeps the Redis health
    indicator fully native and the URLs stable.
- **Planned (later phases, currently unused):**
  - **Cache**: short-lived caching to reduce load on services (e.g. cached API
    catalog lookups, session/token material, the Phase 17 subscription check or
    the Phase 21 credential check).
  - **Token storage**: server-side storage for issued refresh tokens /
    blacklisted JWTs (revocation support).
  - **Not a system of record.** Redis must always be reconstructible and is
    never the source of truth for durable data.

## Planned Request Flow

> Phase 22 status: the gateway already performs steps 1, 2 (authentication —
> JWT validation for user Bearer calls, client-credential verification for
> application Basic calls), 3 (subscription — JWT flow via
> `/internal/subscription-check`, application flow via the same
> `/internal/credential-check` call), 4 (Redis-backed rate limiting), and 5 —
> which since Phase 22 also passes the caller's verified identity to managed
> APIs as gateway-generated `X-User-Id`/`X-Roles` (user flow) and
> `X-User-Id`/`X-Application-Id`/`X-Client-Id` (application flow) headers — 6,
> and 7 (minus the telemetry). Tiering and caching for the subscription/credential
> checks and tier-based rate limiting remain planned.

1. A consumer (browser or API caller) sends a request to the Developer Portal
   or directly to the API Gateway with an authorization credential.
2. The **API Gateway** validates the JWT access token (signature, expiry,
   issuer/audience).
3. The gateway looks up the requested API version and checks the caller's
   **subscription** (Phase 17 — implemented via the API Management Service's
   internal check against PostgreSQL) and tier (planned) with the Subscription
   Service (or a cached copy — caching planned).
4. The gateway applies **rate limiting** for the application, incrementing a
   Redis counter; on exceed it responds `429`.
5. The gateway **routes** the request to the owning backend service
   (e.g. the Payment Service's Transaction domain for `GET /transactions`),
   passing through the validated identity and application context.
6. The backend service executes business logic (database reads/writes against
   PostgreSQL), using Redis for caching where applicable.
7. The gateway returns the response and emits **telemetry** for analytics.

## Guiding Trade-offs

- Monolith simplicity is knowingly traded for microservice separation in order
  to demonstrate distributed-system concepts; the cost (network calls, data
  ownership splits) is explicit.
- A single gateway is a central bottleneck/attack surface by design, but it is
  also the single enforcement point, which keeps security rules explainable.
- Shared PostgreSQL (rather than per-service databases) keeps local development
  simple; ownership boundaries still keep services decoupled at the logical
  level.