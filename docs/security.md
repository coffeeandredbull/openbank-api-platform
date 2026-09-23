# Security Design

> This document describes the security model of the OpenBank API Platform.
> **Implemented** functionality is described alongside the **planned** design;
> the section "Planned vs. Implemented" distinguishes the two. No real
> credentials, hashes, or secrets exist in this repository, and none will be
> committed at any point.

## Authentication Model

- **Actors:** end users (via the Developer Portal) and API consumers
  (applications calling APIs directly).
- **Primary mechanism:** a **JWT access token** issued by the **Identity
  Service** after a credential exchange (user login, or an OAuth2-style flow
  for applications).
- The **API Gateway** is the single validation point: it validates the JWT on
  every inbound request before routing to a backend service.
- Token contents (claims): subject (user id), roles, scopes, application /
  client id, issuer, audience, expiry, issued-at.
- **Refresh tokens** (server-side, stored hashed in Redis/Identity DB) provide
  renewal without re-authentication and centralize revocation.
- Public endpoints are only the minimal discovery set (e.g. catalog browsing,
  health); everything else requires authentication. Security fails **closed**.

## JWT Concept

- JWTs are signed (HS256/RS256 decision pending in the Identity phase) so the
  gateway and services can verify authenticity without a DB lookup.
- The gateway verifies **signature, signature algorithm whitelist, expiry,
  issuer, and audience** before extracting identity.
- JWTs carry roles and scopes for authorization decisions without extra
  round-trips.
- **Revocation**: because stateless JWTs cannot be recalled, revocation is
  supported via a Redis-backed deny-list of token IDs (or short-lived access
  tokens with refresh flow). Design intent — implementation in Identity phase.

## Access Token — Implemented (Phase 6)

The Identity Service issues a signed **JWT access token** on successful
`POST /auth/login`. This is the foundation for gateway/service validation; no
request is authenticated with it yet (see "Planned vs. Implemented").

Implemented behavior:

- **Algorithm:** HS256 (HMAC-SHA256), symmetric signing key.
- **Signing secret:** configured via the `JWT_SECRET` environment variable,
  with no default. The service fails to start if the secret is missing, blank,
  or shorter than 256 bits — it never generates a random secret silently. The
  secret is never logged, and never returned in any API response.
- **Expiration:** configured via `JWT_EXPIRATION_SECONDS`
  (default `3600` = 1 hour), matching the `expiresIn` value in the login
  response.
- **Claims:** `sub` (user ID, decimal string), `role` (e.g. `DEVELOPER`,
  `ADMIN`), `iat`, `exp`. No password, password hash, or secret is ever placed
  in a token.
- **Validation:** a token is accepted only if it parses, has a valid signature
  under the configured secret, is not expired, and carries the required `sub`
  and `role` claims. Failures raise a generic application-level error without
  exposing cryptographic details.
- **Login response:** `POST /auth/login` returns
  `accessToken`, `tokenType` (`Bearer`), `expiresIn`, `userId`, `email`,
  `role`. Failed logins (unknown email or wrong password) continue to return
  `401 AUTHENTICATION_FAILED` and no token.

Not implemented yet (future phases): a Spring Security filter chain or gateway
filter that consumes these tokens, RBAC/scopes enforcement, refresh tokens, and
revocation.

## Request Authentication & RBAC — Implemented (Phase 7)

The Identity Service now authenticates requests using Spring Security bearer
tokens and enforces role-based authorization on a small set of verification
endpoints. This is the foundation for later gateway-level enforcement.

Implemented behavior:

- **Header format:** `Authorization: Bearer <jwt>`. Other schemes (`Basic`,
  arbitrary strings) and malformed values (`Bearer`, `Bearer `) do not
  authenticate and fail closed with `401`.
- **Flow:** a request filter reads the bearer header, validates the JWT with
  the existing `JwtTokenService` (signature, expiry, required claims), and
  places an authenticated principal (`userId` + `role`) into the Spring
  Security context. No database lookup is performed per request — the signed
  claims are the source of truth for this phase.
- **Stateless:** session management is `STATELESS`; every protected request is
  authenticated from its bearer token. There are no server-side sessions.
  CSRF is disabled because this is a stateless bearer-token API (no cookies are
  used for authentication).
- **Roles:** `ADMIN` and `DEVELOPER` map to Spring Security authorities
  `ROLE_ADMIN` and `ROLE_DEVELOPER` (standard `hasRole` semantics). Roles come
  from the JWT `role` claim, never from a hardcoded user.
- **Public endpoints:** `POST /users` and `POST /auth/login` remain public.
  Everything else remains accessible as before; only the verification
  endpoints below are protected.
- **Temporary RBAC verification endpoints** (development only, not business
  functionality):
  - `GET /test/authenticated` — requires any authenticated role
    (`ADMIN` or `DEVELOPER`).
  - `GET /test/admin` — requires `ADMIN`.
- **401 vs 403:** missing/invalid/expired credentials → `401` with code
  `UNAUTHENTICATED`; authenticated but insufficient role → `403` with code
  `ACCESS_DENIED`. Both use the standard error response shape and never leak
  framework internals, stack traces, JWTs, or secrets.

Not implemented yet (future phases): authentication at the API Gateway
(implemented later — Phase 16), subscription enforcement (implemented later —
Phase 17), and scopes.

## Shared JWT Validation — Implemented (Phase 8)

The API Management Service validates the same identity-service-issued JWT
locally, using the identical secret configuration approach. No login,
registration, or user database exists in that service — it only verifies tokens
signed by the platform secret.

Implemented behavior:

- **Configuration:** `JWT_SECRET` environment variable (no default; fail-fast
  if missing or shorter than 256 bits). It must be the same secret the Identity
  Service uses to sign tokens.
- **Validation:** the service's `JwtTokenService` reuses the semantics of the
  Identity Service validator — HMAC signature check, expiry, and the required
  `sub` and `role` claims. Validation is purely local; no remote call to the
  Identity Service is made per request.
- **Authorization:** `POST /apis` is `ADMIN`-only; `GET /apis` and
  `GET /apis/{id}` allow `ADMIN` or `DEVELOPER`. Missing/invalid/expired
  credentials → `401 UNAUTHENTICATED`; insufficient role → `403 ACCESS_DENIED`,
  using the same structured error shape as the Identity Service.
- **No new authentication architecture:** stateless bearer JWT, CSRF disabled,
  no sessions, no form login, no HTTP Basic, no refresh tokens, no API keys.

Not implemented yet (future phases): RS256, subscription tiers/status, and
credential lifecycle. (Gateway-level user authentication and subscription
checks are implemented later — Phases 16 and 17 — and gateway-level application
client-credential checks in Phase 21; see below.)

## Application Ownership — Implemented (Phase 11)

The API Management Service's **applications** endpoints enforce per-user
ownership of developer applications.

Implemented behavior:

- **Owner derivation:** the `ownerUserId` of an application always comes from
  the validated JWT `sub` claim (a `Long` user ID bounded to `roles` from the
  token). The request body cannot set the owner — a supplied `ownerUserId` in
  the payload is ignored.
- **Owner-scoped access:** `GET /applications`, `GET /applications/{id}`,
  `PATCH /applications/{id}` operate only on applications owned by the caller
  (repository lookup keyed by `id` **and** `ownerUserId`). A resource that does
  not exist **or belongs to another user** returns `404 APPLICATION_NOT_FOUND`
  exactly as if it did not exist — this avoids confirming other users' resources.
- **Roles:** both `ADMIN` and `DEVELOPER` bearer tokens are accepted, with
  uniform owner semantics. An `ADMIN` owns what it creates; it has **no**
  implicit access to other users' applications. Insufficient/unknown roles fail
  closed with `401 UNAUTHENTICATED`; missing/invalid/expired tokens also map to
  `401 UNAUTHENTICATED`. There is no `DELETE` endpoint in this phase.
- **Defense in depth:** authorization lives in the service layer (and DB lookup
  keys), not in the route matcher; the security filter only authenticates.

Not implemented yet (future phases): subscription-level checks and
profile/scope enforcement. Application credentials are **implemented** (Phase 13
— see below).

## Subscription Ownership — Implemented (Phase 12)

The API Management Service's **subscriptions** endpoints (the Application →
Subscription → API Version link) enforce the same per-user ownership model as
applications.

Implemented behavior:

- **Owner derivation:** a subscription is never created against an arbitrary
  application. The request body carries only `applicationId` and `apiVersionId`;
  the service looks up the application keyed by `id` **and** the authenticated
  caller's JWT `sub` (`findByIdAndOwnerUserId`) before creating anything. A
  non-existent or cross-owner `applicationId` returns `404 APPLICATION_NOT_FOUND`
  — indistinguishable from a missing application, so resource existence is not
  leaked.
- **Owner-scoped access:** `GET /subscriptions`, `GET /subscriptions/{id}`
  operate only on subscriptions whose **application** belongs to the caller
  (repository lookup keyed by subscription `id` **and** the application's
  `ownerUserId`). A subscription that does not exist **or belongs to another
  user's application** returns `404 APPLICATION_SUBSCRIPTION_NOT_FOUND` exactly
  as if it did not exist.
- **Roles:** both `ADMIN` and `DEVELOPER` bearer tokens are accepted with
  uniform owner semantics. An `ADMIN` owns what it creates and has **no**
  global access to other users' subscriptions. Unknown/insufficient roles fail
  closed with `401 UNAUTHENTICATED`; missing/invalid/expired tokens also map to
  `401 UNAUTHENTICATED`, with no application stack trace or token material in
  the response. There is no `DELETE`/`PATCH` endpoint in this phase.
- **Duplicate prevention:** a subscription is unique per
  (application, API version). The service pre-checks
  `existsByApplicationIdAndApiVersionId` **and** a DB unique constraint backs it
  up; a race condition surfacing as `DataIntegrityViolationException` is mapped
  to the same `409 SUBSCRIPTION_ALREADY_EXISTS`, never exposed as a constraint
  violation.
- **Defense in depth:** authorization lives in the service layer and DB lookup
  keys, not in the route matcher; the security filter only authenticates.

Not implemented yet (future phases): subscription-specific credentials (the
OAuth2-style client registry in the Identity Service), tiers, status/lifecycle,
revocation, and gateway-level subscription enforcement.

## Credential Ownership & Disclosure — Implemented (Phase 13)

The API Management Service issues **application credentials** (`clientId` +
`clientSecret`, the future gateway authentication material) and stores
**only** a BCrypt hash of the secret.

Implemented behavior:

- **Server-side generation:** the client never supplies `clientId` or
  `clientSecret`. `clientId` is a fresh random UUID per credential; the plaintext
  `clientSecret` is 32 bytes from `SecureRandom`, base64url-encoded (256 bits of
  entropy). Exhaustive guessability analysis:
  - 256-bit entropy — brute-force is infeasible regardless of hashing.
  - `SecureRandom` on Windows/Java 21 uses `/dev/urandom`-equivalent
    CSPRNG seeding (`NativePRNG`/`DRBG`); no `Random` (predictable) or
    `ThreadLocalRandom` (not fork-safe) anywhere in credential generation.
- **Hashing & storage:** the secret is hashed with the same `PasswordEncoder`
  (BCrypt) infrastructure as user passwords. The DB stores
  `client_secret_hash` only — the plaintext secret exists in memory only
  between generation and hashing, is returned in the `201 Created` body
  **exactly once**, and is never persisted, logged, or retrievable afterward.
  `CredentialResponse` (used by the two GET endpoints) structurally cannot
  contain a secret (it has no secret fields; a unit test asserts the serialized
  JSON contains neither `clientSecret` nor `clientSecretHash`).
- **Owner derivation:** ownership is never client-supplied. Credential →
  Application → `ownerUserId` — the application is looked up keyed by `id`
  **and** the authenticated caller's JWT `sub` before issuing
  (`findByIdAndOwnerUserId`). A non-existent or cross-owner `applicationId`
  returns `404 APPLICATION_NOT_FOUND` (no existence leak).
- **Owner-scoped access:** `GET /credentials`, `GET /credentials/{id}`
  operate only on credentials whose **application** belongs to the caller
  (repository lookups keyed by credential `id`/owner and application owner,
  respectively). A credential that does not exist **or belongs to another
  user's application** returns `404 CREDENTIAL_NOT_FOUND` exactly as if it did
  not exist (no existence leak).
- **Roles:** both `ADMIN` and `DEVELOPER` bearer tokens are accepted with
  uniform owner semantics. An `ADMIN` owns what it creates and has **no**
  global access to other users' credentials. Unknown/insufficient roles fail
  closed with `401 UNAUTHENTICATED`; missing/invalid/expired tokens also map to
  `401 UNAUTHENTICATED`. These early 401s never include tokens, stack traces, or
  secrets.
- **clientId uniqueness:** enforced in the service (existence pre-check against
  `existsByClientId`) **and** by a DB unique constraint (`uc_credential_client_id`)
  — defense in depth against race conditions; a collision triggers regeneration
  and a retry rather than a sensitive constraint violation leaking into a
  response. `DataIntegrityViolationException` during save is caught and retried
  (bounded retry loop); a persistent failure surfaces as a generic `500`.
- **Defense in depth:** authorization lives in the service layer and DB lookup
  keys, not in the route matcher; the security filter only authenticates.
  Error responses (all 4xx, including 500) never expose `client_secret_hash`,
  SQL, constraint names, or the plaintext secret.

Not implemented yet (future phases): credential rotation, revocation, status,
expiry, scopes, gateway-side verification/authentication of these credentials,
and profile/scope enforcement.

## Account/Payment/Transaction Ownership — Implemented (Phase 14)

The **Payment Service** (Account, Payment, and Transaction domains) validates
the same identity-service-issued JWT locally and enforces per-user financial
ownership.

Implemented behavior:

- **Configuration & validation:** identical to Phase 8 — `JWT_SECRET`
  environment variable (no default; fail-fast if missing or shorter than 256
  bits), HS256, and the same `JwtTokenService` semantics (signature, expiry,
  required `sub` + `role` claims). Validation is purely local; no remote call to
  the Identity Service per request. The service keeps **no user rows** — it
  references users by `ownerUserId` (a logical ID from the JWT `sub`) only.
- **Fail closed by default:** all nine endpoints (`POST/GET /accounts`,
  `GET /accounts/{id}`, `POST/GET /payments`, `GET /payments/{id}`,
  `POST/GET /transactions`, `GET /transactions/{id}`) require an `ADMIN` or
  `DEVELOPER` bearer token; the security config permits nothing else
  (`.anyRequest().denyAll()`). There are **no public endpoints** in this
  service. Missing/malformed/expired/tampered tokens → `401 UNAUTHENTICATED`.
- **Unknown roles fail closed to `401`:** a token whose `role` claim is not a
  known role (`ADMIN`/`DEVELOPER`) is rejected as unauthenticated — it never
  grants access and never reaches the endpoints (a future `CUSTOMER` role gets
  `401`, not a partial allowance).
- **Owner derivation:** an account, payment, or transaction is always created
  against the authenticated caller's identity. The request body never supplies
  an owner; a client-supplied `ownerUserId` is ignored. Account creation is keyed
  to `sub` and one account per user is enforced by a DB unique constraint on
  `owner_user_id`.
- **Owner-scoped access (404, no existence leak):** every read and every
  create-time parent lookup is keyed by `id` **and** the caller's `ownerUserId`
  (`findByIdAndOwnerUserId`, `findByIdAndAccount_OwnerUserId`). A resource that
  does not exist **or belongs to another user** returns the same `404` (with
  codes `ACCOUNT_NOT_FOUND`, `PAYMENT_NOT_FOUND`, `TRANSACTION_NOT_FOUND`) — it
  is impossible to distinguish "not yours" from "missing", so other users'
  resources are never confirmed.
- **Cross-resource consistency without leaks:** creating a transaction requires
  (1) an account the caller owns (`ACCOUNT_NOT_FOUND` otherwise) and (2) a
  payment whose `account_id` exactly matches that account (`PAYMENT_NOT_FOUND`
  otherwise) — a payment on any other account, or that does not exist, is
  indistinguishable.
- **Server-derived financial fields:** payment `status` always starts `PENDING`
  and payment `currency` always comes from the account; transaction `type` is
  always `PAYMENT` and its currency always the account's. The client cannot set
  status, type, or currency (extra body fields are ignored) — preventing
  forged "completed" payments or currency confusion.
- **Roles:** both `ADMIN` and `DEVELOPER` bearer tokens are accepted with
  uniform owner semantics. An `ADMIN` owns what it creates and has **no** global
  access to other users' accounts, payments, or transactions — the admin's list
  view is empty unless the admin created the resources.
- **No sensitive material:** there are no balances, no secrets, and no other
  users' records exposed anywhere. Error responses (4xx/5xx) never expose SQL,
  constraint names, stack traces, JWTs, or the signing secret.
- **Defense in depth:** authorization lives in the service layer and DB lookup
  keys, not in the route matcher; the security filter only authenticates.

Not implemented yet (future phases): balance authorization, real payment
processing/approval, subscription/scope enforcement, and gateway integration.

## Gateway Foundations — Implemented (Phases 15–22)

The `gateway-service` (Spring Cloud Gateway, port `8080`) is a routing gateway
that authenticates callers (user JWTs since Phase 16; application client
credentials since Phase 21), enforces subscriptions for managed API invocations
(Phases 17 and 21), and applies Redis-backed rate limiting (Phase 21 completes
the application side). It remains deliberately thin: routing, authentication,
subscription enforcement, and rate limiting are implemented; application
credentials are verified against the API Management Service's credential
registry, never stored or copied by the gateway.

Implemented behavior:

- **Gateway JWT authentication (Phase 16):** a global filter runs on every
  request before routing. Only three paths are public — `POST /users`,
  `POST /auth/login`, and `GET /actuator/health` (method-sensitive: e.g.
  `GET /users/me`, `GET/DELETE /users/*`, and any other `POST /auth/*` are
  protected). All other routed paths require an `Authorization: Bearer <jwt>`:
  - The token must parse as an HS256 JWT whose signature verifies against the
    shared `JWT_SECRET` environment variable (the same value as the backends'
    `app.jwt.secret`, minimum 256 bits; the gateway fails fast on startup if
    it is missing or too short — no insecure default secret).
  - It must be unexpired and carry mandatory `sub` (numeric user id) and
    `role` (`ADMIN` or `DEVELOPER`) claims.
  - Any failure (missing header, wrong scheme, unparseable, bad signature,
    expired, missing/invalid claims) returns the same generic `401` with code
    `UNAUTHENTICATED` and message `Authentication is required`. The gateway
    **never discloses which check failed** — it leaks no token contents,
    exception classes, or parsing internals, and rejecting it never exposes
    the token or secret.
- **Gateway subscription enforcement (Phase 17):** after authentication,
  requests to `/runtime/apis/**` (the managed-API invocation route →
  `MANAGED_API_TARGET_URL`, default `http://localhost:8084`) are gated on an
  **active subscription** before forwarding:
  - **Identity is derived from the JWT only.** The caller's user id comes
    exclusively from the validated `sub` claim; a client-supplied `userId`
    query parameter or `X-User-Id` header is **ignored** (tests assert the
    check request carries only `contextPath` and `version`). There is no
    `ADMIN` bypass and no reliance on any unverified client-supplied identity.
  - **API version identification**: the requested version is parsed from the
    path (`/runtime/apis/{context}/{version}/...`); the check target is the
    version registered under that context path in the API Management Service.
    The check is intentionally lifecycle-agnostic: a subscription to the target
    API version satisfies the gate in any lifecycle state (CREATED, PUBLISHED,
    DEPRECATED, RETIRED). Lifecycle-based runtime blocking (PUBLISHED-only
    invocation, deprecation/retirement sunset rules) is out of scope for Phase 17
    and remains a later phase. A malformed runtime path (no version segment) is
    rejected with `403` without contacting the check service.
  - **The check itself authenticates and is not routable.** The gateway calls
    the API Management Service's internal
    `GET /internal/subscription-check` endpoint directly (never through a
    gateway route) and forwards the same `Authorization` header; the endpoint
    requires a valid bearer token (`.authenticated()`), returns only
    `{"subscribed": bool}`, and queries **PostgreSQL directly** — the system of
    record. No Redis and no caching are used for this decision.
  - **Fail closed on authorization and on failure.** Not subscribed (including
    a subscription owned by **another user's** application — the lookup is
    keyed by the target API version *and* the owning application's
    `ownerUserId`) → `403` + `SUBSCRIPTION_REQUIRED`; the check service
    unreachable, timed out (3 s response, 2 s connect), or returning a 5xx /
    malformed body → `503` + `SUBSCRIPTION_SERVICE_UNAVAILABLE`. An
    unverified or unsubscribed request is **never** forwarded. A `4xx` from
    the check is treated as a forbidden result, never as an excuse to bypass.
  - **Nothing sensitive leaks.** All 401/403/503 rejection bodies are generic
    (stable shape, no stack traces, no upstream URLs, no token material, no
    check responses). Gateway logs record only method/path/route id/
    status/duration for the check and forwarding decisions — never the
    `Authorization` header, JWT, body, check response, or internal endpoint.
- **Gateway application/client-credential authentication (Phase 21):** managed
  API invocations can be authenticated with the Phase 13 application
  credentials presented as `Authorization: Basic base64(clientId:clientSecret)`
  instead of a user JWT:
  - **Flow.** The gateway decodes the pair, derives the API version from the
    path (`/runtime/apis/{context}/{version}/...`), and calls the API
    Management Service's internal `GET /internal/credential-check` endpoint
    directly (never through a gateway route) with the **same Basic header**.
    That endpoint is self-authenticating (it is deliberately `permitAll()` in
    the service's security config — the Basic header, not a bearer token, is
    what proves identity) and verifies the credential server-side: it looks up
    the credential by `clientId` and runs BCrypt `matches` against the stored
    `client_secret_hash` — the plaintext secret and its hash are never read
    back, returned, forwarded, or logged. It returns, from a **direct
    PostgreSQL query** (the system of record), `{"authenticated":true,
    "applicationId":N,"ownerUserId":N,"subscribed":bool}`; all failures return
    `{"authenticated":false}`. The same single check returns the **subscription
    verdict** for the authenticated application, so no second network call is
    made.
  - **Outcomes (fail closed).** Unknown/malformed credentials → `401` + code
    `CLIENT_CREDENTIAL_INVALID`; the check is unreachable (2 s connect / 3 s
    response), returns 5xx, or a malformed/unparseable 2xx body → `503` + code
    `CREDENTIAL_SERVICE_UNAVAILABLE` (an unverified request is **never**
    forwarded); a malformed runtime path with Basic → `403` + code
    `SUBSCRIPTION_REQUIRED` (mirrors the JWT flow, no check call).
  - **Subscription is the application's own.** Valid credentials whose
    **authenticated application** is not subscribed to the target API version
    → `403` + code `SUBSCRIPTION_REQUIRED`. Identity is the application itself
    (`applicationId` + `ownerUserId` from the registry), never a user-derived
    value; there is no `ADMIN` bypass, and the JWT-subscription rules do not
    apply to this flow.
  - **The credentials never pass through.** After the check, the gateway
    **strips the `Authorization` header** (via a request decorator) before
    forwarding, so the consumed backend never sees the client secret. This is
    the opposite of the Bearer flow, where the validated JWT is forwarded
    unchanged as part of defense in depth.
  - **Bearer vs Basic.** A `Bearer` + `Basic` pair on a runtime route uses the
    client-credential flow (the JWT filter skips when client credentials are
    present). Platform-management routes (`/apis/**`, `/applications/**`, …)
    still require a Bearer JWT — client credentials there are not accepted and
    Basic on `/apis/**` yields the standard `401 UNAUTHENTICATED`.
- **Gateway-supplied trusted identity headers (Phase 22):** for managed API
  invocations (`/runtime/apis/**`) the gateway supplies the caller's **verified
  identity** to the consumed API as gateway-generated request headers, added
  only *after* successful authentication:
  - **JWT (user) flow** → `X-User-Id` (the JWT `sub` user id) and `X-Roles`
    (the JWT `role`, e.g. `ADMIN`/`DEVELOPER`); `Authorization: Bearer <jwt>`
    continues to be forwarded unchanged.
  - **Client-credential (application) flow** → `X-User-Id` (the credential's
    `ownerUserId`), `X-Application-Id` (the credential's `applicationId`), and
    `X-Client-Id` (the credential's `clientId`); **no `X-Roles`** is set, and
    the `Authorization: Basic ...` header remains stripped before forwarding.
  - **Never trusted from the client.** These headers are generated by the
    gateway exclusively; client-supplied `X-User-Id`, `X-Roles`,
    `X-Application-Id`, or `X-Client-Id` values are **stripped** from every
    inbound request by a reusable sanitizer (`TrustedIdentityHeaderSanitizer`)
    before trusted values are added, so a spoofed identity can never reach the
    managed API or influence its access decisions. Identity is always derived
    post-authentication — from the validated JWT claims or the credential-check
    response — never from request input (end-to-end tests assert a managed
    upstream receives only gateway-derived values for both flows).
  - **Runtime path only.** Headers are applied exclusively on `/runtime/apis/**`;
    platform-management routes (`/apis/**`, `/applications/**`, ...) receive
    none. The filter order is unchanged (`-210` client credentials → `-200` JWT
    → `-150` subscription → `-120` rate limit → `-100` global filter).
  - **Defense in depth unchanged.** The headers are enriched context for the
    consumed backend, not standalone trust: every backend still validates the
    JWT locally and re-checks ownership, so the gateway is not the single point
    of trust. The header names live in a single shared location
    (`TrustedIdentityHeaders`) so no later caller can introduce header-name
    drift.
- **Gateway rate limiting (Phase 21 — Redis-backed, per API version):** every
  `/runtime/apis/**` request is rate limited before routing, using Redis
  fixed-window counters keyed by the *resource being consumed*:
  `rate_limit:user:{userId}:{context}:{version}` for JWT callers and
  `rate_limit:app:{applicationId}:{context}:{version}` for client-credential
  callers (the application branch is added in Phase 21). The limit comes from
  `RATE_LIMIT_REQUESTS` (default `100`) over `RATE_LIMIT_WINDOW_SECONDS`
  (default `60`). Over-limit requests get `429` + code `RATE_LIMIT_EXCEEDED`
  with a `Retry-After` header; if Redis is unreachable or the counter cannot be
  read, the request is rejected with `503` + code `RATE_LIMIT_SERVICE_UNAVAILABLE`
  (**fail closed**). This is the **first business use of Redis** (Phase 18
  plumbed it; nothing consumed it until now).
- **Defense in depth is unchanged:** the gateway forwards the `Authorization`
  header **unchanged** for *Bearer* requests (every backend service still
  validates the JWT locally as in Phases 6–14). Removing or bypassing the
  gateway therefore does **not** remove user authentication. For
  *client-credential* requests the secret is stripped, and upstream backends
  never authenticate applications — the gateway is the sole enforcement point
  for that flow. Backend services run no Basic/application authentication.
- **Upstream failures fail closed with a generic error:** if an upstream is
  unreachable (e.g. connection refused) or exceeds the 2 s connect / 5 s
  response timeouts, the gateway returns `503` with code
  `UPSTREAM_SERVICE_UNAVAILABLE` and a fixed human message. The response never
  contains the upstream host, port, URL, exception class, or stack trace
  (covered by tests). Backend application errors (401/403/404/...) pass
  through unchanged, so clients still see the real backend error shape.
- **Upstream selection is configuration-only and validated at startup (Phase
  20):** the runtime upstream (`MANAGED_API_TARGET_URL`, default
  `http://localhost:8084`) is trusted application configuration, never client
  input. The gateway does not accept an upstream host, URL, or port from query
  parameters, request headers, the `Authorization` header, or path segments, so
  it cannot be turned into an open proxy; there is no arbitrary per-request URL
  to inspect beyond the single configured value, and no SSRF surface from
  request-controlled URLs. At startup the value must be an absolute `http(s)`
  URL with a host; credentials/userinfo, fragments, unsupported schemes,
  missing hosts, and whitespace are all rejected, and malformed values abort
  startup with a generic error that never echoes the configured value (tests
  cover every case). The gateway has no dynamic API registry and never queries
  PostgreSQL for upstream resolution.
- **Diagnostics-only logging:** each routed request logs method, path, route
  id, status, and duration. The gateway **never logs the `Authorization`
  header, JWT/token material, cookies, or any request/response body** — login
  passwords and client secrets are invisible to gateway logs (tests assert
  rejected/valid tokens never appear in logs).
- **Minimal actuator exposure:** only `GET /actuator/health` is exposed; the
  gateway route-discovery endpoints (`/actuator/gateway/...`) are not.
- **No secrets in configuration:** upstream URLs are environment variables
  with non-secret localhost defaults. The JWT secret never appears in gateway
  configuration — it is read from the `JWT_SECRET` environment variable only
  (a configuration test asserts the YAML contains no secret/password/jwt/token
  material).

Not implemented yet (future phases): subscription tiers and credential
rotation/revocation/status. Gateway-issued runtime analytics telemetry is
implemented (Phase 23) — see the Analytics Service section below.

## Analytics Service — Implemented (Phase 23)

- **JWT protection for query endpoints:** `GET /analytics/events` and
  `GET /analytics/usage` accept only valid shared-signature HS256 bearer JWTs
  (verified locally against the `JWT_SECRET` environment variable with
  nimbus-jose-jwt; the secret must be at least 32 bytes). Every other endpoint
  fails closed (`.anyRequest().denyAll()`); only `GET /actuator/health` is
  public.
- **ADMIN/DEVELOPER authorization:** both analytics endpoints require an
  `ADMIN` or `DEVELOPER` role in the JWT. Missing, malformed, or expired tokens
  return `401 UNAUTHENTICATED`; other roles return `403 ACCESS_DENIED`. There
  is no per-user or per-application segmentation — the summary is a
  platform-wide global aggregate.
- **Internal ingestion boundary (`/internal/*`):** `POST
  /internal/analytics/events` is guarded by a servlet filter that runs *before*
  Spring Security (`Ordered.HIGHEST_PRECEDENCE`) and requires the shared
  internal token; the runtime events are the only production traffic that
  reaches the service directly on its own port (the gateway exposes no `/analytics`
  route, so the internal endpoint is never reachable through the gateway or via
  user JWTs).
- **`ANALYTICS_INTERNAL_TOKEN`:** the shared secret for gateway→Analytics
  delivery, read from the `ANALYTICS_INTERNAL_TOKEN` environment variable and
  sent as the `X-Internal-Service-Token` request header on the internal
  ingestion call. It never appears in configuration, responses, or logs, and
  delivery is disabled entirely unless the token is set — a blank value means
  the feature is off, so no event is ever transmitted unauthenticated.
- **Constant-time token comparison:** the Analytics Service compares a SHA-256
  digest of the expected token against a digest of the presented token with
  `MessageDigest.isEqual` (constant-time on fixed-length digests), so a
  missing, wrong, or malformed token always fails closed to `401`, and error
  responses never reveal which check failed.
- **No header forwarding to the Analytics Service:** the gateway builds the
  internal delivery request from the event data only. Client `Authorization`
  values (Bearer or Basic) and the trusted-identity headers (`X-User-Id`,
  `X-Roles`, `X-Application-Id`, `X-Client-Id`) are never forwarded to the
  Analytics Service; only the caller's numeric `userId`/`applicationId` are
  included in the event payload (tests assert this).

## Authorization / RBAC

- **Roles** (`USER`, `ADMIN`, etc.) determine coarse access (self-service vs.
  administrative operations).
- **Ownership**: a user may only access resources they own (own applications,
  subscriptions, accounts) unless granted otherwise.
- **Scopes**: JWT `scopes` claim gates specific operations (`accounts:read`,
  `payments:write`, `subscriptions:manage`).
- **Subscription enforcement**: authorization for API invocation requires an
  **active subscription** to the target API version. **Implemented** for managed
  API invocations: for **JWT callers** (Phase 17) the gateway checks the API
  Management Service's internal subscription check for a subscription owned by
  the caller's user; for **application callers** (Phase 21) the same internal
  check returns the *authenticated application's* subscription in the same
  response as the credential verification. Both checks query PostgreSQL
  directly (fail closed, no cache). Redis caching of the check, subscription
  tiers/status, and credential revocation remain planned.

## API Subscription Enforcement

- Requests to managed API routes (`/runtime/apis/**`) are de-authorized unless
  the caller holds a subscription to the target API version — **implemented at
  the gateway**: for JWT callers (Phase 17) the call is authorized if the
  calling **user** owns a subscribed application; for client-credential callers
  (Phase 21) authorization is decided by the same internal check and applies to
  the **authenticated application itself** (`applicationId`), not the owning
  user. Both go through the API Management Service's internal check against
  PostgreSQL (fail closed; no cache) — the Phase 21 application flow makes one
  single call that returns both the credential verdict and the subscription
  verdict.
- Subscription state machine, tiers, and status (`PENDING → ACTIVE`, `DENIED`,
  `REVOKED`) are **planned**; today the registry has no status and the gateway
  check is lifecycle-agnostic (any subscription for the API version counts).
- Once a subscription status/state machine exists, revoking it will
  immediately de-authorize subsequent calls; today the gateway queries
  PostgreSQL on every managed-API request, so registry changes take effect
  immediately (with no cache to invalidate).

## Credential Security

- **Application credentials** (`clientId` + `clientSecret`, issued per
  application since Phase 13):
  - Client IDs are public identifiers (random UUIDs, unique per credential).
  - Secrets are **not stored plaintext** — only BCrypt hashes are persisted
    (reusing the same `PasswordEncoder` infrastructure as user passwords).
  - The plaintext secret is generated server-side from `SecureRandom` (256-bit
    entropy), shown **once** at creation, and never logged, retrievable, or
    exposed by later responses.
  - Rotation/revocation/status are **planned** (Phase 13 intentionally has no
    credential lifecycle).
- The gateway **authentication** step that consumes these credentials is
  **implemented (Phase 21)** — see "Gateway Foundations" above. The secret is
  presented as a Basic header, verified server-side on every request (BCrypt
  `matches` against the stored hash), and **stripped before forwarding** to
  the consumed backend; it is never stored, logged, or echoed by the gateway.
- **Refresh tokens**: stored as hashes only.
- No credential is ever printed in logs, responses, or exceptions.

## Password Hashing

- User passwords are salted and hashed with a strong, slow password-hashing
  algorithm (e.g. BCrypt via Spring Security) — **never** MD5/SHA1/plaintext.
- Password hashes are the only password-derived data stored.
- Password policy (minimum length, etc.) validated on registration and change;
  exact policy set during the Identity phase.

## Secret Management

- All secrets (datasource passwords, Redis password, JWT signing key, client
  secrets) come from **environment variables** or orchestration-provided
  secrets (Docker Compose env / Kubernetes Secrets).
- **Nothing is hardcoded**; no default passwords committed; example configs use
  placeholders that are overridden per environment.
- **Redis password (Phase 18):** each service binds `spring.data.redis.password`
  from the `REDIS_PASSWORD` environment variable (no default value in YAML).
  An empty/missing value maps to *no password* — no `AUTH` command is ever
  attempted, and a config test asserts the connection configuration carries no
  password in that case. The **gateway** declares no `password:` key at all
  (a configuration test guards that gateway YAML contains no secret-bearing
  keys). Health responses never include the Redis password: the health endpoint
  uses `show-details: never` and only the `redisHealth` group's component
  *status* is visible; Testcontainers tests assert the password does not appear
  in any `/actuator/health` response body.
- JWT signing keys can be rotated via configuration.
- `.gitignore` rules will exclude any local config that carries real values.

## Rate Limiting

- **Implemented (Phase 21).** The API Gateway enforces per-API-version request
  limits for `/runtime/apis/**` using **Redis fixed-window counters** keyed by
  the caller identity: `rate_limit:user:{userId}:{context}:{version}` for
  JWT-authenticated callers, `rate_limit:app:{applicationId}:{context}:{version}`
  for client-credential (application) callers. `RATE_LIMIT_REQUESTS` (default
  `100`) requests are allowed per `RATE_LIMIT_WINDOW_SECONDS` (default `60`)
  slot.
- Enforced **before** routing so abusive callers cannot reach the managed
  backend.
- Exceeded limits return `429` + code `RATE_LIMIT_EXCEEDED` with
  `Retry-After`; Redis/counter failures return `503` + code
  `RATE_LIMIT_SERVICE_UNAVAILABLE` (fail closed).
- Redis is ephemeral here; counters are not the source of truth.
- Tiers (per-subscription or per-A PI limits) remain planned.

## Security Boundaries

| Boundary | Enforcement |
| --- | --- |
| Edge (external → gateway) | TLS, JWT (Bearer) and/or client-credential (Basic) validation, Redis rate limiting, subscription check |
| Gateway → service | Only gateway-validated requests are routed; on runtime routes the gateway adds verified identity headers (`X-User-Id`, `X-Roles` for users; `X-User-Id`, `X-Application-Id`, `X-Client-Id` for applications) after authentication and always strips client-supplied values of those headers; services still re-validate auth attributes from the JWT, never trusting unchecked headers |
| Service → service | Internal calls carry verified identity context; sensitive operations re-check ownership |
| Data | Services read/write only their own schemas; ownership checks in service logic |
| Secrets | Environment/orchestration only; hashed at rest for locally-stored credentials |

- **Defense in depth**: services must not trust the gateway alone; they validate
  the identity context they receive. Gateway-generated identity headers
  (Phase 22) are enriched context, never standalone trust.
- **Least privilege**: roles/scopes are minimal by default.

## Planned vs. Implemented

| Area | Status |
| --- | --- |
| Authentication (login, JWT issuance) | **Implemented (Phase 6)** — login verifies credentials and issues an HS256 JWT; failed logins return `401 AUTHENTICATION_FAILED` |
| Request authentication (bearer filter) | **Implemented (Phase 7)** — `Authorization: Bearer <jwt>` validated per request in the Identity Service; stateless, no DB lookup |
| RBAC roles & scopes | **Partially implemented (Phase 7)** — `ADMIN`/`DEVELOPER` enforced as `ROLE_ADMIN`/`ROLE_DEVELOPER` on `/test/*` endpoints; scopes still planned |
| Shared JWT validation & RBAC (other services) | **Partially implemented (Phase 8)** — the API Management Service validates the same JWT locally (`JWT_SECRET`) and enforces `ADMIN`/`DEVELOPER` roles on its catalog endpoints |
| Resource ownership (applications) | **Implemented (Phase 11)** — applications carry `ownerUserId` from the JWT `sub`; all reads/updates are owner-scoped; cross-owner access returns `404 APPLICATION_NOT_FOUND` (no existence leak) |
| Subscription ownership (subscriptions) | **Implemented (Phase 12)** — subscriptions are owner-scoped through their application; cross-owner access returns `404 APPLICATION_SUBSCRIPTION_NOT_FOUND` (no existence leak); duplicates rejected (`409`) |
| Credential ownership & issuance (credentials) | **Implemented (Phase 13)** — credentials are owner-scoped through their application; server-generated `clientId` + `clientSecret` (BCrypt hash stored, plaintext shown once); cross-owner access returns `404 CREDENTIAL_NOT_FOUND` (no existence leak); `clientId` unique at service + DB level |
| Account/Payment/Transaction ownership | **Implemented (Phase 14)** — the Payment Service validates the same JWT locally, requires `ADMIN`/`DEVELOPER` on all endpoints (`.anyRequest().denyAll()`, unknown roles fail closed to `401`), derives owners from `sub`, and returns `404` (no existence leak) for any missing or unowned account/payment/transaction; financial fields (status/type/currency) are always server-derived |
| Gateway routing & upstream failure handling | **Implemented (Phase 15)** — 9 path routes forward to Identity/API Management/Payment with the URI untouched; unreachable/timed-out upstreams return a generic `503 UPSTREAM_SERVICE_UNAVAILABLE` (no internal addresses or stack traces); backend 4xx/5xx pass through; method/path/route/status/duration logged without `Authorization` headers or bodies; only `/actuator/health` exposed |
| JWT validation at gateway | **Implemented (Phase 16)** — the gateway rejects any non-public routed request without a valid Bearer JWT (HS256 verified against the shared `JWT_SECRET`, unexpired, numeric `sub` + `ADMIN`/`DEVELOPER` `role`); rejections return a generic `401 UNAUTHENTICATED` that never reveals which check failed or any token material; valid `Authorization` headers are forwarded unchanged and services still validate locally (defense in depth); the gateway issues no tokens and does no business authorization |
| Subscription enforcement | **Implemented (Phases 17 and 21, managed-API calls)** — for `/runtime/apis/**`: JWT callers (Phase 17) must own an application subscribed to the target API version, verified via the authenticated, non-routable internal API Management `GET /internal/subscription-check` endpoint against PostgreSQL (no cache); application callers (Phase 21) are authorized by the **authenticated application's own** subscription, returned by the same single `GET /internal/credential-check` call that verifies the credential. Unsubscribed → `403 SUBSCRIPTION_REQUIRED`, failed check → `503` (`SUBSCRIPTION_SERVICE_UNAVAILABLE` / `CREDENTIAL_SERVICE_UNAVAILABLE`) — fail closed; identity is never client-supplied and there is no `ADMIN` bypass. Tier/status-based enforcement and Redis caching remain planned |
| Client-credential gateway authentication | **Implemented (Phase 21)** — `/runtime/apis/**` accepts `Authorization: Basic base64(clientId:clientSecret)`; the gateway verifies the credential (and the application's subscription) via the internal `GET /internal/credential-check` endpoint (BCrypt against the stored hash, direct PostgreSQL query, no cache) and **strips the Basic header before forwarding**; unknown/malformed credentials → `401 CLIENT_CREDENTIAL_INVALID`, check failure → `503 CREDENTIAL_SERVICE_UNAVAILABLE` (fail closed), no subscription → `403 SUBSCRIPTION_REQUIRED`. Management routes remain Bearer-only (Basic on `/apis/**` → `401`); secrets/hashes are never stored, returned, forwarded, or logged by the gateway |
| Trusted identity headers (gateway → managed APIs) | **Implemented (Phase 22)** — after authenticating a `/runtime/apis/**` request the gateway adds verified identity headers (`X-User-Id` + `X-Roles` for the JWT/user flow; `X-User-Id` + `X-Application-Id` + `X-Client-Id` for the client-credential/application flow) before forwarding, keeps Bearer forwarding / Basic stripping unchanged, applies them only on runtime routes, and always strips client-supplied values of these four headers (never trusted from the client); platform-management routes receive none. `X-Scopes` remains planned |
| Rate limiting | **Implemented (Phase 21)** — the gateway rate-limits `/runtime/apis/**` before routing with Redis fixed-window counters per API version (`rate_limit:user:{userId}:{context}:{version}` for JWT callers, `rate_limit:app:{applicationId}:{context}:{version}` for application callers; `RATE_LIMIT_REQUESTS` default `100` per `RATE_LIMIT_WINDOW_SECONDS` default `60` s); exceed → `429 RATE_LIMIT_EXCEEDED` with `Retry-After`, Redis failure → `503 RATE_LIMIT_SERVICE_UNAVAILABLE` (fail closed). Tiers and per-subscription limits remain planned |
| Password hashing | **Implemented (Phases 3/4)** — BCrypt via `spring-security-crypto`; only hashes are stored |
| Credential hashing (client secrets) | **Implemented (Phase 13)** — client secrets hashed with the same BCrypt `PasswordEncoder`; only `client_secret_hash` is persisted |
| Secret management / env-config | **Partially implemented** — datasource credentials and the JWT signing secret (`JWT_SECRET`, `JWT_EXPIRATION_SECONDS`) come from environment variables; fail-fast if the required signing secret is absent. Redis coordinates (`REDIS_HOST`/`REDIS_PORT`/`REDIS_PASSWORD`) are env-bound since Phase 18 and an empty password maps to *no password* (never a literal AUTH); the gateway config declares no password key |
| Redis infrastructure health (Phase 18) | **Implemented** — all four services connect to Redis (infrastructure only, no caching/rate-limit/token business yet) and monitor it via a dedicated `GET /actuator/health/redisHealth` group (native Redis indicator, `show-components: always`, `show-details: never`); the default `/actuator/health` deliberately excludes Redis so a Redis-less deployment stays `UP`, implemented with a small `HealthEndpointGroups` bean (public Actuator API) because Boot 3.5.16 cannot exclude a contributor from the default group by properties. Health responses never expose the Redis password |
| Token revocation (Redis) | **Planned** — not implemented |

> As of Phase 22 the Identity Service supports stateless bearer request
> authentication and role checks on the temporary `/test/*` endpoints, the
> API Management Service validates the same JWT and enforces roles on its
> catalog, application, subscription, and credential endpoints with full
> owner-scoping (and now hosts the internal subscription-check and
> credential-check endpoints the gateway consults), and the Payment Service
> validates the same JWT and enforces owner-scoped `ADMIN`/`DEVELOPER` access
> on its account, payment, and transaction endpoints (fail closed, no public
> endpoints except the Phase 18 health endpoint). The API Gateway (Phases
> 15–22) routes requests to the three services, handles upstream failures,
> authenticates callers — user **Bearer JWTs** (`POST /users`, `POST /auth/login`,
> and health remain public) and, for managed API invocations, **application
> client credentials** (`Basic`, verified against the API Management Service's
> registry with the plaintext secret stripped before forwarding) — enforces
> subscriptions (`403 SUBSCRIPTION_REQUIRED` when unsubscribed; fail closed
> `503` when the check is unavailable), applies **Redis-backed rate
> limiting** per API version (`429 RATE_LIMIT_EXCEEDED`; fail closed `503`
> when Redis is unavailable), and, for managed API invocations, supplies the
> caller's verified identity to the consumed API as gateway-generated
> `X-User-Id`/`X-Roles` (user flow) and `X-User-Id`/`X-Application-Id`/
> `X-Client-Id` (application flow) headers (Phase 22), always stripping any
> client-supplied values of those headers. Scope enforcement, subscription
> tiers, and credential lifecycle remain planned. Each backend service still validates
> the JWT locally, so the gateway is not a single point of trust for
> authentication. Redis is connected across all four services (Phase 18) with
> health monitoring via the dedicated `redisHealth` health group and is now
> used for real gateway rate-limit counters (Phase 21) — the only business
> function it currently serves.