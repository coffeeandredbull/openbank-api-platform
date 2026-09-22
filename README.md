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
   3. Subscription Service
   4. Account Service
   5. Payment Service
   6. Transaction Service
   7. Analytics Service
```

All client traffic enters through the API Gateway. The Developer Portal is the
user-facing React application that developers use to discover APIs, subscribe
to them, and manage credentials.

> **Status note:** The Identity Service (registration, login, JWT issuance,
> bearer authentication, RBAC) and the API Management Service (API catalog
> foundation, API versioning, API version lifecycle, developer application,
> subscription and credential management) are implemented. The remaining
> services, the gateway, and the portal are planned. See the
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
- **Planned phases (subject to change):** API Gateway client-credential
  authentication (using these credentials), subscription tiers / rate limits,
  credential rotation/revocation and status, the remaining services, the
  Developer Portal, shared infrastructure (PostgreSQL/Redis via Docker
  Compose), CI/CD (GitHub Actions) and Kubernetes manifests will be built in
  small, explicitly requested phases and verified (compile + tests) at each
  step.

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
  Gateway will later authenticate applications with the issued credentials.
- **Account Service:** customer bank accounts and balances.
- **Payment Service:** payment initiation and approval workflows.
- **Transaction Service:** transaction history for accounts.
- **Analytics Service:** aggregated request usage and performance metrics
  published from gateway/service activity.
- **API Gateway:** central entry point — routing, JWT validation, rate
  limiting, subscription enforcement, request observability.
- **Developer Portal:** React/TypeScript UI to browse APIs, register, create
  applications, subscribe, and view usage analytics.
- **Shared infrastructure:** PostgreSQL as system of record; Redis for caching,
  token storage, and rate-limit counters.
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
```