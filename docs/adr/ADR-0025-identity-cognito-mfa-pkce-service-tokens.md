# ADR-0025: Identity: Cognito with mandatory MFA, managed login with PKCE, client-credentials service tokens, Cognito auth-event triggers

- Status: Proposed
- Decision date/time: 2026-10-01 21:50 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/target-architecture`
- Related issue/PR: TBD (pull request from `feat/target-architecture` to `dev`)
- Supersedes: N/A (extends [ADR-0005](ADR-0005-cognito-security-across-features-1-and-2.md))
- Superseded by: N/A

## Context

[ADR-0005](ADR-0005-cognito-security-across-features-1-and-2.md) (Accepted, owner Joseph Yau) already decides several things:
- Cognito is the shared identity source;
- every service validates Cognito access tokens;
- roles come from `cognito:groups`;
- agents are limited to their own clients;
- API Gateway applies a JWT authorizer at the edge.

It does not cover several things the target architecture needs:
- how users sign in from the single-page frontend;
- MFA enforcement;
- how services and scheduled tasks authenticate to each other when no user is involved;
- how login and self-service password-reset events reach the audit log, given they happen inside Cognito.

Feature 1 requires OAuth 2.0, MFA, secure password storage, and auditing of logins and password changes.

## Decision Drivers

- Security: MFA; no secrets in the browser; Zero Trust between services (NFR 8)
- Course requirements for Feature 1
- Using managed services rather than building authentication

## Options Considered

### Sign-in flow

- **Managed login with the OAuth 2.0 authorization-code flow and PKCE (chosen, pending confirmation):** Cognito hosts the login and MFA pages. The single-page app cannot keep a client secret, so PKCE proves the token request comes from the app that started the sign-in.
- **Custom login form using Cognito's SRP API (Amplify `signIn()`):** the CRM's own login UI; no redirect, so PKCE does not apply; the team builds the MFA and challenge screens.
- **Implicit grant:** returns tokens in the URL; current OAuth guidance advises against it. Rejected.

### Service-to-service authentication

- **Forward the user's JWT** where a user is involved, and use **Cognito client-credentials tokens with scopes** where none is (chosen).
- **Trust calls because they come from inside the VPC:** violates Zero Trust. Rejected.

### Login and password-reset auditing

- **Cognito Lambda triggers** `PostAuthentication` and `PostConfirmation_ConfirmForgotPassword` (chosen): the only way to capture events that happen inside Cognito.
- **CloudTrail:** records Cognito API calls, but not as business audit events in the hash chain.

## Decision

1. **Cognito user pool:**
   - MFA **required** (TOTP);
   - Cognito stores password hashes;
   - `Admin` and `Agent` groups appear in the JWT `cognito:groups` claim;
   - the root admin cannot be deleted or disabled.
2. **Sign-in:** Cognito managed login with the authorization-code flow and **PKCE**. This is **not yet confirmed** ([open item 20](../architecture/open-items.md#20-sign-in-flow-and-pkce-unconfirmed)). Cognito checks PKCE when a challenge is sent but does not require one, so the app client must be set up as:
   - a public client with no client secret;
   - authorization-code grant only, with implicit grant off;
   - used from a frontend library that adds PKCE automatically. `oidc-client-ts`, used on `feat/frontend` through `react-oidc-context`, does this by default.
3. **Defense in depth:** API Gateway validates every token, and each service validates it again (ADR-0005).
4. **Service identity:** calls with no user use Cognito client-credentials tokens with scopes, for example `audit/read`. Such callers include scheduled tasks and the health analysis task. Calls with a user forward the user's JWT.
5. **Auth-event capture:**
   - the two Cognito triggers send login and password-reset events directly to the audit queue and **fail open** ([ADR-0020](ADR-0020-audit-write-path-outbox-shared-sqs.md)). They are one plain Java 25 Lambda that gives itself 3 seconds to send ([ADR-0040](ADR-0040-cognito-triggers-java-lambda.md));
   - user-service audits logout, user creation, update, disable, role change and admin password reset through its outbox.

## Rationale

- Managed login keeps password and MFA handling inside Cognito.
- PKCE closes the stolen-code gap for a public client.
- Client-credentials tokens give background callers a verifiable identity without sharing user tokens.
- The triggers are the only hook into events that never reach our services.

## Consequences

### Positive

- One identity system for users and services.
- No client secret in the browser.
- MFA is enforced for everyone.
- Every login is audited.

### Negative / Trade-offs

- Managed login limits UI customisation.
- PKCE protection depends on configuration and the frontend library, and needs a test.
- Fail-open triggers can leave a login missing from the chain until it is replayed.
- Client-credentials app clients and scopes must be managed in Terraform.

## Security & Privacy Impact

- No password or MFA secret ever reaches CRM services.
- Roles always come from the verified token, never from request fields.
- Cognito has no sign-out trigger, so a logout is audited only if the frontend also calls a user-service endpoint when it revokes the refresh token (`/oauth2/revoke`).

## Cost Impact

Cognito is free at this number of users. The trigger Lambda costs almost nothing ([ADR-0040](ADR-0040-cognito-triggers-java-lambda.md)).

## Operational Impact

- User pool, app clients, groups, scopes and triggers are defined in Terraform.
- An `AUDIT_SEND_FAILED` alarm and a replay runbook (in `services/cognito-triggers/README.md`).

## Validation

- Sign-in without MFA fails.
- In the browser network tab, the `/oauth2/authorize` request carries `code_challenge` and `code_challenge_method=S256`.
- A token from the wrong app client or with the wrong `token_use` is rejected (ADR-0005 tests).
- A login produces an audit event; with SQS unreachable, the login still succeeds and `AUDIT_SEND_FAILED` is logged.

## References

- [Identity and security](../security/security-model.md#identity-and-security), [authentication events](../security/security-model.md#authentication-events)
- [Open item 20](../architecture/open-items.md#20-sign-in-flow-and-pkce-unconfirmed)
- [ADR-0005](ADR-0005-cognito-security-across-features-1-and-2.md) (Accepted; extended, not superseded)
- RFC 7636 (PKCE); AWS documentation: Amazon Cognito managed login, Lambda triggers
