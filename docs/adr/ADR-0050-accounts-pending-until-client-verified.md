# ADR-0050: Accounts stay Pending until the client is verified; agents change account status

- Status: Proposed
- Decision date/time: 2026-10-20 22:10 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/base-features/verified-clients-only-accounts`
- Related issue/PR: TBD (pull request from `task/base-features/verified-clients-only-accounts` to `dev`)
- Supersedes: on acceptance, [ADR-0035](ADR-0035-account-ownership-across-services.md) decision 2 in part (account creation also reads the client's verification, and an agent may open only a Pending account for an unverified client; the lookup, its fail-closed behaviour and the owner it copies are unchanged)
- Superseded by: [ADR-0059](ADR-0059-admins-read-only-on-client-data.md) (decision 7: admins no longer open or delete accounts; the other decisions are unchanged)

## Context

Opening an account checks only that the client exists and that the caller may see it ([ADR-0035](ADR-0035-account-ownership-across-services.md) decision 2). An agent can open an Active account for a client nobody has verified. And once an account is open, nothing can change its status: the brief defines only `POST /api/accounts` and `DELETE /api/accounts/{accountId}`.

The brief lists an account's status as "e.g., Active, Inactive, Pending"; the CRM also allows Closed ([ADR-0036](ADR-0036-soft-deletion-of-clients-and-accounts.md)). The requirements Q&A (`Project Requirements Q&A-2627-t1.xlsx`) does not say that opening an account needs a verified client. What it says nearby:
- **CPM13:** verification is "only for client onboarding", not for "the CRUD of the client account"; the agent verifies, and "only when he/she is satisfied then the info is stored in the system".
- **The follow-up rows under CPM24:** "if client is not verified then the client is considered inactive"; "keep the data of inactive client - agent should still see so that they can follow up"; and "the system can handle the workflow e.g: doc submitted, account opened, account active".
- **UM18**, and the same rows under CPM24: "Admin dont have write access on client related activities. Admin can't create new client account"; "client account can only be managed by agent. admin can only manage agent".
- **CPM16** asked whether accounts need an update API; the answer was "Clarify/Rephrase your question".

So the rule below is the decision owner's choice (2026-10-20), not a requirement. It follows the "account opened, account active" workflow, and treats an unverified client as inactive.

## Decision Drivers

- An unverified client is inactive (CPM24), so their accounts should not be Active.
- Follow the Q&A's workflow: an account can be opened during onboarding and become active later.
- The agent, not the system, decides when an account becomes Active.
- Rules must hold whoever calls the API, not only in the frontend.
- Admins do not manage client accounts (UM18).
- No new call between services, and no schema change.

## Options Considered

### Option A — Refuse an agent's account for an unverified client

- Pros: simple; no account exists before verification, as CPM13's "only when he/she is satisfied then the info is stored" suggests.
- Cons: contradicts "doc submitted, account opened, account active"; the agent cannot set up the client's accounts while waiting for their documents. This ADR's first draft chose it; the decision owner replaced it before acceptance.

### Option B — Open as Pending; client-service activates the accounts when it verifies the client

- Pros: follows the workflow, with no step for the agent.
- Cons:
  - verifying would need a call from client-service to account-service;
  - a column to tell "Pending until verified" from Pending chosen on purpose, so a migration;
  - a retry path when that call fails, because Verify cannot be repeated (409).

### Option C — Open as Pending, with no way to activate

- Pros: small.
- Cons: those accounts stay Pending forever, since nothing changes an account's status.

### Option D — Open as Pending; the agent activates accounts, and can change any account's status once the client is verified (chosen)

- Pros:
  - follows the workflow, and the agent decides;
  - no new call between services and no schema change;
  - a failed activation is redone by hand on the accounts page.
- Cons: a new endpoint the brief does not define; one more step for the agent after verifying.

## Decision

**Option D.**

1. **Opening.** For an unverified client, an agent may open an account only as **Pending**. account-service reads `verification_status` from the client lookup it already makes (ADR-0035 decision 2); `VERIFIED` in any case means verified (clients verified before ADR-0041 hold `verified`), and a missing or unknown status means not verified. Any other status for an unverified client is refused with **403** `an unverified client's account can only be opened as pending`, and nothing is created. The client page offers only Pending for an unverified client.
2. **Changing a status.** `PATCH /api/accounts/{accountId}` with `{"status": "ACTIVE"}` changes an account's status, and nothing else:
   - to any of Active, Inactive, Pending or Closed, in any direction;
   - only by the agent who owns the account (the existing ownership check; others get 404);
   - only once its client is verified; otherwise **403** `verify the client before changing an account's status`;
   - never by an admin: **403** `admins do not manage client accounts` (UM18);
   - a deleted account is 404 (ADR-0036); the client lookup answers 404 and 503 as for opening.
3. **After a verification.** If the client has Pending accounts, the client page asks the agent whether to make them all Active. Each is a `PATCH`. If some fail, the page offers those again and points to the accounts page. Admins are not asked.
4. **The accounts page** ("Manage accounts and transactions") shows each account's status. For a verified client, the agent can change it there; for an unverified client it says the client must be verified first. Admins see the status only.
5. **Audit.** The status change is audited like the other account routes, as `UPDATE`, and its payload also records `attribute_name: "status"`, `before_value` and `after_value`, the brief's audit attributes for a change. The row is locked while it is updated, so the before value is exact. The activity feed shows it as "changed an account's status from Pending to Active". Refused requests are recorded with their status code and left out of the feed, as before.
6. **Existing accounts are unchanged,** including Active accounts that unverified clients already have. They cannot be changed by hand until the client is verified.
7. **Admins** keep what they can do today when opening and deleting accounts. Whether they should, given UM18, is [open item 23](../architecture/open-items.md#23-admin-writes-to-client-data).

## Rationale

Opening as Pending keeps the Q&A's workflow and still never shows an unverified (inactive) client with an Active account opened since this rule. Letting the agent activate, rather than client-service, avoids a call between services, a migration and a retry path. The same endpoint gives agents what the brief lacks: a way to mark an account Inactive or Closed without deleting it, which ADR-0036 says is a different thing.

Agent-only follows UM18. Checking verification in account-service, from the lookup it already makes, keeps the frontend a convenience rather than the control.

## Consequences

### Positive

- An agent cannot open an Active account for an unverified client, or change an unverified client's accounts, through the UI or the API.
- Account status can change after opening, so Closed and Inactive become usable without deleting anything.
- Status changes are audited with their before and after values.

### Negative / Trade-offs

- A new endpoint beyond the brief's account API.
- account-service depends on `verification_status` and its `VERIFIED` value in client-service's profile response. If client-service renames either, agents can open only Pending accounts and change no status until account-service follows.
- No endpoint undoes a verification, and editing a verified client's details doesn't reset it (ADR-0041, [open item 22](../architecture/open-items.md#22-identity-documents-wrong-uploads-and-re-verification)).
- The transaction import still links transactions to accounts of any status; only deleted accounts are skipped (ADR-0037). The producer's account list leaves out Closed accounts at its next sync (ADR-0045).

## Security & Privacy Impact

- The role comes from the verified Cognito token, as on account-service's other scoped routes; a request cannot claim to be an agent or an admin.
- The ownership check runs before anything else, so an agent asking about another agent's account gets 404, and a 403 cannot be used to find other agents' accounts.
- The audit payload holds statuses and IDs only, no client personal data.

## Cost Impact

None.

## Operational Impact

- Deploy account-service and the frontend together. An older frontend still offers Active for an unverified client and gets the 403 message.
- No data migration.

## Validation

- **Unit tests, account-service:**
  - the lookup reads `VERIFIED`, lower-case `verified`, `UNVERIFIED` and a missing status;
  - opening: an agent's Active account for an unverified client is refused without a write; Pending succeeds; an admin is not restricted;
  - status change: admins refused, a missing account 404, an unverified client refused without a write, a verified client's account changed, with the status before and after returned and recorded for the audit;
  - the repository against PostgreSQL: the old status is returned and the new one stored, and a deleted account is not found;
  - the audit middleware puts the before and after values in the payload only when a change was recorded.
- **Unit tests, frontend:**
  - an unverified client's form offers only Pending;
  - after Verify, the prompt makes the Pending accounts Active, offers again those that failed, and is not shown to admins;
  - the accounts page lets an agent change a verified client's account, and shows neither unverified clients nor admins a control;
  - the activity feed describes the change.
- **Mutation checks:** without each check (unverified, admin, the Pending exception, the form's restriction, the page's control, the admin prompt) a test fails.
- **End to end (not yet run):** an agent opens a Pending account for an unverified client, verifies the client, makes the account Active, then closes it; the activity feed shows both changes; an admin's `PATCH` returns 403.

## References

- [ADR-0035](ADR-0035-account-ownership-across-services.md) (decision 2, partly superseded on acceptance), [ADR-0036](ADR-0036-soft-deletion-of-clients-and-accounts.md), [ADR-0041](ADR-0041-client-identity-documents-and-manual-verification.md), [ADR-0037](ADR-0037-transactions-database-and-account-linking.md), [ADR-0045](ADR-0045-producer-account-list-synced-from-crm.md)
- CS301 project specification v18.0, Feature 2 "Create Account" and the account fields; requirements Q&A rows CPM13, CPM16, CPM24 (with its follow-up rows) and UM18
- [Open item 23: admin writes to client data](../architecture/open-items.md#23-admin-writes-to-client-data)
- `services/account-service/README.md`, `frontend/README.md`, `docs/feature-plans/feature-2_plan.md` (F2-ACC-02)

## Revision history

- 2026-10-20 21:30 +08:00: proposed as "Agents open accounts only for verified clients" (Option A).
- 2026-10-20 22:10 +08:00: rewritten by the decision owner before acceptance, after reading the Q&A: accounts open as Pending until the client is verified, and agents change account status (Option D). Renamed from `ADR-0050-agents-open-accounts-only-for-verified-clients.md`.
