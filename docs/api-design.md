# API Design (Planned)

> This document describes the **planned** REST API conventions for the OpenBank
> API Platform. Early phases already implemented endpoint sets (Identity,
> API Management, Payment Service) — each is marked "Implemented so far" below.
> The Phase 15–22 gateway is also implemented (routing + JWT and
> client-credential authentication + subscription enforcement + Redis rate
> limiting + trusted identity headers; see the Gateway section). Consistent conventions
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
  - Forwarded/internal headers (gateway → managed APIs, **implemented for
    `/runtime/apis/**` since Phase 22**), carrying only verified claims:
    `X-User-Id`, `X-Roles` (user flow) and `X-User-Id`, `X-Application-Id`,
    `X-Client-Id`, `X-Scopes` (application flow; `X-Scopes` carries the
    gateway-verified effective scopes since Phase 24 Slice 16 and is present
    only when the effective string is non-empty — absence means *either*
    unrestricted *or* an empty credential ∩ tier intersection, never an
    authorization grant). See the Phase 22 note below.
  - Note: services treat these headers as **enriched context from the
    gateway**, not as standalone trust; they still check ownership.
  - **Phase 16 note:** the gateway authenticates presenters itself: it
    validates the `Authorization: Bearer <jwt>` header (HS256 signature via
    the `JWT_SECRET` variable, expiry, and mandatory `sub` + `role` claims) and
    forwards it untouched to services; the `X-*` forwarded headers above are
    implemented for managed-API invocations in Phase 22. See Security for the
    public/protected route split.
  - **Phase 21 note (application client credentials):** for managed API
    invocations only (`/runtime/apis/**`), an application may authenticate with
    `Authorization: Basic base64(clientId:clientSecret)` (the Phase 13
    credentials) instead of a Bearer JWT. The gateway verifies the credential
    (and the application's subscription) via the internal credential-check
    endpoint and **strips the header before forwarding**, so the consumed
    backend never sees the secret. Platform-management routes do not accept
    Basic — they remain Bearer-only.
  - **Phase 22 note (trusted identity headers):** after successful
    authentication of a `/runtime/apis/**` request, the gateway supplies the
    verified caller identity as gateway-generated request headers — `X-User-Id`
    + `X-Roles` for the JWT/user flow, `X-User-Id` + `X-Application-Id` +
    `X-Client-Id` (+ `X-Scopes`, the validated effective-scope string, when
    non-empty) for the client-credential/application flow — on top of the
    unchanged `Authorization` behavior (Bearer forwarded, Basic stripped). These
    headers are **never trusted from the client**: any client-supplied
    `X-User-Id`, `X-Roles`, `X-Application-Id`, `X-Client-Id`, or `X-Scopes`
    value is
    stripped before trusted values are added. Platform-management routes receive
    none of them.
- Application credentials (client id/secret or API key) may be presented at
  token endpoints: `POST /auth/token` with `grant_type` and secrets in the body
  (never in URLs).

## Example Endpoint Structure

### Gateway (implemented — Phases 15–22)

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
  Management Service's internal `GET /internal/subscription-check?contextPath={context}&version={version}` endpoint (authenticated; not reachable through any gateway route; returns `{"subscribed": bool}` plus, when subscribed, the active subscription's tier policy `{"tierId","tierName","requestsPerWindow","windowSeconds"}` from a direct PostgreSQL query — no cache). Decision table:
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
  plus, when subscribed, the active subscription's tier policy
  (`tierId`, `tierName`, `requestsPerWindow`, `windowSeconds`)
  and the **effective scopes** `scopes` — a space-delimited canonical string
  (`""` = unrestricted) computed server-side as credential ∩ tier
  allowedScopes (Phase 24 Slice 16)
  (any failure returns `{"authenticated":false}`). Decision table:
  - credential not found / secret mismatch / inactive or expired credential /
    malformed credential → `401` + code `CLIENT_CREDENTIAL_INVALID`; stable shape
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
  - confirmed subscription (`subscribed:true`) **without** a valid tier policy
    (missing/malformed `requestsPerWindow`/`windowSeconds`) in the check
    response → `503` + code `CREDENTIAL_SERVICE_UNAVAILABLE` /
    `SUBSCRIPTION_SERVICE_UNAVAILABLE` (fail closed, Phase 24 Slice 4);
  - an authenticated check outcome whose `scopes` value is missing, not a JSON
    string, or not a valid canonical scope string (token grammar, length, or
    duplicate violation) → `503` + code `CREDENTIAL_SERVICE_UNAVAILABLE`
    (fail closed, Phase 24 Slice 16; an empty `""` value is valid);
  - malformed runtime path with Basic → `403` + code `SUBSCRIPTION_REQUIRED`
    (mirrors the JWT flow, no check call).
  The Basic header is then **stripped before forwarding**, so the consumed
  backend never sees the credentials. A request carrying both `Bearer` and
  `Basic` is handled by the client-credential flow (the JWT filter skips).
  Platform-management routes remain Bearer-only: Basic on `/apis/**` → the
  standard `401 UNAUTHENTICATED`.
- **Trusted identity headers (Phase 22):** for managed API invocations
  (`/runtime/apis/**`) the gateway adds verified caller identity as
  gateway-generated request headers before forwarding:
  - **JWT/user flow** → `X-User-Id` (the JWT `sub` user id) + `X-Roles` (the
    `role` claim, e.g. `ADMIN`/`DEVELOPER`); `Authorization: Bearer <jwt>` is
    still forwarded unchanged.
  - **Client-credential/application flow** → `X-User-Id` (the credential's
    `ownerUserId`) + `X-Application-Id` (the credential's `applicationId`) +
    `X-Client-Id` (the credential's `clientId`) + `X-Scopes` (the
    gateway-parsed effective scope string, added only when non-empty since
    Phase 24 Slice 16); **no `X-Roles`**; `Authorization`
    remains stripped.
  - **Spoof-safe:** the gateway strips any client-supplied `X-User-Id`,
    `X-Roles`, `X-Application-Id`, `X-Client-Id`, or `X-Scopes` from the inbound
    request
    (a reusable sanitizer) before trusted values are added — these headers are
    never accepted from the client (end-to-end tests assert a managed upstream
    receives only gateway-derived values).
  - Applied **only** on `/runtime/apis/**`; platform-management routes receive
    none. Managed APIs receive them as enriched context, while each backend
    still validates the JWT locally / re-checks ownership (defense in depth).
- **Rate limiting (Phase 21 — Redis-backed, per API version; policy-driven since
  Phase 24 Slice 4):** every `/runtime/apis/**` request is rate limited before
  routing via Redis fixed-window counters keyed
  `rate_limit:user:{userId}:{context}:{version}` (JWT callers) or
  `rate_limit:app:{applicationId}:{context}:{version}` (client-credential
  callers). The limit/window are **no longer configured directly on the
  gateway** — each check response (subscription or credential) carries the
  active subscription's tier policy (`requestsPerWindow` requests per
  `windowSeconds`, defaults `100`/`60`), and the gateway applies it to the
  request; a subscribed request whose policy is missing or malformed fails
  closed with `503` `RATE_LIMIT_SERVICE_UNAVAILABLE` before the limiter is
  contacted. Exceeded → `429` + code `RATE_LIMIT_EXCEEDED`
  (`{"timestamp","status":429,
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

**Implemented so far (Phase 24, Slice 8) — subscription tier updates:** an
existing tier's name, description, rate-limit policy, price, and allowed
scopes can be partially
updated by an `ADMIN` through `PATCH /subscription-tiers/{tierId}`. The body
may contain `name`, `description`, `requestsPerWindow`, `windowSeconds`,
`priceCentsPerMonth`, and/or `allowedScopes`;
at least one field is required, and omitted fields (including JSON `null`) remain
unchanged. A `DEVELOPER` receives `403 ACCESS_DENIED`; an unauthenticated caller receives
`401 UNAUTHENTICATED`. A missing tier returns `404
SUBSCRIPTION_TIER_NOT_FOUND`, a conflicting name returns `409
SUBSCRIPTION_TIER_ALREADY_EXISTS`, and invalid values return `400
VALIDATION_FAILED`. The response is the tier's public fields (`id`, `name`,
`description`, `requestsPerWindow`, `windowSeconds`, `priceCentsPerMonth`,
`allowedScopes`,
`status`, `createdAt`, `updatedAt`).
PostgreSQL remains the source of truth; after a successful commit, only the
exact `subscriptionTier::<tierId>` Redis entry is evicted. A failed transaction
does not evict. Explicit ADMIN tier reassignment is implemented in Phase 24
Slice 10.

**Implemented so far (Phase 24, Slice 13) — subscription tier creation and
listing:**
- `POST /subscription-tiers` — **ADMIN-only** creation of a new subscription
  tier. Body: `{ "name": string, "description"?: string, "requestsPerWindow"?: int,
  "windowSeconds"?: int, "priceCentsPerMonth"?: int, "allowedScopes"?:
  string[] }`. `name` is required, ≤ 100
  characters, and must not
  be blank or contain leading/trailing whitespace; `description` is optional and
  ≤ 500 characters; when `requestsPerWindow`/`windowSeconds` are omitted they
  default to `100`/`60` (the same defaults as the table columns), when
  `priceCentsPerMonth` is omitted it defaults to `0` (free tier), and when
  supplied the rate-limit fields must be positive and the price must not be
  negative. `allowedScopes` follows the scope grammar below (omitted → `""`,
  unrestricted). Returns `201 Created` with
  `Location: /subscription-tiers/{id}` and the tier's public fields (`id`,
  `name`, `description`, `requestsPerWindow`, `windowSeconds`,
  `priceCentsPerMonth`, `allowedScopes`, `status`, `createdAt`, `updatedAt`). A duplicate name returns `409 SUBSCRIPTION_TIER_ALREADY_EXISTS`
  (service check plus unique-constraint race backstop). Invalid input returns
  `400 VALIDATION_FAILED`; a `DEVELOPER` receives `403 ACCESS_DENIED`; an
  unauthenticated caller receives `401 UNAUTHENTICATED`. Creation does not
  evict Redis: a new database id was never cacheable, so no existing
  `subscriptionTier` entry can be stale.
- `GET /subscription-tiers` — **ADMIN-only** listing of every tier ordered by
  `id` ascending (empty array when none exist). The response is an array of the
  same public fields. The list is not cached (only `GET`-by-id via the internal
  `subscriptionTier` cache is). A `DEVELOPER` receives `403 ACCESS_DENIED`; an
  unauthenticated caller receives `401 UNAUTHENTICATED`.
- Tier pricing (`priceCentsPerMonth`) is implemented in Slice 15 below; the
  tier status lifecycle is implemented in Slice 14 below.

**Implemented so far (Phase 24, Slice 14) — subscription tier status
lifecycle:**
- Every tier carries a `status` (`ACTIVE` or `INACTIVE`), persisted as
  `varchar(32) NOT NULL DEFAULT 'ACTIVE'` and exposed in every tier response
  alongside the other public fields. New tiers are `ACTIVE`.
- `PATCH /subscription-tiers/{tierId}/status` — **ADMIN-only** change of the
  lifecycle state. Body: `{ "status": "ACTIVE" | "INACTIVE" }` (`status` is
  required). Both directions are allowed — there is no transition matrix for
  the two-state lifecycle — and supplying the current status is an idempotent
  no-op: the tier is not saved, `updatedAt` is untouched, and no cache entry
  is evicted. Returns `200 OK` with the tier's public fields; a missing status
  or invalid value returns `400 VALIDATION_FAILED`; a missing tier returns
  `404 SUBSCRIPTION_TIER_NOT_FOUND`; a `DEVELOPER` receives `403
  ACCESS_DENIED`; an unauthenticated caller receives `401 UNAUTHENTICATED`.
  A real change evicts only the exact `subscriptionTier::<tierId>` Redis entry
  **after commit** (via `CacheInvalidationService`); a rejected or rolled-back
  update evicts nothing.
- **Inactive-tier enforcement:** selecting an `INACTIVE` tier as the target of
  `POST /subscriptions` or `PATCH /subscriptions/{subscriptionId}/tier`
  returns `409 SUBSCRIPTION_TIER_INACTIVE` and changes nothing (the tier is
  checked right after the lookup, before the duplicate-subscription and
  same-tier idempotency checks respectively). Deactivating a tier **never**
  touches subscriptions that already reference it: they keep their
  `tier_id`, status, and rate-limit policy, and the gateway's enforcement is
  unchanged — existing traffic continues under the same policy. Reactivating
  the tier simply re-enables new assignments.

**Implemented so far (Phase 24, Slice 15) — subscription tier pricing:** every
tier carries a `priceCentsPerMonth` (integer cents per month) that is part of
the public tier fields, exposed in create, list, read, and both PATCH
responses after `windowSeconds`. `POST /subscription-tiers` accepts an
optional `priceCentsPerMonth`; when omitted it defaults to `0` (a free tier)
and is persisted as `price_cents_per_month integer NOT NULL DEFAULT 0`.
`PATCH /subscription-tiers/{tierId}` accepts it like any other partial field:
an omitted/`null` value leaves the price unchanged, a supplied value replaces
it, and a price-only body satisfies the at-least-one-field rule. A negative
value returns `400 VALIDATION_FAILED` (`priceCentsPerMonth must not be
negative`) on both endpoints, and the service rejects a negative price with an
`IllegalArgumentException` as a backstop for direct callers. A missing tier on
update still returns `404 SUBSCRIPTION_TIER_NOT_FOUND`. The gateway and the
internal subscription/credential checks are unchanged: the price is carried
and stored only — it is never charged and is not part of the internal
rate-limit policy payload. A successful price change participates in the
same post-commit exact-key eviction as every other tier update (only
`subscriptionTier::<tierId>`); PostgreSQL remains the source of truth, and
rows that existed before the column was added read back as `0` through the
column default. Hard limits/burst, currency handling, and billing remain
planned.

**Implemented so far (Phase 24, Slice 16) — credential scopes and tier
`allowedScopes`:** credentials and subscription tiers carry **scopes**,
persisted as a space-delimited canonical string in `text NOT NULL DEFAULT ''`
columns (`credentials.scopes`, `subscription_tiers.allowed_scopes`), with `""`
meaning unrestricted.
- **Scope grammar** (shared `@ValidScopes` constraint + service backstop):
  a JSON array of at most 20 **unique** tokens, each ≤ 64 characters, matching
  `^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*(?::[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*)?$`
  (lowercase start, at most one `:` separator, e.g. `accounts:read`). Invalid
  or duplicate tokens → `400 VALIDATION_FAILED` (`fieldErrors.scopes`);
  canonicalization preserves the caller's input order.
- `POST /credentials` and `POST /applications/{applicationId}/credentials`
  accept an optional `"scopes": string[]` (omitted/`null` → `""`); creation,
  rotation, GET, and list responses expose the canonical `scopes` string after
  `expiresAt`.
- `PATCH /credentials/{credentialId}/scopes` — **ADMIN-only** scope update
  (global management operation keyed by credential id, not owner-scoped,
  mirroring the expiration PATCH). Body: `{ "scopes": string[] }` (`scopes` is
  required, `null` → `400 VALIDATION_FAILED`). The update changes only
  `scopes` (and `updatedAt`): no rotation, status untouched, a `REVOKED`
  credential is updated **without** being reactivated. Response is the same
  `CredentialResponse` with the new `scopes`. A missing credential → `404
  CREDENTIAL_NOT_FOUND`; invalid/duplicate tokens → `400 VALIDATION_FAILED`; a
  `DEVELOPER` → `403 ACCESS_DENIED`; an unauthenticated caller → `401
  UNAUTHENTICATED`.
- Tier create/PATCH accept an optional `allowedScopes` with the same grammar;
  a scopes-only PATCH whose canonical value is unchanged is a **no-op** (no
  save, `updatedAt` untouched, no cache eviction), while a real change evicts
  only `subscriptionTier::<tierId>` after commit.
- The internal `GET /internal/credential-check` response now includes
  `scopes`, the **effective scopes** computed server-side as credential ∩
  tier (in credential order; either side `""` → unrestricted; empty
  intersection → `""` while `authenticated` stays `true`). The gateway
  **parses and validates** this value on every authenticated outcome (missing,
  non-textual, or malformed → `503 CREDENTIAL_SERVICE_UNAVAILABLE`, fail
  closed), forwards `X-Scopes` only when non-empty (after stripping any
  client-supplied `X-Scopes`), and never sets it on the JWT flow. Because an
  empty intersection and an unrestricted pair both encode as `""`, an absent
  `X-Scopes` means *either* case — consumers must never treat its absence as
  an authorization grant. There is still **no per-API/per-operation scope
  enforcement** — an empty intersection is not a `403`.

**Implemented so far (Phase 24, Slice 9) — safe subscription tier deletion:**
an `ADMIN` can remove an unreferenced tier with
`DELETE /subscription-tiers/{tierId}`. The endpoint returns `204 No Content`
with an empty body on success. A missing tier returns `404
SUBSCRIPTION_TIER_NOT_FOUND`; a `DEVELOPER` receives `403 ACCESS_DENIED`; and an
unauthenticated caller receives `401 UNAUTHENTICATED`. The service checks for
subscriptions before deleting and maps both the application-level reference
check and the PostgreSQL foreign-key race backstop to `409
SUBSCRIPTION_TIER_IN_USE`. The `subscriptions.tier_id` foreign key is `NOT
NULL` and has no cascade, so subscriptions are never implicitly reassigned or
 deleted. Explicit ADMIN tier reassignment is implemented in Phase 24 Slice 10.
PostgreSQL remains the source of truth. A successful transaction evicts only
`subscriptionTier::<tierId>` after commit; rejected or rolled-back deletes do
not evict.

**Implemented so far (Phase 24, Slice 10) — subscription tier reassignment:** an
authenticated `ADMIN` can change a subscription's tier with
`PATCH /subscriptions/{subscriptionId}/tier` and body
`{ "tierId": long }` (`tierId` is required). A successful change returns `200 OK`
with the normal subscription response and changes only the tier reference; the
subscription's application, API version, lifecycle status, and `revokedAt` are
preserved. A missing subscription returns `404
APPLICATION_SUBSCRIPTION_NOT_FOUND`; a missing target tier returns `404
SUBSCRIPTION_TIER_NOT_FOUND`; invalid input returns `400 VALIDATION_FAILED`; a
`DEVELOPER` receives `403 ACCESS_DENIED`; and an unauthenticated caller receives
`401 UNAUTHENTICATED`. Reassigning to the current tier is idempotent: the
subscription is not saved and its `updatedAt` timestamp is unchanged. The
operation is transactional and does not evict Redis cache entries; PostgreSQL
remains the source of truth, and the target tier's cached policy is unaffected.
No subscription is created, deleted, or implicitly cascaded by this operation.

**Implemented so far (Phase 12) — subscriptions:** an application can be
**subscribed** to an **API version** in the API Management Service. This
implements the Application → Subscription → API Version link. The request body
carries `applicationId`, `apiVersionId`, and `tierId`; the **owner is always
derived from the authenticated user's JWT `sub` claim** and is never taken from
the request body.
- `POST /subscriptions` — subscribe an owned application to an API version,
  binding it to a subscription tier. Body: `{ "applicationId": long,
  "apiVersionId": long, "tierId": long }` (all required). Returns `201 Created`
  with a `Location: /subscriptions/{id}` header and the subscription body (`id`,
  `applicationId`, `apiVersionId`, `tierId`, `tierName`, `status`, `revokedAt`,
  `createdAt`, `updatedAt`). New subscriptions are created with `status =
  "PENDING"` and a null `revokedAt`.
- `PATCH /subscriptions/{subscriptionId}/tier` — an `ADMIN` can reassign the
  subscription to another existing tier. Body: `{ "tierId": long }`; the
  response is the normal subscription body. The operation preserves status,
  `revokedAt`, application, and API-version relationships; see the Slice 10
  details above.
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
  non-existent `apiVersionId` returns `404 API_VERSION_NOT_FOUND`; a
  non-existent `tierId` returns `404 SUBSCRIPTION_TIER_NOT_FOUND`. Application,
  version, and tier are all checked before the subscription is created.
- Duplicate subscriptions (same application + same API version) → `409
  SUBSCRIPTION_ALREADY_EXISTS`. Enforced in the service up front **and** by a
  database unique constraint on `(application_id, api_version_id)`; the
  resulting `DataIntegrityViolationException` from a race condition is mapped to
  the same `409` response. Two different applications may subscribe to the same
  API version, and one application may subscribe to multiple versions.
- Validation: `applicationId`, `apiVersionId`, and `tierId` are required
  (`@NotNull`); violations return `400 VALIDATION_FAILED`, malformed JSON
  returns `400 MALFORMED_REQUEST`.
- A subscription carries a **tier reference** (Phase 24): tier id + name are
  returned and the `tier_id` column is a `NOT NULL` foreign key to the
  `subscription_tiers` table. It carries **no** credentials (API key / client
  id+secret). Each tier now carries its rate-limit policy
  (`requestsPerWindow`/`windowSeconds`, Phase 24 Slice 4) that the gateway
  enforces per active subscription; Slice 8 adds ADMIN-only partial tier
  updates, Slice 9 adds safe deletion of unreferenced tiers, and Slice 10 adds
  explicit ADMIN tier reassignment for existing subscriptions; tier
  creation/listing (Slice 13), the ACTIVE/INACTIVE tier status lifecycle
  (Slice 14), and tier pricing (`priceCentsPerMonth`, integer cents per month,
  default `0`, Slice 15) are implemented, while billing/burst/hard limits
  remain planned.
- **Subscription lifecycle/status (Phase 24, Slice 2):** every subscription has
  a `status` (`PENDING`, `ACTIVE`, `DENIED`, `REVOKED`) persisted as a string
  and a nullable `revokedAt` timestamp (set when a subscription is revoked).
  Status is managed through an **ADMIN-only** endpoint:
  - `PATCH /subscriptions/{subscriptionId}/status` with body
    `{ "status": "ACTIVE" }`. Allowed transitions: `PENDING → ACTIVE | DENIED |
    REVOKED`, `ACTIVE → REVOKED`, `DENIED → ACTIVE | REVOKED`; `REVOKED` is
    terminal. A disallowed transition → `409
    INVALID_SUBSCRIPTION_STATUS_TRANSITION`; a missing subscription → `404
    APPLICATION_SUBSCRIPTION_NOT_FOUND`; a non-`ADMIN` caller → `403`; an
    absent `status` field or an invalid enum value → `400 VALIDATION_FAILED`.
    The `ADMIN` lifecycle operation is a global management action (it may
    approve/deny/revoke **any** existing subscription), unlike the read/create
    paths which remain owner-scoped. The response is the normal subscription
    body including the updated `status` and `revokedAt`.
  - **Runtime effect:** only `ACTIVE` subscriptions satisfy the internal
    `GET /internal/subscription-check` (Phase 17) and
    `GET /internal/credential-check` (Phase 21) checks — `PENDING`, `DENIED`,
    and `REVOKED` subscriptions no longer count for gateway enforcement. The
    gateway itself is unchanged; the API Management Service remains the source
    of truth.

**Implemented so far (Phase 13; credential expiry in Phase 24, Slice 11;
expiration update in Phase 24, Slice 12) —
credentials:** an **application** can hold **credentials** (`clientId` + hashed
`clientSecret`) in the API Management Service. These are the credentials the API
Gateway uses to authenticate an application. The existing creation body carries
`applicationId`; the application-scoped route carries the same application id in
its path. The **owner is always derived from the authenticated user's JWT `sub`
claim** and is never taken from the request body.
- `POST /credentials` — create a credential for an owned application. Body:
  `{ "applicationId": long, "expiresAt": ISO-8601 Instant?, "scopes"?:
  string[] }`. `applicationId` is
  required; `expiresAt` is optional, and omitted/`null` means the credential does
  not expire. A supplied expiry must be in the future. `scopes` is optional
  (omitted/`null` → unrestricted `""`; see the scope grammar above). `clientId` and
  `clientSecret` are generated **server-side** and are not accepted from the
  client (extra body fields are ignored). Returns `201 Created` with a
  `Location: /credentials/{id}` header and body (`id`, `applicationId`,
  `clientId`, `clientSecret`, `status`, `expiresAt`, `scopes`, `createdAt`, `updatedAt`).
  The plaintext `clientSecret` is returned **exactly once**, in this creation
  response only. New credentials start with `status: "ACTIVE"`.
- `POST /applications/{applicationId}/credentials` — the application-scoped
  creation route. Body: `{ "expiresAt": ISO-8601 Instant?, "scopes"?:
  string[] }`; the application id
  comes from the path. It follows the same generation, validation, one-time
  secret, and `201` response contract as `POST /credentials`.
- `GET /credentials/{credentialId}` — the caller's **own** credential
  (applications the caller owns). Returns `CredentialResponse` (`id`,
  `applicationId`, `clientId`, `status`, `expiresAt`, `scopes`, `createdAt`, `updatedAt`)
  — **never** the `clientSecret` or `clientSecretHash`.
- `GET /credentials` — list the caller's **own** credentials in ascending `id`
  order (owner-scoped; other users' credentials never appear, no secrets).
- `PATCH /credentials/{credentialId}/status` — **ADMIN-only** credential
  lifecycle management (a global management operation keyed by credential id,
  mirroring the subscription lifecycle endpoint — **not** owner-scoped). Body:
  `{ "status": "REVOKED" }`. Allowed transition: `ACTIVE → REVOKED`.
  Re-applying the current state (`ACTIVE → ACTIVE`, `REVOKED → REVOKED`) and
  any restore (`REVOKED → ACTIVE`) return `409
  INVALID_CREDENTIAL_STATUS_TRANSITION`; a missing credential → `404
  CREDENTIAL_NOT_FOUND`; a missing/invalid `status` → `400 VALIDATION_FAILED`
  (`fieldErrors.status`). `REVOKED` is terminal. Response is the same
  `CredentialResponse` (status updated, never a secret).
- `POST /credentials/{credentialId}/rotate` — **ADMIN-only** credential
  rotation (global, not owner-scoped). The existing credential row is updated
  **in place**: a new server-side `clientId` and a new `SecureRandom`
  256-bit `clientSecret` are generated, only the new BCrypt hash is stored, the
  status stays `ACTIVE`, the existing `expiresAt` is preserved (rotation never
  extends the lifetime), and the response returns the **new** `clientId` and
  plaintext `clientSecret` **exactly once** (same shape as creation, plus
  `status` and `expiresAt`). The old `clientId`/`clientSecret` stop
  authenticating immediately.
  A `REVOKED` credential cannot be rotated → `409
  INVALID_CREDENTIAL_STATUS_TRANSITION`; a missing credential → `404
  CREDENTIAL_NOT_FOUND`. `clientId` collision retries reuse the bounded
  creation policy (never an unbounded loop).
- `PATCH /credentials/{credentialId}/expiration` — **ADMIN-only** credential
  expiration update (Phase 24, Slice 12; a global management operation keyed by
  credential id, not owner-scoped). Body: `{ "expiresAt": ISO-8601 Instant }`;
  `expiresAt` is required and must be strictly in the future. The update
  changes **only** the credential's `expiresAt` (and `updatedAt`) — the
  `clientId` and `clientSecret`/hash are **not rotated** (the existing pair
  keeps authenticating) and the `status` is untouched. Response is the same
  `CredentialResponse` (`id`, `applicationId`, `clientId`, `status`,
  `expiresAt`, `createdAt`, `updatedAt`) — **never** the `clientSecret` or
  `clientSecretHash`. A `REVOKED` credential remains `REVOKED` (an expiration
  update never reactivates it), while an expired `ACTIVE` credential whose
  expiration is extended into the future authenticates again (the internal
  credential check requires `ACTIVE` and, when set, an expiration strictly in
  the future). A missing credential → `404 CREDENTIAL_NOT_FOUND`; a
  missing/non-future `expiresAt` → `400 VALIDATION_FAILED` (`fieldErrors.expiresAt`);
  an unparseable `expiresAt` or malformed JSON → `400 MALFORMED_REQUEST`; a
  `DEVELOPER` → `403 ACCESS_DENIED`; an unauthenticated caller → `401
  UNAUTHENTICATED`.
- `PATCH /credentials/{credentialId}/scopes` — **ADMIN-only** credential scope
  update (Phase 24, Slice 16; global management operation keyed by credential
  id, not owner-scoped). Body: `{ "scopes": string[] }` — `scopes` is required
  and a `null`/missing value returns `400 VALIDATION_FAILED`
  (`fieldErrors.scopes`); the grammar is the scope grammar above. The update
  changes **only** `scopes` (and `updatedAt`) — no rotation, `status`
  untouched — and a `REVOKED` credential is updated without being
  reactivated. Response is the same `CredentialResponse` with the new
  canonical `scopes`. A missing credential → `404 CREDENTIAL_NOT_FOUND`; a
  `DEVELOPER` → `403 ACCESS_DENIED`; an unauthenticated caller → `401
  UNAUTHENTICATED` (unparseable/malformed JSON → `400 MALFORMED_REQUEST`).
- Authorization is uniform for `ADMIN` and `DEVELOPER` with owner-based
  semantics, exactly like applications/subscriptions: `ADMIN` owns what it
  creates and has **no** global access for reads — but **lifecycle management
  (PATCH status, PATCH expiration, PATCH scopes, rotate) is an ADMIN-only
  global operation**
  by credential id, exactly like the subscription lifecycle endpoint.
  Credential ownership is derived through Credential → Application →
  ownerUserId. Accessing a credential that does not exist **or belongs to an
  application the caller does not own** returns `404 CREDENTIAL_NOT_FOUND`
  (no existence leak). Creating a credential against an application the caller
  does not own (or that does not exist) returns `404 APPLICATION_NOT_FOUND`.
- `clientId` uniqueness: generated as a random UUID, unique per credential. A
  service-level existence check plus a database unique constraint on
  `client_id`; if a collision somehow occurs, the service regenerates and
  retries rather than surfacing a database error. Rotation (same row, new id)
  uses the identical bounded policy.
- Secret hashing: the `clientSecret` is hashed with BCrypt (the same
  `PasswordEncoder` infrastructure as user passwords) and **only the hash** is
  stored. The plaintext secret cannot be retrieved after creation or rotation.
- Validation: `applicationId` is required for creation (`@NotNull`); an optional
  `expiresAt` must be a future `Instant`; `status` is required for the status
  endpoint (`@NotNull`) and must be a valid `CredentialStatus` value. Violations
  return `400 VALIDATION_FAILED`, malformed JSON or an unparseable timestamp
  returns `400 MALFORMED_REQUEST`.
- Credential **status/revocation/rotation** are implemented (Phase 24, Slice 3),
  optional **credential expiry** is implemented (Phase 24, Slice 11), the
  **ADMIN-only expiration update** is implemented (Phase 24, Slice 12), and
  **credential scopes with the ADMIN-only scope update** are implemented
  (Phase 24, Slice 16). Per-operation scope **enforcement** and permissions
  remain planned (rate limits are enforced per
  subscription tier, not per credential; effective scopes are surfaced
  informationally to downstream services via `X-Scopes`). The runtime consumer is the internal
  `GET /internal/credential-check` (see the Gateway section), which authenticates
  only `ACTIVE` credentials with `expiresAt` absent or strictly in the future.

### Subscription Service

> `POST /applications` and `GET /applications` are **implemented** in the API
> Management Service (Phase 11), the Application → Subscription → API Version
> link (`POST /subscriptions`, `GET /subscriptions/{id}`, `GET /subscriptions`)
> in Phase 12, and application **credentials** (`POST /credentials`,
> `POST /applications/{id}/credentials`, `GET /credentials/{id}`,
> `GET /credentials`) in Phases 13 and 24 Slice 11 — see the sections above.
> The subscription schema carries a **tier reference** (`subscription_tiers`,
> Phase 24) and a **status/lifecycle state machine** (`PATCH
> /subscriptions/{id}/status`, Phase 24 Slice 2; see the subscriptions section
> above). An `ADMIN` can also explicitly reassign an existing subscription's
> tier with `PATCH /subscriptions/{id}/tier` (Phase 24 Slice 10). Application
> credentials carry a **status lifecycle with revocation and rotation**, an
> optional **expiry** (`expiresAt`), and **scopes** (Phase 24 Slice 16, with
> the ADMIN-only `PATCH /credentials/{id}/scopes`), all enforced by the internal
> credential check; secret exposure remains one-time. Subscription deletion
> remains planned.
> They are repeated here as the planned contract for the gateway/portal view.
- `POST   /applications/{id}/credentials` — (implemented; optional `expiresAt`
  and `scopes`, secret shown once).
- `POST   /applications/{id}/subscriptions` — (planned) subscribe app to API
  version + tier; the tiered link already exists via Phase 12 `POST
  /subscriptions` and the Phase 24 tier binding.
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
- Public query (Phase 23, Slice 5):
  `GET /analytics/events?apiContext=...&apiVersion=...&authenticationType=...&statusCode=...&from=...&to=...&page=0&size=20` —
  requires an `ADMIN` or `DEVELOPER` JWT; an anonymous, malformed, or invalid
  token is answered `401 UNAUTHENTICATED` and a valid token whose role is not
  `ADMIN`/`DEVELOPER` is answered `403 ACCESS_DENIED` (each via the platform
  error shape, without disclosing why or echoing token material). All
  parameters are optional: `apiContext`, `apiVersion`, and `authenticationType`
  (`JWT`/`CLIENT_CREDENTIAL`) are exact matches, `statusCode` is an exact match
  (100–599), `from`/`to` are inclusive `Instant` bounds (ISO-8601; `from` after
  `to` is rejected), and `page ≥ 0`, `1 ≤ size ≤ 100` (default `0`/`20`). Any
  invalid value → `400 INVALID_REQUEST_PARAMETER`. Returns
  `{ "items": [...], "page", "size", "totalElements", "totalPages", "last" }`
  ordered newest-first (`event_timestamp DESC, id DESC`). Each `item` exposes
  only `id`, `timestamp`, `apiContext`, `apiVersion`, `httpMethod`,
  `statusCode`, `latencyMs`, `authenticationType`, `userId`, `applicationId` —
  never credentials, tokens, headers, or bodies. Filtering, sorting, and
  pagination are executed in PostgreSQL (JpaSpecificationExecutor + Pageable);
  no rows are filtered or paged in memory.
- Usage summary (Phase 23, Slice 6):
  `GET /analytics/usage?from=...&to=...&apiContext=...&apiVersion=...` — same
  `ADMIN`/`DEVELOPER` JWT protection and the same 401/403 error shape as
  `/analytics/events`. All parameters optional; `from`/`to` are inclusive
  ISO-8601 `Instant` bounds (from after `to` → `400 INVALID_REQUEST_PARAMETER`,
  malformed `Instant` → `400`), `apiContext`/`apiVersion` are exact matches.
  Returns a single-row summary computed entirely in PostgreSQL (one aggregate
  `SELECT`, no `GROUP BY`, no rows loaded into Java):
  `{ "totalRequests": long, "successfulRequests": long, "clientErrorRequests": long,
  "serverErrorRequests": long, "averageLatencyMs": double }`, where
  `successful/clientError/serverError` are 2xx/4xx/5xx counts. An empty or
  fully filtered-out window returns `200` with zero counts and
  `averageLatencyMs: 0.0` (handled by `COALESCE(AVG(latency_ms), 0)`).
- Planned (later): `GET /analytics/applications/{appId}/usage?since=...&until=...` —
  counts per API/status/latency buckets for the Developer Portal.

## Why These Choices (interview-ready)

- URI versioning is explicit, cache-friendly, and trivially routable by the
  gateway.
- Uniform error shape + stable codes makes client handling predictable.
- DTOs at boundaries protect domain models from API coupling and allow the API
  contract to evolve independently.
- Do-not-leak-404 mapping for unowned resources avoids confirming existence of
  other users' resources.
- Centralized error handling keeps controllers thin per `AGENTS.md`.