# OpenBank API Platform — AI Development Rules

## Purpose

OpenBank API Platform is an educational enterprise API-management platform inspired by the concepts of enterprise API-management platforms such as WSO2 API Manager — it is NOT a clone. Every major architectural decision must remain explainable by the developer in an interview. It demonstrates: API management, API gateways, authentication, authorization, JWT, OAuth2 concepts, API subscriptions, API versioning, rate limiting, Redis, PostgreSQL, microservices, Docker, CI/CD, Kubernetes, and API analytics.

## Repository status

- Fresh repository: no commits yet, only this file. No build files, no source code.
- Do not run build/test/lint/typecheck commands until the relevant build files exist (`pom.xml`, `package.json`, etc.) — nothing compiles yet.
- The active git repo's top-level is `C:\Users\www` (the home directory), not this project folder. `git add` from here stages the entire home tree — always use explicit paths and check `git status` before staging.
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