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

- **Application credentials** (API keys / client IDs and client secrets):
  - Client IDs are public identifiers.
  - Secrets/keys are **not stored plaintext** — only hashes are persisted.
  - Secrets are displayed once at creation (or regenerable) and never logged.
  - Rotation is supported.
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
| JWT validation at gateway | **Planned** — not implemented (a service-level `JwtTokenService.validateToken` foundation exists) |
| RBAC roles & scopes | **Planned** — role is carried in the JWT claim; no enforcement filters exist |
| Subscription enforcement | **Planned** — not implemented |
| Rate limiting | **Planned** — not implemented |
| Password hashing | **Implemented (Phases 3/4)** — BCrypt via `spring-security-crypto`; only hashes are stored |
| Secret management / env-config | **Partially implemented** — datasource credentials and the JWT signing secret (`JWT_SECRET`, `JWT_EXPIRATION_SECONDS`) come from environment variables; fail-fast if the required signing secret is absent |
| Token revocation (Redis) | **Planned** — not implemented |

> As of Phase 6 only user registration, password hashing, login, and JWT
> issuance exist. There is **no request authentication mechanism** yet — no
> filter, no protected endpoints, and no gateway validation.