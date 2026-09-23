# API Design (Planned)

> This document describes the **planned** REST API conventions for the OpenBank
> API Platform. Early phases already implemented endpoint sets (Identity,
> API Management, Payment Service) — each is marked "Implemented so far" below.
> The Phase 15–21 gateway is also implemented (routing + JWT and
> client-credential authentication + subscription enforcement + Redis rate
> limiting; see the Gateway section). Consistent conventions
> apply to the Developer
> Portal-facing APIs and
> the consumer-facing APIs routed by the gateway. OpenAPI documents for each
> service will be produced in the relevant service phases.

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
  - **Phase 16 note:** the gateway authenticates presenters itself: it
    validates the `Authorization: Bearer <jwt>` header (HS256 signature via
    the `JWT_SECRET` variable, expiry, and mandatory `sub` + `role` claims) and
    forwards it untouched to services; the `X-*` forwarded headers above are
    still planned. See Security for the public/protected route split.
  - **Phase 21 note (application client credentials):** for managed API
    invocations only (`/runtime/apis/**`), an application may authenticate with
    `Authorization: Basic base64(clientId:clientSecret)` (the Phase 13
    credentials) instead of a Bearer JWT. The gateway verifies the credential
    (and the application's subscription) via the internal credential-check
    endpoint and **strips the header before forwarding**, so the consumed
    backend never sees the secret. Platform-management routes do not accept
    Basic — they remain Bearer-only.
- Application credentials (client id/secret or API key) may be presented at
  token endpoints: `POST /auth/token` with `grant_type` and secrets in the body
  (never in URLs).

## Example Endpoint Structure

### Gateway (implemented — Phases 15–21)

The `gateway-service` (Spring Cloud Gateway, port `8080`) exposes **no business
endpoints of its own**; it forwards the existing service paths unchanged and
adds one managed-API invocation route (`/runtime/apis/**`, Phase 17):

| Gateway path | Upstream | Env var (development default) |
| --- | --- | --- |
| `/users/**`, `/auth/**` | Identity | `IDENTITY_SERVICE_URL` (`http://localhost:8081`) |
| `/apis/**`, `/applications/**`, `/subscriptions/**`, `/credentials/**` | API Management | `API_MANAGEMENT_SERVICE_URL` (`http://localhost:8081`) |
| `/accounts/**`, `/payments/**`, `/transactions/**` | Payment | `PAYMENT_SERVICE_URL` (`http://localhost:8082`) |
| `/runtime/apis/**` | Managed API target (consumed APIs) | `MANAGED_API_TARGET_URL` (`http://localhost:8084`) |

- The upstream sees exactly the path, query, method, body, and headers the
  client sent — the URI is **not rewritten** (no `StripPrefix`). Any path not
  in the table returns the gateway's own `404`.
- **Runtime upstream validation (Phase 20):** the `/runtime/apis/**` target is
  configured via `MANAGED_API_TARGET_URL` (development default
  `http://localhost:8084`) and validated at startup: absolute `http(s)` URL, a
  host present, and no credentials/userinfo, fragment, or whitespace. Invalid
  values abort gateway startup with a generic message that never echoes the
  configured value. The target is **configuration-only** — a client-controlled
  query, header, or path value can never choose or override the upstream, and
  the gateway has no dynamic registry. Forwarding (path, query, method, body,
  headers) is unchanged, and an unreachable or timed-out target still returns
  `503` + code `UPSTREAM_SERVICE_UNAVAILABLE` with a fixed, leak-free message.
- **Authentication (Phase 16):** the gateway requires
  `Authorization: Bearer <jwt>` on every routed path except `POST /users`,
  `POST /auth/login`, and `GET /actuator/health` (method-sensitive: e.g.
  `GET /users/me`, `GET/DELETE /users/*`, and any other `POST /auth/*` are
  protected). Tokens must be signed with the shared `JWT_SECRET` (HS256),
  unexpired, and carry numeric `sub` + `role` (ADMIN or DEVELOPER) claims.
  Rejections return a stable shape:
  `{"timestamp", "status":401, "error":"Unauthorized", "path",
  "code":"UNAUTHENTICATED", "message":"Authentication is required",
  "fieldErrors":{}}` — the gateway deliberately does not disclose which rule
  failed. Valid requests are forwarded with the `Authorization` header
  unchanged; the gateway issues no tokens.
- **Subscription enforcement (Phase 17):** requests routed to
  `/runtime/apis/**` are additionally gated on an **active subscription**. The
  invoked API version is identified from the path as
  `/runtime/apis/{context}/{version}/...`; the caller's user id is derived
  **only** from the validated JWT `sub` claim, and the gateway calls the API
  Management Service's internal `GET /internal/subscription-check?contextPath={context}&version={version}` endpoint (authenticated; not reachable through any gateway route; returns only `{"subscribed": bool}` from a direct PostgreSQL query — no cache). Decision table:
  - valid JWT + subscribed → request forwarded unchanged;
  - valid JWT + not subscribed (or subscription belonging to another user) →
    `403` + code `SUBSCRIPTION_REQUIRED`; stable shape
    `{"timestamp", "status":403, "error":"Forbidden", "path",
    "code":"SUBSCRIPTION_REQUIRED",
    "message":"An active subscription is required to access this API",
    "fieldErrors":{}}`;
  - check unavailable / failed (unreachable, timeout, 5xx, malformed body) →
    `503` + code `SUBSCRIPTION_SERVICE_UNAVAILABLE`; stable shape
    `{"timestamp", "status":503, "error":"Service Unavailable", "path",
    "code":"SUBSCRIPTION_SERVICE_UNAVAILABLE",
    "message":"Subscription verification is temporarily unavailable",
    "fieldErrors":{}}`. The gateway **fails closed** — an unverified request is
  never forwarded.
  This is **intentionally lifecycle-agnostic for Phase 17**: a subscription to
  the target API version satisfies the gate regardless of its lifecycle state
  (`CREATED`, `PUBLISHED`, `DEPRECATED`, `RETIRED`). Requiring `PUBLISHED` (or
  any other lifecycle-based runtime blocking) is out of scope for Phase 17 — the
  lifecycle remains metadata-only at runtime (see the Phase 10 lifecycle notes
  below). The gate has no `ADMIN` bypass; a missing version segment returns `403`
  without calling the check. A single-segment context (e.g.
  `/runtime/apis/accounts`) limits the version-segment parsing (documented
  limitation).
- **Client-credential authentication (Phase 21):** managed API invocations
  (`/runtime/apis/**`) also accept **application client credentials**:
  `Authorization: Basic base64(clientId:clientSecret)` (the Phase 13
  credentials), presented instead of a user JWT. The gateway derives the API
  version from the path and calls the API Management Service's internal,
  self-authenticating `GET /internal/credential-check?contextPath={context}&version={version}`
  endpoint with the **same Basic header** (not reachable through any gateway
  route). That endpoint verifies the secret server-side (`clientId` lookup +
  BCrypt against the stored hash — direct PostgreSQL query, no cache, no plaintext
  secret or hash ever returned/forwarded/logged) and returns
  `{"authenticated":true,"applicationId":N,"ownerUserId":N,"subscribed":bool}`
  (any failure returns `{"authenticated":false}`). Decision table:
  - credential not found / secret mismatch / malformed credential → `401` +
    code `CLIENT_CREDENTIAL_INVALID`; stable shape
    `{"timestamp","status":401,"error":"Unauthorized","path",
    "code":"CLIENT_CREDENTIAL_INVALID","message":"Invalid client credentials",
    "fieldErrors":{}}`;
  - check unavailable / failed (unreachable, timeout, 5xx, malformed body) →
    `503` + code `CREDENTIAL_SERVICE_UNAVAILABLE`; stable shape
    `{"timestamp","status":503,"error":"Service Unavailable","path",
    "code":"CREDENTIAL_SERVICE_UNAVAILABLE","message":
    "Credential verification is temporarily unavailable","fieldErrors":{}}`;
    the gateway **fails closed** — an unverified request is never forwarded;
  - valid credentials but the **authenticated application** is not subscribed
    to the target API version → `403` + code `SUBSCRIPTION_REQUIRED` (same body
    as Phase 17; decided by the same single check — no second network call,
    identity is the application's `applicationId`);
  - malformed runtime path with Basic → `403` + code `SUBSCRIPTION_REQUIRED`
    (mirrors the JWT flow, no check call).
  The Basic header is then **stripped before forwarding**, so the consumed
  backend never sees the credentials. A request carrying both `Bearer` and
  `Basic` is handled by the client-credential flow (the JWT filter skips).
  Platform-management routes remain Bearer-only: Basic on `/apis/**` → the
  standard `401 UNAUTHENTICATED`.
- **Rate limiting (Phase 21 — Redis-backed, per API version):** every
  `/runtime/apis/**` request is rate limited before routing via Redis
  fixed-window counters keyed `rate_limit:user:{userId}:{context}:{version}`
  (JWT callers) or `rate_limit:app:{applicationId}:{context}:{version}`
  (client-credential callers), with `RATE_LIMIT_REQUESTS` (default `100`)
  requests per `RATE_LIMIT_WINDOW_SECONDS` (default `60`) slot. Exceeded →
  `429` + code `RATE_LIMIT_EXCEEDED` (`{"timestamp","status":429,
  "error":"Too Many Requests","path","code":"RATE_LIMIT_EXCEEDED",
  "message":"Rate limit exceeded","fieldErrors":{}}`, plus a `Retry-After`
  header); Redis/counter failure → `503` + code
  `RATE_LIMIT_SERVICE_UNAVAILABLE` (fail closed). This is the first business
  use of Redis.
- Backend responses (including 4xx/5xx error bodies) pass through unchanged.
- When an upstream is unreachable or exceeds the 2 s connect / 5 s response
  timeout, the gateway itself returns `503` with a **stable error shape** that
  never includes the upstream host, port, or URL:

  ```json
  {
    "timestamp": "2026-09-22T12:00:00Z",
    "status": 503,
    "error": "Service Unavailable",
    "code": "UPSTREAM_SERVICE_UNAVAILABLE",
    "message": "The requested service is currently unavailable"
  }
  ```

- `GET /actuator/health` → `{"status":"UP"}` — the only exposed actuator
  endpoint (the gateway route-discovery endpoints are not exposed).
- Development note: the gateway's Identity default URL (`8081`) collides with
  API Management's default port (`8081`), while the Identity Service's own
  default port is `8080`. Running all three locally therefore requires
  overriding one of them, e.g. `IDENTITY_SERVICE_URL=http://localhost:8080`.

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
- The lifecycle is **metadata/state only through Phase 17**: it does not control
  gateway routing, subscriptions, or traffic. Lifecycle-based traffic control
  (e.g. only `PUBLISHED` versions invocable, deprecation/retirement sunset
  rules) is intentionally out of scope; the Phase 17 subscription-enforcement
  gate is lifecycle-agnostic for the same reason (see Subscription enforcement
  above).

**Implemented so far (Phase 11) — developer applications:** developers (and
admins) can manage their **applications** in the API Management Service. An
application's owner is the authenticated user: `ownerUserId` is derived from the
JWT `sub` claim, never from the request body. Ownership is **logical** — the
`ownerUserId` references an Identity Service user by ID; the API Management
Service stores no user record and no cross-service foreign key.
- `POST /applications` — create an application (`name` required,
  `description` optional); returns `201 Created` with a `Location` header.
- `GET /applications` — list the caller's **own** applications (name/description
  owner-scoped).
- `GET /applications/{applicationId}` — a single owned application.
- `PATCH /applications/{applicationId}` — partial update of `name`/`description`
  only. Omitted fields stay unchanged; an empty body is a no-op.
- Authorization is uniform for `ADMIN` and `DEVELOPER` bearer tokens with
  owner-based semantics: `ADMIN` owns what it creates and has **no** global
  access to other users' applications. Accessing an application that does not
  exist **or is not owned by the caller** returns `404 APPLICATION_NOT_FOUND`
  (identical to "does not exist" — no existence leak). Unauthenticated,
  invalid, or expired tokens return `401 UNAUTHENTICATED`.
- Validation: `name` is required, ≤ 255 characters, with no leading/trailing
  whitespace; `description` ≤ 2000 characters. Violations return
  `400 VALIDATION_FAILED`; malformed JSON returns `400 MALFORMED_REQUEST`.
- Names are **not unique** — the same name may be used by the same or different
  developers; there is no duplicate check.
- No credentials (client id/secret, API keys) or subscriptions are attached to
  applications yet; those remain planned under the Subscription Service.
  Application deletion is intentionally not part of this phase.

**Implemented so far (Phase 12) — subscriptions:** an application can be
**subscribed** to an **API version** in the API Management Service. This
implements the Application → Subscription → API Version link. The request body
carries only `applicationId` and `apiVersionId`; the **owner is always derived
from the authenticated user's JWT `sub` claim** and is never taken from the
request body.
- `POST /subscriptions` — subscribe an owned application to an API version.
  Body: `{ "applicationId": long, "apiVersionId": long }` (both required).
  Returns `201 Created` with a `Location: /subscriptions/{id}` header and the
  subscription body (`id`, `applicationId`, `apiVersionId`, `createdAt`,
  `updatedAt`).
- `GET /subscriptions/{subscriptionId}` — the caller's **own** subscription.
- `GET /subscriptions` — list the caller's **own** subscriptions in ascending
  `id` order (owner-scoped; other users' subscriptions never appear).
- Authorization is uniform for `ADMIN` and `DEVELOPER` with owner-based
  semantics, exactly like applications: `ADMIN` owns what it creates and has
  **no** global access to other users' subscriptions. Accessing a subscription
  that does not exist **or belongs to an application the caller does not own**
  returns `404 APPLICATION_SUBSCRIPTION_NOT_FOUND` (no existence leak).
- Cross-owner or missing target resources: an `applicationId` the caller does
  not own (or that does not exist) returns `404 APPLICATION_NOT_FOUND`; a
  non-existent `apiVersionId` returns `404 API_VERSION_NOT_FOUND`. Both are
  checked before the subscription is created.
- Duplicate subscriptions (same application + same API version) → `409
  SUBSCRIPTION_ALREADY_EXISTS`. Enforced in the service up front **and** by a
  database unique constraint on `(application_id, api_version_id)`; the
  resulting `DataIntegrityViolationException` from a race condition is mapped to
  the same `409` response. Two different applications may subscribe to the same
  API version, and one application may subscribe to multiple versions.
- Validation: `applicationId` and `apiVersionId` are required (`@NotNull`);
  violations return `400 VALIDATION_FAILED`, malformed JSON returns `400
  MALFORMED_REQUEST`.
- A subscription is currently **metadata/link only**: it carries no lifecycle
  state, status, tier, credentials (API key / client id+secret), or rate limit.
  Those remain planned under the Subscription Service.

**Implemented so far (Phase 13) — credentials:** an **application** can hold
**credentials** (`clientId` + hashed `clientSecret`) in the API Management
Service. These are the credentials an API Gateway will later use to
authenticate an application; gateway-side authentication is still a later
phase. The request body carries only `applicationId`; the **owner is always
derived from the authenticated user's JWT `sub` claim** and is never taken from
the request body.
- `POST /credentials` — create a credential for an owned application. Body:
  `{ "applicationId": long }` (required). `clientId` and `clientSecret` are
  generated **server-side** and are not accepted from the client (extra body
  fields are ignored). Returns `201 Created` with a `Location: /credentials/{id}`
  header and body (`id`, `applicationId`, `clientId`, `clientSecret`,
  `createdAt`, `updatedAt`). The plaintext `clientSecret` is returned
  **exactly once**, in this creation response only.
- `GET /credentials/{credentialId}` — the caller's **own** credential
  (applications the caller owns). Returns `CredentialResponse` (`id`,
  `applicationId`, `clientId`, `createdAt`, `updatedAt`) — **never** the
  `clientSecret` or `clientSecretHash`.
- `GET /credentials` — list the caller's **own** credentials in ascending `id`
  order (owner-scoped; other users' credentials never appear, no secrets).
- Authorization is uniform for `ADMIN` and `DEVELOPER` with owner-based
  semantics, exactly like applications/subscriptions: `ADMIN` owns what it
  creates and has **no** global access. Credential ownership is derived through
  Credential → Application → ownerUserId. Accessing a credential that does not
  exist **or belongs to an application the caller does not own** returns `404
  CREDENTIAL_NOT_FOUND` (no existence leak). Creating a credential against an
  application the caller does not own (or that does not exist) returns `404
  APPLICATION_NOT_FOUND`.
- `clientId` uniqueness: generated as a random UUID, unique per credential. A
  service-level existence check plus a database unique constraint on
  `client_id`; if a collision somehow occurs, the service regenerates and
  retries rather than surfacing a database error.
- Secret hashing: the `clientSecret` is hashed with BCrypt (the same
  `PasswordEncoder` infrastructure as user passwords) and **only the hash** is
  stored. The plaintext secret cannot be retrieved after creation.
- Validation: `applicationId` is required (`@NotNull`); violations return `400
  VALIDATION_FAILED`, malformed JSON returns `400 MALFORMED_REQUEST`.
- No credential lifecycle yet: no rotation, revocation, status, expiry, scopes,
  permissions, or rate limits. Those remain planned.

### Subscription Service

> `POST /applications` and `GET /applications` are **implemented** in the API
> Management Service (Phase 11), the Application → Subscription → API Version
> link (`POST /subscriptions`, `GET /subscriptions/{id}`, `GET /subscriptions`)
> in Phase 12, and application **credentials** (`POST /credentials`,
> `GET /credentials/{id}`, `GET /credentials`) in Phase 13 — see the sections
> above. They are repeated here as the planned contract for the gateway/portal
> view; the remaining items (tiers, status, revocation) are not yet implemented.
- `POST   /applications/{id}/credentials` — (implemented via Phase 13 `POST /credentials`; secret shown once).
- `POST   /applications/{id}/subscriptions` — (planned) subscribe app to API version + tier; the un-tiered link already exists via Phase 12 `POST /subscriptions`.
- `GET    /subscriptions/{id}` — subscription status.
- `DELETE /applications/{id}/subscriptions/{subId}` — revoke.
- `GET    /subscriptions?apiVersionId=...` — admin: who holds this version.

### Account Service (Payment Service — Account domain)

**Implemented so far (Phase 14):**
- `POST /accounts` — open the caller's account. Body: `{ "currency": "LKR" }`
  (optional; must match `[A-Z]{3}` if present, default `LKR`). The owner is
  always derived from the JWT `sub` claim — never from the body. Returns `201
  Created` with a `Location: /accounts/{id}` header and the account body (`id`,
  `ownerUserId`, `currency`, `createdAt`, `updatedAt`). Duplicate (one account
  per user) returns `409 ACCOUNT_ALREADY_EXISTS`.
- `GET /accounts/{id}` — the caller's **own** account. Missing or not owned →
  `404 ACCOUNT_NOT_FOUND` (no existence leak).
- `GET /accounts` — the caller's **own** accounts (empty list when none).
- Authorization is uniform for `ADMIN` and `DEVELOPER`: `ADMIN` owns what it
  creates and has no global access. Unauthenticated/invalid/expired tokens →
  `401 UNAUTHENTICATED`; unknown roles fail closed to `401`.
- No update, no delete, **no balance**, no account number/type yet.

### Payment Service (Payment domain)

**Implemented so far (Phase 14):**
- `POST /payments` — create payment instructions against the caller's account.
  Body: `{ "accountId": long, "amount": decimal, "description": string? }`.
  Amount must be > 0 with at most 2 fraction digits and 17 integer digits;
  description ≤ 500 chars. A missing/unowned `accountId` → `404
  ACCOUNT_NOT_FOUND`. Returns `201 Created` with `Location: /payments/{id}` and
  the payment body (`id`, `accountId`, `amount`, `currency`, `description`,
  `status` = `PENDING`, `createdAt`, `updatedAt`). The status is **always**
  `PENDING` and the currency **always the account's** — the client cannot choose
  either (extra body fields are ignored). No payment is actually processed, and
  **no transaction is created**.
- `GET /payments/{id}` — the caller's **own** payment. Missing or not owned →
  `404 PAYMENT_NOT_FOUND`.
- `GET /payments` — the caller's **own** payments in ascending `id` order.
- No approval/processing/lifecycle endpoints yet (planned:
  `POST /payments/{id}/approve`, status transitions).

### Transaction Service (Payment Service — Transaction domain)

**Implemented so far (Phase 14):**
- `POST /transactions` — record that a payment was made for the caller's
  account. Body: `{ "accountId": long, "paymentId": long, "amount": decimal }`.
  The account must be owned by the caller (else `404 ACCOUNT_NOT_FOUND`) and the
  payment must belong to exactly that account (else `404 PAYMENT_NOT_FOUND` — a
  payment on another account is indistinguishable from a missing one). Amount
  validation as for payments. Returns `201 Created` with
  `Location: /transactions/{id}` and the transaction body (`id`, `accountId`,
  `paymentId`, `type` = `PAYMENT`, `amount`, `currency` = the account's,
  `createdAt`). The type and currency are **always** derived server-side; the
  client cannot choose them.
- `GET /transactions/{id}` — the caller's **own** transaction (via account
  ownership). Missing or not owned → `404 TRANSACTION_NOT_FOUND`.
- `GET /transactions` — the caller's **own** transactions in ascending `id`
  order.
- No paging, no date filters, no `balance_after`, no debit/credit direction
  yet; a transaction is immutable (no `updatedAt`).
- Planned (later): `GET /accounts/{accountId}/transactions?from=...&to=...&page=1&size=20`.

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