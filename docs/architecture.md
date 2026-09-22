# Architecture

> This document describes the **planned** architecture of the OpenBank API
> Platform. Nothing described here is implemented yet. It is the design target
> that later phases build toward, one small step at a time.

## Overview

The platform is a microservice system with a single entry point (the API
Gateway) and a user-facing frontend (the Developer Portal). Seven Spring Boot
backend services implement business capabilities. Two shared infrastructure
components — PostgreSQL and Redis — are used by those services. Note that
`AGENTS.md` describes six services; this document reflects the **corrected**
seven-service architecture: Identity, API Management, **Subscription**, Account,
Payment, Transaction, and Analytics.

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
            | Identity      |   | API Mgmt      |   | Subscription  |
            | Service       |   | Service       |   | Service       |
            +-------+-------+   +-------+-------+   +-------+-------+
                    |               |                |
                    v               v                v
            +---------------+   +---------------+   +---------------+
            | Account       |   | Payment       |   | Transaction   |
            | Service       |   | Service       |   | Service       |
            +-------+-------+   +-------+-------+   +-------+-------+
                    |               |                |
                    +-------+-------+----------------+
                            |
                            v
                    +-------|-----------+   +-------|-------+
                    |  PostgreSQL       |   |  Redis        |
                    |  (system of       |   |  (cache,      |
                    |   record)         |   |   tokens,     |
                    |                   |   |   rate limits)|
                    +-------------------+   +--------------+
```

## Service Responsibilities

| Service | Responsibility |
| --- | --- |
| **Identity Service** | Owns users, roles, and credentials. Handles registration, login, JWT access-token issuance and validation, OAuth2-style concepts (client registry, grant-type flows), and password hashing. The gateway consults it (directly or via pre-issued tokens) when validating tokens. |
| **API Management Service** | Owns the API catalog: published APIs, versions, endpoint metadata, documentation, and lifecycle state (published / deprecated / retired). It is the source of truth for "which API versions exist". Also owns the **developer application registry** (Phase 11): who owns which application, with ownership enforced from JWT claims. |
| **Subscription Service** | Owns API subscriptions and credentials. Links a developer application (owned by the API Management Service) to an API version under a rate-limit tier and provisions credentials (API key / client ID + secret). Enforces whether an application is actually allowed to call an API. |
| **Account Service** | Owns bank account entities and balances. Authorizes balance reads and updates. Payment and Transaction services consult it for balance effects. |
| **Payment Service** | Initiates and processes payments. Validates payment instructions against accounts, applies business rules, and records payment outcomes. |
| **Transaction Service** | Owns the ledger of transactions. Records and queries transaction history for accounts, including the entries produced by payments. |
| **Analytics Service** | Collects and aggregates API usage and performance data (request counts, latency, status-code distribution) published by the gateway and services, and exposes queries for the Developer Portal. |

## Communication Between Services

- **All external traffic** enters through the API Gateway. Browser requests
  from the Developer Portal and direct API consumer calls both go through the
  gateway.
- **Between services**, services may call each other over HTTP using internal
  (gateway-routed or direct) calls, e.g. Payment → Account (validate/apply
  balance) and Payment → Transaction (record ledger entries).
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
  catalog and versions, applications, subscriptions, accounts, balances,
  payments, and transactions.
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
   (e.g. Transaction Service for `GET /accounts/{id}/transactions`), passing
   through the validated identity and application context.
6. The backend service executes business logic (database reads/writes against
   PostgreSQL), possibly calling other services (e.g. Payment → Account,
   Payment → Transaction) and using Redis for caching.
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