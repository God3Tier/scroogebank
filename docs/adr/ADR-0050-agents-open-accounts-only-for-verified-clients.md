# ADR-0050: Agents open accounts only for verified clients

- Status: Proposed
- Decision date/time: 2026-10-20 21:30 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/base-features/verified-clients-only-accounts`
- Related issue/PR: TBD (pull request from `task/base-features/verified-clients-only-accounts` to `dev`)
- Supersedes: on acceptance, [ADR-0035](ADR-0035-account-ownership-across-services.md) decision 2 in part (account creation also checks that the client is verified when the caller is an agent; the lookup, its fail-closed behaviour and the owner it copies are unchanged)
- Superseded by: N/A

## Context

An agent can open a bank account for any client they own, verified or not. account-service checks only that the client exists and that the caller may see it ([ADR-0035](ADR-0035-account-ownership-across-services.md) decision 2).

The requirements Q&A, as [ADR-0041](ADR-0041-client-identity-documents-and-manual-verification.md) records, says verification happens at onboarding (CPM13), and an unverified client is treated as inactive but stays visible to the agent (CPM24). Opening an account for a client whose identity nobody has checked contradicts that: the account would be the client's first product before onboarding is finished. A bank would expect identity checks before an account is opened.

The decision owner asked (2026-10-20) that agents can open accounts only for verified clients. Admins are not restricted.

## Decision Drivers

- An unverified client is inactive (CPM24), so an agent should not open an account for one.
- The rule must hold whoever calls the API, not only in the frontend.
- No new call between services: account creation already reads the client's profile.
- Accounts that already exist must not change.
- Admins keep the access they have: "Admins can access all client profiles and their accounts" ([ADR-0005](ADR-0005-cognito-security-across-features-1-and-2.md) decision 4).

## Options Considered

### Option A — account-service checks the client's verification, from the lookup it already makes (chosen)

- Pros: enforced for every caller of `POST /api/accounts`; the profile that `GET /api/clients/{id}` returns already has `verification_status`, so there is no new call; one place to change.
- Cons: account-service depends on client-service's name and values for the status (`verification_status`, `VERIFIED`).

### Option B — The frontend hides Open account for unverified clients, and nothing else

- Pros: no backend change.
- Cons: anyone with an agent's token can still call the API directly. A rule only the UI enforces is not a rule.

### Option C — client-service refuses to show an unverified client to account-service

- Pros: account-service would not change.
- Cons: client-service cannot tell an account lookup from any other read made with the same user token, and agents must still see their unverified clients (CPM24).

### Option D — Apply the rule to admins too

- Pros: one rule for everyone.
- Cons: not what was asked; admins would lose a way to open an account when a client's verification is pending for a reason outside the CRM.

## Decision

**Option A.**

1. **Creating an account reads the client's `verification_status`** from the same `GET /api/clients/{client_id}` response that supplies `created_by_agent_id` (ADR-0035 decision 2). A status of `VERIFIED`, in any case (clients verified before ADR-0041 hold `verified`), means verified. A missing or unknown status means not verified, so the check fails closed.
2. **An agent's request for an unverified client is refused with 403**, and nothing is created. The body says `verify the client before opening an account`. The caller's role comes from the verified token, never from the request.
3. **Admins are not restricted.** They may open an account for any client they can see, verified or not.
4. **The order of checks is unchanged:** a missing, deleted or another agent's client is still 404, and no answer from client-service within 2 seconds is still 503. Verification is checked only once the client is found.
5. **Existing accounts are unchanged.** Accounts that unverified clients already have stay as they are, and nothing else about them is restricted.
6. **The frontend follows the same rule.** On an unverified client's page, an agent sees "Verify this client before opening an account." instead of Open account. An admin sees Open account as before. A 403, for a page loaded before the client's state changed, shows the same message.

## Rationale

account-service already asks client-service about the client, with the caller's token, before every account it opens. Reading one more field from that answer enforces the rule for every caller, adds no call and no dependency, and keeps the frontend a convenience rather than the control.

403 rather than 409: the request is valid and the client exists, but this caller may not do it for this client, while an admin may. The frontend can tell it apart from the 409 a duplicate account type returns.

Treating any status other than `VERIFIED` as not verified means a client-service change that renames or drops the field refuses agents rather than letting them through.

## Consequences

### Positive

- An agent cannot open an account for a client whose identity nobody has checked, whether through the UI or the API.
- No new call between services, and no schema change.

### Negative / Trade-offs

- account-service now depends on `verification_status` and its `VERIFIED` value in client-service's profile response. If client-service renames either, agents cannot open accounts until account-service follows.
- No endpoint undoes a verification, and editing a verified client's details doesn't reset it (ADR-0041, [open item 22](../architecture/open-items.md#22-identity-documents-wrong-uploads-and-re-verification)). So a client verified once can be given accounts after their details change.
- Admins can still open accounts for unverified clients. Whether that should be audited or limited is not decided here.

## Security & Privacy Impact

- The check uses the role from the verified Cognito token (`cognito:groups`), as account-service's other scoped routes do. A request cannot claim to be an admin.
- The 403 says only that the client must be verified first. The caller can already see the client's status on its page, so it reveals nothing new. Missing and foreign clients still return 404, so the 403 cannot be used to find other agents' clients.
- A refused request is audited like any other request on the account routes: account-service's audit middleware sends an event with the caller, the client and the status code, 403.

## Cost Impact

None. No new resources or calls.

## Operational Impact

- account-service and the frontend must be deployed together, or an older frontend shows Open account and gets the 403 message on submit.
- No data migration.

## Validation

- **Unit tests, account-service:**
  - the client lookup reads `VERIFIED`, lower-case `verified`, `UNVERIFIED`, and a missing status (not verified);
  - account creation refuses an agent for an unverified client without writing, and lets an admin open one;
  - the controller maps the refusal to 403.
- **Unit tests, frontend:** an agent on an unverified client's page sees the message and no Open account; an admin sees Open account; a 403 shows the message.
- **Mutation checks:** without the service check, or without hiding the button, the tests fail.
- **End to end (not yet run):**
  - an agent opens an account for a verified client;
  - an agent's direct `POST /api/accounts` for an unverified client returns 403;
  - an admin opens one for an unverified client.

## References

- [ADR-0035](ADR-0035-account-ownership-across-services.md) (decision 2, partly superseded on acceptance), [ADR-0041](ADR-0041-client-identity-documents-and-manual-verification.md), [ADR-0005](ADR-0005-cognito-security-across-features-1-and-2.md), [ADR-0036](ADR-0036-soft-deletion-of-clients-and-accounts.md)
- Requirements Q&A rows CPM13 and CPM24, as recorded in ADR-0041
- `services/account-service/README.md`, `frontend/README.md`, `docs/feature-plans/feature-2_plan.md` (F2-ACC-02)

## Revision history

- 2026-10-20 21:30 +08:00: proposed.
