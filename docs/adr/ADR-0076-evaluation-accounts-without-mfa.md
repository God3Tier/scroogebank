# ADR-0076: Evaluation accounts sign in without MFA; a token trigger enforces MFA for everyone else

- Status: Accepted (2026-11-03; decided by Neo Jia Wen)
- Decision date/time: 2026-11-03 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/infrastructure/evaluation-accounts-and-min-tasks`
- Related issue/PR: TBD
- Supersedes: in part, ADR-0025's "MFA required" pool setting (the requirement stays; how it is enforced changes)
- Superseded by: N/A

## Context

The brief requires two default accounts for evaluation, `admin@crm.com` and `agent1@crm.com`, with their passwords written only in the report. The evaluators cannot receive mail at those addresses (so a mailed temporary password never arrives) and should not need to enrol an authenticator, change a password or enter a code.

The user pool requires MFA (`mfa_configuration = "ON"`, ADR-0025), and Cognito cannot exempt single users from that setting. With MFA optional, Cognito challenges only users who already enrolled, and managed login never offers enrolment, so MFA would quietly become opt-in for everyone.

## Decision Drivers

- MFA stays required, and enforced by the server, for every user except the two evaluation accounts.
- The evaluation accounts sign in with a password only, with no first-login password change.
- Their passwords never reach the repository, tfvars or Terraform state.

## Options Considered

### Option A: keep MFA required and pre-enrol TOTP for the evaluation accounts

- Pros: no change to the security model.
- Cons: evaluators must still add an authenticator from a key in the report and enter codes; the brief asks only for a username and password.

### Option B: make MFA optional with no enforcement

- Pros: least work.
- Cons: MFA becomes opt-in for every user, against the specification's MFA requirement.

### Option C: optional MFA in Cognito, enforced by a pre token generation trigger and API Gateway scopes (chosen)

- Pros: MFA is still required for every other user, enforced at the API; the exemption is a group only Terraform fills.
- Cons: a new trigger, an enrolment page in the app, and one more scope on every route.

## Decision

1. **The pool's MFA is optional** when the Cognito triggers are deployed (`enable_cognito_triggers`); without them it stays required, as nothing else would enforce it.
2. **A pre token generation trigger (`TokenScopeHandler`, V2_0 events)** adds the scope `crm/api` to the access token only if the user is in the `MFA_EXEMPT` group or has a TOTP authenticator enrolled (AdminGetUser). It fails closed: if it cannot read the user's MFA settings, the sign-in fails.
3. **Every API Gateway route requires `crm/api`**, except `POST /api/users/me/logout`, so that a sign-out is still audited.
4. **The trigger keeps `MFA_EXEMPT` out of tokens.** It copies the user's other groups across unchanged, so services see only ADMIN and AGENT.
5. **The app enrols TOTP itself.** A signed-in user whose token lacks `crm/api` is sent to an enrolment page, which uses Cognito's AssociateSoftwareToken, VerifySoftwareToken and SetUserMFAPreference with the user's access token. The web client may now issue `aws.cognito.signin.user.admin` for this, may write only `given_name` and `family_name`, and an e-mail change takes effect only once verified. After enrolment the app refreshes the token, which now carries `crm/api`. Every later sign-in asks for a code, because Cognito challenges enrolled users even when MFA is optional.
6. **The evaluation accounts.** Terraform (`mfa_exempt_users`) creates them with no password and no e-mail, and adds them to `MFA_EXEMPT`. `scripts/evaluation-accounts.sh` then sets permanent passwords, typed in or read from the environment. user-service adopts `admin@crm.com` as the root admin when it starts. An admin creates `agent1@crm.com` in the CRM, which adopts the Cognito user and adds it to AGENT.

## Rationale

Option C is the only one that meets the brief without weakening MFA for real users. The scope check sits at API Gateway, which every browser request already passes, so services change nothing. Copying the groups in the trigger avoids Cognito dropping the groups claim and keeps the exemption invisible to services.

## Consequences

### Positive

- Evaluators sign in with a username and password only.
- Every other user must enrol TOTP before reaching any data, and is challenged on every sign-in after that.

### Negative / Trade-offs

- Two accounts can sign in with a password alone. They are for evaluation only, as the brief says, and their passwords appear only in the report.
- A user who has not enrolled can sign in and hold a token without `crm/api`. API Gateway refuses it on every route except sign-out. The services check the token's signature, issuer and groups but not the scope, so a request that bypassed API Gateway from inside the VPC would not be refused for a missing scope. The VPC has no such path for users (ADR-0019, ADR-0071).
- The SPA's access token can call Cognito's user API, for example to change the user's own given or family name in Cognito. The CRM shows the names users-db holds.
- One more Lambda function in the sign-in path. A cold start adds about half a second, within Cognito's 5-second limit.

## Security & Privacy Impact

- MFA: still enforced for all users except `MFA_EXEMPT`, which only Terraform fills. A user cannot add themselves to it: group changes need the admin API, and user-service adds only ADMIN and AGENT.
- Passwords: never in the repository, tfvars or Terraform state; typed into `scripts/evaluation-accounts.sh` at deploy time.
- The trigger's log names the user by the pool's username (a UUID) and holds no attributes.

## Cost Impact

Negligible: one Lambda invocation for each sign-in or token refresh.

## Operational Impact

- The runbook gains the evaluation-account steps: apply, run `scripts/evaluation-accounts.sh`, then create `agent1@crm.com` in the CRM.
- The frontend build reads `VITE_MFA_ENROLMENT` from the crm stack's `frontend_build_env` output.
- Load-test and ZAP users still enrol TOTP during their setup, so their tokens carry `crm/api`.

## Validation

- `TokenScopeHandlerTest` covers enrolled, not-enrolled, exempt and failing lookups, and that `MFA_EXEMPT` never reaches a token.
- `terraform test` in the crm stack: the pool's MFA mode, route scopes, the accounts and their group.
- Frontend tests: the redirect to enrolment and the enrolment page.
- On AWS: `admin@crm.com` and `agent1@crm.com` sign in with a password only and reach their home pages. A new user created by an admin is sent to enrolment, cannot call the API until enrolled, and is asked for a code at the next sign-in.

## References

- [ADR-0025](ADR-0025-identity-cognito-mfa-pkce-service-tokens.md), [ADR-0040](ADR-0040-cognito-triggers-java-lambda.md), [ADR-0019](ADR-0019-edge-entry-chain.md)
- Cognito, [Adding MFA to a user pool](https://docs.aws.amazon.com/cognito/latest/developerguide/user-pool-settings-mfa.html) and [Pre token generation Lambda trigger](https://docs.aws.amazon.com/cognito/latest/developerguide/user-pool-lambda-pre-token-generation.html)
