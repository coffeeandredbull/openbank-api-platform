# OpenBank API Platform — AI Development Rules

## Purpose

OpenBank API Platform is an educational enterprise API-management platform inspired by the concepts of enterprise API-management platforms such as WSO2 API Manager — it is NOT a clone. Every major architectural decision must remain explainable by the developer in an interview. It demonstrates: API management, API gateways, authentication, authorization, JWT, OAuth2 concepts, API subscriptions, API versioning, rate limiting, Redis, PostgreSQL, microservices, Docker, CI/CD, Kubernetes, and API analytics.

## Repository status

- The git repo's top-level is this project folder (`C:\Users\www\Desktop\openbank-api-platform`), with an active commit history (Identity, API Management, gateway, payments, analytics, infrastructure; currently at Phase 24). Always check `git status` before staging; never stage unrelated files.
- Builds exist: run build/test/lint from `api-management-service/` (`.\\mvnw.cmd -B test`), with Docker up for Testcontainers.
- Never commit, push, or amend unless explicitly asked.
- On Windows; the default shell is `pwsh` (PowerShell 7).

## Technology stack (do not change without asking)

- Backend: Java 21, Spring Boot, Spring Security, Spring Data JPA, PostgreSQL, Redis, Spring Cloud Gateway
- Frontend: React, TypeScript
- Testing: JUnit, Mockito, Spring Boot Test, Testcontainers
- Infrastructure: Docker, Docker Compose, GitHub Actions, Kubernetes
- API documentation: OpenAPI

## Hard constraints

- Build in small, explicitly requested phases only. NEVER build the whole project (or entire services) in one operation, and never preemptively create future services/features.
- Do not add Kafka, RabbitMQ, Elasticsearch, GraphQL, gRPC, service discovery, cloud infrastructure, or AI features unless explicitly requested. Do not introduce any technology outside the stack above without asking first.
- No placeholder functionality: no fake analytics, fake authentication, or hardcoded fake security. Everything implemented must have a clear reason.
- If requirements are ambiguous, STOP and ask. If an implementation choice has significant trade-offs, explain them before implementing.
- Code must compile and relevant tests must pass after each step. Never claim something works unless verified. Diagnose root cause before changing code.

## Coding standards

- Controllers thin, business logic in services, DB access in repositories, DTOs at API boundaries, centralized error handling, validate all external input, appropriate HTTP status codes.
- Never expose or hardcode secrets (passwords, hashes, client secrets, JWT secrets, API keys). Use environment variables for secrets and environment-specific config. Never log credentials.
- Prefer simple production-style implementations; avoid unnecessary abstractions; don't duplicate business logic; don't delete or rewrite working code without a concrete reason.
- Before modifying multiple files, explain which files will change and why.

## Working style

The developer uses AI as an engineering assistant, not a replacement for understanding. For every significant feature:

1. Explain the problem. 2. Propose the design. 3. Identify affected files. 4. Implement the smallest useful version. 5. Run tests/build. 6. Explain the important code. 7. Identify possible weaknesses. 8. Wait for the next instruction.

## Architecture goal

Developer Portal → API Gateway → Identity / API Management / Account / Payment / Transaction / Analytics services, sharing PostgreSQL and Redis. The architecture may evolve only when there is a concrete engineering reason; keep this file synchronized with any change.