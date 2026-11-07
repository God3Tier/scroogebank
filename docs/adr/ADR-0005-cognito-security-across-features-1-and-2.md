# ADR-0005: Enforce shared Cognito security across Features 1 and 2

- Status: Accepted
- Decision date/time: 2026-09-26 15:25 +08:00 (Asia/Singapore)
- Human owner/decision-maker: Joseph Yau
- AI-assisted draft: Yes — Codex
- Feature/branch: `feat/refactor-feat-2`
- Related issue/PR: Not yet assigned
- Supersedes: ADR-0004
- Superseded by: [ADR-0059](ADR-0059-admins-read-only-on-client-data.md) (decision 4 in part: admins read every client profile and account but no longer change them; the rest of decision 4 and the other decisions remain in force)

## Context

The user-service `local` profile had replaced Cognito provisioning with a no-op adapter and permitted unauthenticated requests. Feature 2 had no authentication or ownership checks and accepted a caller-provided `X-Agent-ID`. The project specification requires OAuth2 request validation, Admin/Agent roles, agents limited to their own clients, and encrypted data in transit and at rest.

## Decision

1. Cognito is the shared identity source. Feature 1 provisions users and group membership; both services validate Cognito access tokens.
2. The user-service local profile may select a local database and enable Swagger, but it must not replace Cognito, JWT validation, or Admin authorization.
3. Feature 2 verifies RS256 signatures against the configured Cognito user-pool JWKS and validates issuer, app-client ID, `token_use=access`, and time claims.
4. Feature 2 reads role membership from `cognito:groups`. Admins can access all client profiles and their accounts. Agents can access only profiles created under their authenticated Cognito `sub` and accounts belonging to those profiles.
5. Feature 2 treats Cognito `sub` as an opaque string and stores it as text. Caller-supplied agent headers are overwritten after authentication.
6. Production Feature 2 deployments must enable HTTPS and verify PostgreSQL TLS certificates. Local-only HTTP/PostgreSQL settings are not production defaults.
7. API Gateway should apply a Cognito-compatible JWT authorizer at the edge while the service repeats JWT validation. RDS encryption-at-rest and Feature 2 audit write integration remain deployment/integration requirements.

## Consequences

### Positive

- Both features use one identity, role source, and MFA enrollment path.
- Feature 2 enforces per-agent data boundaries inside the service even if a request bypasses API Gateway.
- A client cannot impersonate another agent by supplying an `X-Agent-ID` header.

### Trade-offs and risks

- Local CRUD calls now require a real Cognito access token and reachable Cognito JWKS.
- Deployment infrastructure and the Auditor write contract are absent from this checkout, so API Gateway authorizer, RDS encryption-at-rest, and Feature 2 hash-chain audit integration still need implementation.
- JWT verification code must be maintained alongside tests for key rotation, expired tokens, issuer/client mismatches, and role/ownership decisions.

## Alternatives considered

### Trust only the API Gateway authorizer

Rejected because the project requires each service to validate every request under Zero Trust; a direct service path would otherwise bypass JWT validation.

### Continue trusting `X-Agent-ID`

Rejected because callers can forge headers unless the service derives the actor from a verified token.
