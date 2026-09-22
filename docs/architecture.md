# Architecture

> This document describes the **planned** architecture of the OpenBank API
> Platform. Nothing described here is implemented yet. It is the design target
> that later phases build toward, one small step at a time.

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
| **API Management Service** | Owns the API catalog: published APIs, versions, endpoint metadata, documentation, and lifecycle state (published / deprecated / retired). It is the source of truth for "which API versions exist". Also owns the **developer application registry** (Phase 11), the **subscription registry** (Phase 12), and **application credentials** (Phase 13): for each owned application it issues a `clientId` + `clientSecret` (BCrypt-hashed at rest) that a future gateway authenticates. Subscription **tiers** and **rate-limit** enforcement remain future work on top of this registry. Ownership of everything is enforced from JWT claims — the service never trusts a client-supplied owner. |
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
- **No message broker** (Kafka / RabbitMQ) is used. Any asynchronous need will
  be addressed with Redis or direct calls unless explicitly requested
  otherwise.
- **No service discovery** is planned; service locations are resolved via
  configuration (e.g. `SPRING_*` environment variables or Compose service
  names) unless a concrete need emerges.

## API Gateway Responsibilities

- **Single entry point**: all requests — from the Developer Portal and from
  external API consumers — enter through the gateway.
- **Authentication**: validates JWT access tokens presented by callers before
  routing is allowed.
- **Authorization**: enforces that the caller is permitted to reach the target
  API/route (role, scope, or subscription check).
- **Subscription enforcement**: verifies the calling application's
  subscription against the target API version.
- **Rate limiting**: applies per-application (and per-tier) request limits,
  backed by Redis counters; returns `429 Too Many Requests` on exceed.
- **Routing**: routes requests to the correct backend service based on the
  requested API version and path.
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

- **Cache**: short-lived caching to reduce load on services (e.g. cached API
  catalog lookups, session/token material).
- **Token storage**: server-side storage for issued refresh tokens / blacklisted
  JWTs (revocation support).
- **Rate-limit counters**: fast, low-latency counters for the gateway's rate
  limiting before requests reach business services.
- **Not a system of record.** Redis must always be reconstructible and is never
  the source of truth for durable data.

## Planned Request Flow

1. A consumer (browser or API caller) sends a request to the Developer Portal
   or directly to the API Gateway with an authorization credential.
2. The **API Gateway** validates the JWT access token (signature, expiry,
   issuer/audience).
3. The gateway looks up the requested API version and checks the caller's
   **subscription** and tier with the Subscription Service (or a cached copy).
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