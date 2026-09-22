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

Not implemented yet (future phases): authentication at the API Gateway,
subscription enforcement, and scopes.

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

Not implemented yet (future phases): gateway-level authentication, RS256, and
credential/subscription checks.

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

## Authorization / RBAC

- **Roles** (`USER`, `ADMIN`, etc.) determine coarse access (self-service vs.
  administrative operations).
- **Ownership**: a user may only access resources they own (own applications,
  subscriptions, accounts) unless granted otherwise.
- **Scopes**: JWT `scopes` claim gates specific operations (`accounts:read`,
  `payments:write`, `subscriptions:manage`).
- **Subscription enforcement**: authorization for API invocation requires an
  **active subscription** of the caller's application to the target API
  version, at the gateway using Subscription Service data (with Redis caching).

## API Subscription Enforcement

- Only requests carrying application credentials with an active subscription
  reach a versioned API.
- Subscription state machine (`PENDING → ACTIVE`, `DENIED`, `REVOKED`) is
  enforced by the Subscription Service; the gateway consults it (or cached)
  per request.
- Revoking a subscription immediately de-authorizes subsequent calls.

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
  - The gateway **authentication** step that consumes these credentials is a
    later phase.
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
- JWT signing keys can be rotated via configuration.
- `.gitignore` rules will exclude any local config that carries real values.

## Rate Limiting

- Per-application, per-tier request limits enforced by the **API Gateway**
  using **Redis counters** (token-bucket or fixed-window; decision pending).
- Enforced **before** routing so abusive callers cannot reach backends.
- Exceeded limits return `429 Too Many Requests` with `Retry-After`.
- Redis is ephemeral here; counters are not the source of truth.

## Security Boundaries

| Boundary | Enforcement |
| --- | --- |
| Edge (external → gateway) | TLS, JWT validation, rate limiting, subscription check |
| Gateway → service | Only gateway-validated requests are routed; services still re-validate auth attributes from the JWT, never trusting unchecked headers |
| Service → service | Internal calls carry verified identity context; sensitive operations re-check ownership |
| Data | Services read/write only their own schemas; ownership checks in service logic |
| Secrets | Environment/orchestration only; hashed at rest for locally-stored credentials |

- **Defense in depth**: services must not trust the gateway alone; they validate
  the identity context they receive.
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
| JWT validation at gateway | **Planned** — not implemented (Identity Service validates at the request level) |
| Subscription enforcement | **Planned** — not implemented (only the subscription and credential registries exist; gateway/runtime enforcement is a future phase) |
| Rate limiting | **Planned** — not implemented |
| Password hashing | **Implemented (Phases 3/4)** — BCrypt via `spring-security-crypto`; only hashes are stored |
| Credential hashing (client secrets) | **Implemented (Phase 13)** — client secrets hashed with the same BCrypt `PasswordEncoder`; only `client_secret_hash` is persisted |
| Secret management / env-config | **Partially implemented** — datasource credentials and the JWT signing secret (`JWT_SECRET`, `JWT_EXPIRATION_SECONDS`) come from environment variables; fail-fast if the required signing secret is absent |
| Token revocation (Redis) | **Planned** — not implemented |

> As of Phase 13 the Identity Service supports stateless bearer request
> authentication and role checks on the temporary `/test/*` endpoints, and the
> API Management Service validates the same JWT and enforces roles on its
> catalog, application, subscription, and credential endpoints, with full
> owner-scoping for applications/subscriptions/credentials. There is **no
> gateway authentication, subscription enforcement, or scope enforcement**
> yet.