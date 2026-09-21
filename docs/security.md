# Security Design (Planned)

> This document describes the **planned** security model. **To be explicit:
> none of this is implemented yet.** The section "Planned vs. Implemented"
> below distinguishes the two. No credentials, hashes, or secrets exist in this
> repository, and none will be committed at any point.

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
| Authentication (login, JWT issuance) | **Planned** — not implemented |
| JWT validation at gateway | **Planned** — not implemented |
| RBAC roles & scopes | **Planned** — not implemented |
| Subscription enforcement | **Planned** — not implemented |
| Rate limiting | **Planned** — not implemented |
| Password hashing | **Planned** — not implemented (no users or passwords exist) |
| Secret management / env-config | **Planned** — not implemented (no config files exist) |
| Token revocation (Redis) | **Planned** — not implemented |

> As of Phase 1 there is **no running security mechanism of any kind** — the
> repository contains only documentation.