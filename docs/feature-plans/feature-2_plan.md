# Feature 2 Security Plan

- Status: In progress
- Feature branch: `feat/refactor-feat-2`
- Primary developer: Joseph Yau
- Last updated: 2026-09-26

## Goal

Use the Feature 1 Cognito user pool as the identity source for Feature 2. Every client/account API request must have a valid Cognito access token. Admins can manage all clients; agents can access only profiles they created and accounts belonging to those profiles. The authenticated identity must come from the verified token, never from a caller-controlled header.

## Tasks

| Task | Description | Owner | Expected files/components | Dependencies | Branch | Status |
|---|---|---|---|---|---|---|
| F2-SEC-01 | Verify Cognito access tokens and enforce Admin/Agent client ownership | Joseph Yau | `services/client-profile-management-service/src/internal/security`, server/router, schema, Bruno collection | Cognito issuer and app-client ID from the shared pool | `feat/refactor-feat-2` | Implemented; build verified, integration tests not run |
| F1-SEC-01 | Keep Cognito provisioning, JWT validation, and Admin authorization active in the local database profile | Joseph Yau | `services/user-service` security configuration, Makefile, README | Cognito pool and AWS credentials | `feat/refactor-feat-2` | Implemented; package verified, auth flow not integration-tested |
| F2-SEC-02 | Configure API Gateway JWT authorizer and encrypted RDS storage in deployment infrastructure | Joseph Yau | Infrastructure/API Gateway and RDS configuration | Infrastructure directory is absent from this checkout | `feat/refactor-feat-2` | Blocked by missing infrastructure files |
| F2-SEC-03 | Complete hash-chain audit records for Feature 2 business actions | Joseph Yau | Feature 2 data/service and Auditor integration | Audit write contract is not present in the current Auditor API | `feat/refactor-feat-2` | Planned |

## Security behavior

- The Feature 2 service validates the JWT signature against Cognito JWKS and checks issuer, app client, token use, and time claims.
- `cognito:groups` supplies the `ADMIN` or `AGENT` role. The service fails closed when the token is missing, invalid, or lacks a supported group.
- The opaque Cognito `sub` identifies the creator. It is stored as text because Cognito does not guarantee RFC UUID formatting.
- Client reads/updates/deletes/verifications and account create/delete requests enforce agent ownership. Admins can access every client.
- Production service traffic must use TLS, and PostgreSQL connections must verify the RDS certificate. Local HTTP and `sslmode=disable` are limited to local development.

## Open requirements

The source specification also requires encrypted database storage and tamper-resistant audit logging. The infrastructure files needed to configure encrypted RDS/API Gateway are absent here; the current Auditor exposes audit reads but no write endpoint. These gaps must be resolved before Feature 2 security work is considered complete.
