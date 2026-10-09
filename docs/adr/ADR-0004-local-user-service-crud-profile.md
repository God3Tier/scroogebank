# ADR-0004: Local user-service CRUD smoke-test profile

- Status: Superseded
- Decision date/time: 2026-09-26 13:44:31 +08:00 (Asia/Singapore)
- Human owner/decision-maker: Joseph Yau
- AI-assisted draft: Yes — Codex
- Related branch: `feat/refactor-feat-2`
- Related issue/PR: Not yet assigned
- Supersedes: None
- Superseded by: ADR-0005

## Context

Feature 1 uses Cognito for identity provisioning and JWT authentication. Developers need to check the user-service CRUD endpoints and local database persistence without using a deployed Cognito pool. A local mode that silently replaced production security would be unsafe, while a mode scoped to an explicit Spring profile can support CRUD smoke testing without changing deployed behavior.

## Decision

1. Add a `local` Spring profile for user-service CRUD smoke testing.
2. The profile uses a local PostgreSQL database and a no-op `IdentityProviderClient`; it makes no AWS Cognito calls.
3. The profile permits unauthenticated HTTP requests and does not enable method-level role checks.
4. The default and deployed profiles continue to require Cognito JWTs and method-level Admin authorization.
5. `local` must never be activated by a deployed task definition. Tests run with this profile do not count as verification of Cognito, MFA, JWT validation, or role authorization.

## Consequences

### Positive

- Developers can exercise user creation, retrieval, updates, disablement, and database persistence without an access token or AWS credentials.
- The production authentication path remains unchanged and explicit.

### Trade-offs and risks

- The local profile deliberately removes authentication and authorization and is unsafe for any shared or deployed environment.
- The local identity-provider adapter does not synchronize passwords or groups; its behavior is only enough for CRUD persistence tests.
- Production security still needs separate JWT, Cognito, and role-based integration verification.

## Alternatives considered

### Use a real Cognito pool for local CRUD tests

Rejected because it requires cloud credentials and a shared identity pool for routine local testing.

### Add a local Keycloak deployment

Rejected because the requested check is CRUD behavior, not a second IdP implementation or authentication parity environment.

### Disable security in the default profile

Rejected because a missing profile or configuration error could then expose deployed endpoints without authentication.
