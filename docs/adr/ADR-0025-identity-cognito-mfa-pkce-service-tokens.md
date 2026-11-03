# ADR-0025: Identity: Cognito with mandatory MFA, managed login with PKCE, client-credentials service tokens, Cognito auth-event triggers

- Status: Accepted
- Decision date/time: 2026-10-13 18:46 +08:00 (Asia/Singapore); first proposed 2026-10-01 21:50 +08:00
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/target-architecture` (proposed); `task/governance/adr-0025-sign-in` (amended, accepted); implementation on `task/base-features/frontend-sign-in`
- Related issue/PR: TBD
- Supersedes: N/A (extends [ADR-0005](ADR-0005-cognito-security-across-features-1-and-2.md))
- Superseded by: in part, ADR-0076 (MFA enforced through a token scope, so two evaluation accounts can be exempt)

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
- how login and self-service password-reset events reach the audit log, given they happen inside Cognito;
- how logouts reach the audit log, given Cognito has no sign-out trigger.

Feature 1 requires OAuth 2.0, MFA, secure password storage, and auditing of logins, logouts and password changes.

The frontend on `dev` has no real sign-in yet: its login page checks passwords against seed data in the browser and keeps no session. When this ADR was proposed it said `feat/frontend` already signed in through `react-oidc-context`; no branch has that code, so the sign-in is new work. user-service has had `POST /api/users/me/logout`, audited as `LOGOUT`, since PR #74, but nothing calls it yet.

## Decision Drivers

- Security: MFA; no secrets in the browser; Zero Trust between services (NFR 8)
- Course requirements for Feature 1
- Using managed services rather than building authentication

## Options Considered

### Sign-in flow

- **Managed login with the OAuth 2.0 authorization-code flow and PKCE (chosen):** Cognito hosts the login and MFA pages. The single-page app cannot keep a client secret, so PKCE proves the token request comes from the app that started the sign-in. Current OAuth guidance requires PKCE for public clients (RFC 9700, the OAuth 2.0 Security Best Current Practice), and the frontend library adds it at no cost.
- **Custom login form using Cognito's SRP API (Amplify `signIn()`):** the CRM's own login UI; no redirect, so PKCE does not apply. The team would build the MFA setup, MFA challenge, new-password and forgot-password screens, for UI control the CRM does not need.
- **Implicit grant:** returns tokens in the URL; current OAuth guidance advises against it. Rejected.

### Service-to-service authentication

- **Forward the user's JWT** where a user is involved, and use **Cognito client-credentials tokens with scopes** where none is (chosen).
- **Trust calls because they come from inside the VPC:** violates Zero Trust. Rejected.

### Login and password-reset auditing

- **Cognito Lambda triggers** `PostAuthentication` and `PostConfirmation_ConfirmForgotPassword` (chosen): the only way to capture events that happen inside Cognito.
- **CloudTrail:** records Cognito API calls, but not as business audit events in the hash chain.

### Logout auditing

- **The frontend reports each sign-out to user-service** (chosen): user-service audits it through its outbox, like its other events.
- **CloudTrail:** records `RevokeToken` calls, and the classic Hosted UI's `/logout` as a `Logout` event with the user's `sub`, but lists no sign-out event for the managed login pages. It is not the hash chain either, so it would need a pipeline from the trail into the audit queue. Not chosen.

## Decision

1. **Cognito user pool:**
   - MFA **required** (TOTP);
   - Cognito stores password hashes;
   - `Admin` and `Agent` groups appear in the JWT `cognito:groups` claim;
   - the root admin cannot be deleted or disabled.
2. **Sign-in:** Cognito managed login with the authorization-code flow and **PKCE** (`S256`). Cognito checks PKCE when a challenge is sent but does not require one, and no app-client setting makes it required, so the protection rests on three things:
   - **The app client:**
     - a public client with no client secret;
     - authorization-code grant only, with implicit grant off;
     - scopes `openid` and `email`;
     - token revocation on;
     - callback URL `<origin>/auth/callback` and sign-out URL `<origin>/`, for each environment's CloudFront origin and for `http://localhost:5173` in local development.
   - **The frontend library:** `oidc-client-ts`, used through `react-oidc-context`, adds PKCE to every authorization-code sign-in unless `disablePKCE` is set.
   - **A frontend test** that the `/oauth2/authorize` request carries `code_challenge_method=S256` and a `code_challenge` that is the SHA-256 of the verifier.
3. **Defense in depth:** API Gateway validates every token, and each service validates it again (ADR-0005).
4. **Service identity:** calls with no user use Cognito client-credentials tokens with scopes, for example `audit/read`. Such callers include scheduled tasks and the health analysis task. Calls with a user forward the user's JWT.
5. **Auth-event capture:**
   - the two Cognito triggers send login and password-reset events directly to the audit queue and **fail open** ([ADR-0020](ADR-0020-audit-write-path-outbox-shared-sqs.md)). They are one plain Java 25 Lambda that gives itself 3 seconds to send ([ADR-0040](ADR-0040-cognito-triggers-java-lambda.md));
   - user-service audits logout, user creation, update, disable, role change and admin password reset through its outbox;
   - **sign-out:** Cognito has no sign-out trigger, so the frontend reports each sign-out. It runs these steps in order, and each runs even if the one before fails, so an audit failure never stops a sign-out:
     1. `POST /api/users/me/logout` with the session's access token, which user-service audits as `LOGOUT`;
     2. revoke the refresh token at Cognito's `/oauth2/revoke`;
     3. drop the tokens and send the browser to Cognito's `/logout`, which ends the managed login session. Without it, the next sign-in in that browser would skip the password and MFA.

     The audit call comes first, while the access token is certainly valid, so it does not rely on a gateway accepting a token whose refresh token has been revoked.

## Rationale

- Managed login keeps password and MFA handling inside Cognito.
- PKCE closes the stolen-code gap for a public client.
- Client-credentials tokens give background callers a verifiable identity without sharing user tokens.
- The triggers are the only hook into events that never reach our services.
- Reporting sign-outs to user-service puts logouts in the hash chain with the other user events, with no extra infrastructure.

## Consequences

### Positive

- One identity system for users and services.
- No client secret in the browser.
- MFA is enforced for everyone.
- Every login is audited.
- Every sign-out through the app is audited, unless the report fails.

### Negative / Trade-offs

- Managed login limits UI customisation.
- PKCE protection depends on configuration and the frontend library, and needs a test.
- Fail-open triggers can leave a login missing from the chain until it is replayed.
- A `LOGOUT` event records what the frontend reports, not a verified revocation. A failed or skipped report, a closed tab, or a session that simply expires records no `LOGOUT`.
- Client-credentials app clients and scopes must be managed in Terraform.

## Security & Privacy Impact

- No password or MFA secret ever reaches CRM services.
- Roles always come from the verified token, never from request fields.
- user-service records the reported sign-out; it does not check that the refresh token was revoked.
- Revoking the refresh token stops it getting new tokens. Access tokens already issued stay valid until they expire for any service that checks tokens locally, as every CRM service does: Cognito refuses revoked tokens only at its own APIs.
- The frontend keeps tokens in the tab's `sessionStorage` (the `oidc-client-ts` default), so they last only as long as the tab. Cross-site scripting is the main risk to them.

## Cost Impact

Cognito is free at this number of users. The trigger Lambda costs almost nothing ([ADR-0040](ADR-0040-cognito-triggers-java-lambda.md)).

## Operational Impact

- User pool, app clients, groups, scopes and triggers are defined in Terraform, including each environment's callback and sign-out URLs.
- An `AUDIT_SEND_FAILED` alarm and a replay runbook (in `services/cognito-triggers/README.md`).

## Validation

- Sign-in without MFA fails.
- In the browser network tab, the `/oauth2/authorize` request carries `code_challenge` and `code_challenge_method=S256`. A frontend unit test checks the same on the request the library builds.
- A token from the wrong app client or with the wrong `token_use` is rejected (ADR-0005 tests).
- A login produces an audit event; with SQS unreachable, the login still succeeds and `AUDIT_SEND_FAILED` is logged.
- A sign-out produces a `LOGOUT` event for the user's `sub`; with user-service unreachable, the sign-out still completes.
- After a sign-out, signing in again asks for the password and MFA.

## References

- [Identity and security](../security/security-model.md#identity-and-security), [authentication events](../security/security-model.md#authentication-events)
- [Open item 20](../architecture/open-items.md#20-sign-in-flow-and-pkce) (resolved by decision 2)
- [ADR-0005](ADR-0005-cognito-security-across-features-1-and-2.md) (Accepted; extended, not superseded)
- [user-service README, Logout](../../services/user-service/README.md#logout)
- RFC 7636 (PKCE); RFC 9700 (OAuth 2.0 Security Best Current Practice); AWS documentation: Amazon Cognito managed login, the authorize, token revocation and logout endpoints, Lambda triggers

## Revision history

- 2026-10-01 21:50 +08:00: proposed.
- 2026-10-13 18:46 +08:00: amended before acceptance, then accepted by the decision owner.
  - **Context:** corrects the claim that `feat/frontend` already signs in through `react-oidc-context`; no branch has that code. Adds logout auditing.
  - **Options:** RFC 9700 for PKCE; a new "Logout auditing" comparison.
  - **Decision 2:** confirmed, which resolves open item 20. Lists the app client's grant, scope, revocation, callback and sign-out settings, and the test.
  - **Decision 5:** adds the sign-out sequence, audit call first.
  - **Consequences, Security & Privacy, Validation:** the logout trade-off, what revocation does and does not stop, token storage in the browser, and the sign-out checks.
