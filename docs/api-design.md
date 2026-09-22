# API Design (Planned)

> This document describes the **planned** REST API conventions for the OpenBank
> API Platform. No endpoints are implemented yet. Consistent conventions apply
> to the Developer Portal-facing APIs and the consumer-facing APIs routed by the
> gateway. OpenAPI documents for each service will be produced in the relevant
> service phases.

## REST Conventions

- **Resource-oriented REST.** Nouns for resources, HTTP methods for actions;
  RPC-style verbs are avoided.
- Resources are addressed under a **versioned base path**, e.g.
  `/api/v1/accounts/{id}`.
- Naming: lowercase, plural nouns; path segments separated by `/`; identifiers
  use UUIDs.
- Query parameters for filtering, sorting, and paging — never verbs in paths.
- HATEOAS not required; a consistent JSON shape is required.

## HTTP Methods

| Method | Semantics |
| --- | --- |
| `GET` | Read resources; no side effects. |
| `POST` | Create a resource (or a custom, clearly documented operation such as `payments/initiate`). |
| `PUT` | Replace a resource in full. |
| `PATCH` | Partial update (where a crafty partial update is warranted; otherwise `PUT`). |
| `DELETE` | Remove/discontinue a resource (with explicit side-effect documentation, e.g. revoke subscription). |

## Status Codes

- `200 OK` — success with body (list, single resource, aggregate).
- `201 Created` — resource created; `Location` header optional.
- `204 No Content` — success, no body (e.g. delete).
- `400 Bad Request` — invalid/malformed payload or query; validation errors.
- `401 Unauthorized` — missing/invalid/expired authentication credentials.
- `403 Forbidden` — authenticated but not allowed (role, scope, or no active
  subscription).
- `404 Not Found` — resource does not exist or is not visible to the caller
  (for security, ownership failures on reads may also map here).
- `409 Conflict` — state conflict (e.g. duplicate name, conflicting status
  transition).
- `422 Unprocessable Entity` — valid syntax but semantically invalid for the
  business rules (e.g. insufficient funds) — optional; final choice in phases.
- `429 Too Many Requests` — rate limit exceeded; include `Retry-After`.
- `500 Internal Server Error` — unexpected server error; generic body, no
  stack traces leaked.

## DTO Strategy

- **DTOs at every API boundary**: request and response objects are DTOs; domain
  entities are never serialized directly.
- Request DTOs are validated at the boundary (Bean Validation on fields; service
  layer validates business rules).
- Response DTOs are stable, versioned contracts; adding fields is backward
  compatible, removing/renaming fields is a breaking change gated by API
  versioning.
- Responses wrap collections with paging metadata (e.g.
  `{ "items": [...], "page": 1, "size": 20, "total": 137 }`).

## Error-Response Strategy

- **Centralized error handling** — a single convention for all error responses,
  so clients parse errors uniformly.
- Planned error shape:
  ```json
  {
    "timestamp": "2026-09-21T10:00:00Z",
    "status": 404,
    "error": "Not Found",
    "path": "/api/v1/accounts/abc",
    "code": "ACCOUNT_NOT_FOUND",
    "message": "Account abc does not exist or is not visible to you",
    "fieldErrors": []
  }
  ```
- `code` is a stable, machine-readable identifier; `message` is human-readable;
  `fieldErrors` lists per-field validation failures.
- Errors never expose stack traces, SQL, or credentials.

## API Versioning Approach

- **URI path versioning**: `/api/v1/...`, `/api/v2/...` — chosen for simplicity
  and explicitness at the gateway.
- A client calls a specific version explicitly; the gateway routes to the
  owning service.
- Multiple API versions can coexist; lifecycle states (published → deprecated
  → retired) signal when removal is coming.
- Backward-compatible changes ship within a minor version; breaking changes
  require a new major version in the catalog.

## Authentication Headers

- Callers present credentials as a **Bearer token**; the gateway validates and
  forwards verified identity context to services:
  - `Authorization: Bearer <jwt>` — the standard path for users and
    OAuth2-style application flows.
  - Planned forwarded/internal headers (gateway → services), carrying only
    verified claims: `X-User-Id`, `X-Roles`, `X-Scopes`, `X-Application-Id`.
  - Note: services treat these headers as **enriched context from the
    gateway**, not as standalone trust; they still check ownership.
- Application credentials (client id/secret or API key) may be presented at
  token endpoints: `POST /auth/token` with `grant_type` and secrets in the body
  (never in URLs).

## Example Endpoint Structure

### Identity Service (behind gateway)
- `POST /api/v1/auth/register` — register user.
- `POST /api/v1/auth/login` — authenticate, return access + refresh tokens.
- `POST /api/v1/auth/token` — token endpoint (refresh, application flows).
- `POST /api/v1/auth/logout` — revoke refresh token.
- `GET  /api/v1/users/me` — current profile (self-service only).
- `GET  /api/v1/users` — admin only.

### API Management Service
- `GET  /apis` — public catalog (light/no auth).
- `GET  /apis/{apiId}` — detail incl. versions (public).
- `POST /apis` — admin: publish API.
- `POST /apis/{apiId}/versions` — admin/developer: add version.
- `GET  /apis/{apiId}/versions/{versionId}` — include lifecycle state.
- `GET  /apis/{apiId}/versions` — list versions of an API.

**Implemented so far (Phase 9):** the service exposes `POST /apis/{apiId}/versions`,
`GET /apis/{apiId}/versions/{versionId}`, and `GET /apis/{apiId}/versions`.
A version belongs to exactly one API; duplicate versions under the same API
return `409 API_VERSION_ALREADY_EXISTS`, and reading a version through the wrong
API returns `404 API_VERSION_NOT_FOUND` (no cross-API leak). Reading these
endpoints requires an `ADMIN` or `DEVELOPER` bearer token; unauthenticated or
expired tokens get `401 UNAUTHENTICATED`.

**Implemented so far (Phase 10) — API version lifecycle:** API version lifecycle
is a persisted property of the **API version** (not the API) with the states
`CREATED → PUBLISHED → DEPRECATED → RETIRED`. New versions start in `CREATED`;
the caller cannot choose the initial state when creating a version.
- `PATCH /apis/{apiId}/versions/{versionId}/lifecycle`
  with body `{ "lifecycle": "PUBLISHED" }` moves a version to the requested
  state (HTTP `200` with the updated version).
- Allowed transitions: `CREATED → PUBLISHED`, `CREATED → RETIRED`,
  `PUBLISHED → DEPRECATED`, `DEPRECATED → RETIRED`. Every other transition —
  including an "update" to the state already held — is rejected with
  `409 INVALID_LIFECYCLE_TRANSITION`.
- Same ownership rules as Phase 9 apply: the version must belong to the API in
  the URL, otherwise `404 API_VERSION_NOT_FOUND` (no leak about whether the
  version exists under another API); a missing API returns
  `404 API_NOT_FOUND`. An invalid lifecycle value in the JSON body returns
  `400 VALIDATION_FAILED`; a missing `lifecycle` field returns the same.
- The lifecycle endpoint is **ADMIN-only**; `DEVELOPER` and other roles get
  `403 ACCESS_DENIED` (or `401 UNAUTHENTICATED` when not authenticated). The
  existing `POST`/`GET` version endpoints keep their `ADMIN`+`DEVELOPER`
  access.
- The lifecycle value is persisted as a string (`CREATED`, `PUBLISHED`,
  `DEPRECATED`, `RETIRED`), never as an ordinal. `updatedAt` changes on a
  lifecycle change; `createdAt` never changes.
- The lifecycle is currently **metadata/state only**: it does not yet control
  gateway routing, subscriptions, or traffic. Enforcement of deprecation or
  retirement is intentionally out of scope until those components exist.

### Subscription Service
- `GET    /applications` — list own applications.
- `POST   /applications` — create application.
- `POST   /applications/{id}/credentials` — issue credential (secret shown once).
- `POST   /applications/{id}/subscriptions` — subscribe app to API version + tier.
- `GET    /subscriptions/{id}` — subscription status.
- `DELETE /applications/{id}/subscriptions/{subId}` — revoke.
- `GET    /subscriptions?apiVersionId=...` — admin: who holds this version.

### Account Service
- `GET  /accounts` — list own accounts with balances.
- `GET  /accounts/{id}` — account detail + balance.
- `POST /accounts` — open an account.

### Payment Service
- `POST /payments` — initiate payment.
- `GET  /payments/{id}` — payment status.
- `POST /payments/{id}/approve` — approved flow (per business rules).

### Transaction Service
- `GET /accounts/{accountId}/transactions?from=...&to=...&page=1&size=20`
  — paged, filtered history.

### Analytics Service
- `GET /analytics/applications/{appId}/usage?since=...&until=...` — counts
  per API/status/latency buckets for the Developer Portal.

## Why These Choices (interview-ready)

- URI versioning is explicit, cache-friendly, and trivially routable by the
  gateway.
- Uniform error shape + stable codes makes client handling predictable.
- DTOs at boundaries protect domain models from API coupling and allow the API
  contract to evolve independently.
- Do-not-leak-404 mapping for unowned resources avoids confirming existence of
  other users' resources.
- Centralized error handling keeps controllers thin per `AGENTS.md`.