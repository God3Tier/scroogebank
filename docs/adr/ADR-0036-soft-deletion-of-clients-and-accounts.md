# ADR-0036: Soft deletion of clients and accounts

- Status: Accepted
- Decision date/time: 2026-10-08 19:15 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/client-profile-management/soft-delete`
- Related issue/PR: TBD (pull request from `task/client-profile-management/soft-delete` to `dev`)
- Supersedes: ADR-0035, decision 4 in part (what "has accounts" means when a client is deleted: accounts that are not deleted, rather than accounts that are not `closed`; the rest of decision 4 is unchanged)
- Superseded by: N/A

## Context

The brief defines `DELETE /api/clients/{clientId}` and `DELETE /api/accounts/{accountId}`. Both services implemented them as hard deletes: the row was removed from the database.

The course's requirements Q&A (`Project Requirements Q&A-2627-t1.xlsx`) asks for soft deletion instead:

| ID | Question | Answer |
|---|---|---|
| CPM08 | Do we need to retain deleted client info for compliance? | "Yes - do soft delete" |
| CPM21 | What business rules must hold before an account can be deleted? | "it's the agent who will decide. everything is done as soft delete" |
| CPM25 | Is deletion a hard delete or a soft delete? | "mentioned earlier it was soft deletion" |
| CPM33 | Client deletion: is a soft delete acceptable? | "Soft delete. Refer UM19" |
| UM19 | (users) Is disabling enough, or must records be permanently deleted? | "Soft delete. (All the data will remain inside the system. Your UI or backend will handle how to filter out those deleted/Disabled records. UI should not show any of those.)" |
| UM05 | Must deleted data be retained for compliance? | "Typically banks have some archival policy so data that are deleted are not truly deleted" |

[ADR-0035](ADR-0035-account-ownership-across-services.md) decision 4 anticipated a different change: "until accounts can be closed instead of deleted", after which a client could be deleted once its accounts were `closed`. That treated deleting an account and closing it as the same thing. They are not, and this ADR records the difference.

### Deleted is not the same as closed

`accounts.status` already allows `closed` (alongside `active`, `inactive` and `pending`). It is the brief's "Account Status" field: a business state of an account that exists.

| | Closed (`status = 'closed'`) | Deleted (`deleted_at` set) |
|---|---|---|
| What it means | The account existed and has been closed, for example because the client ended it | The record is removed from the CRM; the row is kept only for the bank's records |
| Visible to agents and admins? | Yes. It still appears in the client's account list, marked closed | No. UM19: "UI should not show any of those" |
| `GET /api/accounts/{id}` | 200, with `status: closed` | 404, the same as an account that never existed |
| Relation to `status` | One value of `status` | Separate from `status`. A deleted account keeps whatever status it had |
| Typical case | A client closes their savings account | An agent deletes an account opened by mistake |
| Audit event | A status change | A delete |

Clients have no lifecycle status at all (`verification_status` records identity verification), so for clients a separate deletion marker is the only option.

## Decision Drivers

- The requirements Q&A asks for soft deletion of clients and accounts.
- Deleted records must not be shown (UM19), to admins or agents.
- An account's status must keep its business meaning.
- Agent scoping and the "missing and foreign look the same" 404 rule (ADR-0005, ADR-0035) must hold.
- Imported transactions must not be left pointing at a removed account (ADR-0035, Negative consequences).
- No new endpoints: the brief's API stays as it is.

## Options Considered

### Option A — Reuse `status = 'closed'` as the delete

- Pros: no schema change for accounts.
- Cons:
  - deleted accounts would still be listed and readable, which breaks UM19, unless every read also hides closed accounts, which would then hide genuinely closed accounts too;
  - it overwrites the status the account had, so "closed by the client" and "deleted by the agent" can no longer be told apart;
  - clients have no status to reuse;
  - the audit trail records a status change, not a delete.

### Option B — A `deleted_at` timestamp on clients and accounts (chosen)

- Pros:
  - one rule for both tables: a row with `deleted_at` set is gone as far as any user is concerned;
  - status keeps its meaning;
  - records when the delete happened;
  - SQLBoiler, which both services use, supports it natively: generated queries skip deleted rows.
- Cons:
  - hand-written SQL must filter deleted rows itself;
  - unique constraints must ignore deleted rows;
  - deleted rows stay in the tables until something purges them.

### Option C — Move deleted rows to archive tables

- Pros: live tables hold only live rows, so no query needs a filter.
- Cons: a second copy of each schema to keep in step; a move across tables on every delete; locally, the receiver's foreign key from `transactions.account_id` (ADR-0006) would block moving an account that has transactions.

### Option D — Keep hard deletes

- Rejected: it contradicts CPM08, CPM21, CPM25 and CPM33.

## Decision

**Option B.**

1. **`DELETE /api/clients/{id}` and `DELETE /api/accounts/{id}` set `deleted_at`** (`TIMESTAMPTZ`; `NULL` means not deleted) and keep the row. Their responses do not change.
2. **Deleting is not closing.** Deleting an account leaves `status` as it was. `closed` stays a business status of an account that exists. No endpoint changes status today; closing an account would be a separate feature, and a closed account would stay visible.
3. **A deleted record is treated as missing everywhere, for admins and agents alike.** Reads, updates, verification, a second delete, account lists and the ownership checks all behave as if the record did not exist: 404, or left out of a list. A missing, a deleted and another agent's record look the same. No endpoint returns or restores a deleted record; restoring one would need a new decision.
4. **How it is enforced:**
   - both services generate their models with SQLBoiler's `add-soft-deletes` setting, so every generated finder and query adds `deleted_at IS NULL`, and `Delete(ctx, exec, false)` sets `deleted_at`;
   - the ownership checks in each service's `security/authorization.go` also use the generated finders, so no hand-written SQL needs the filter; any added later must filter deleted rows itself;
   - updates leave `deleted_at` out, so an update racing a delete cannot undo it.
5. **Uniqueness counts only records that are not deleted.** `uk_clients_email`, `uk_clients_phone` and `uk_accounts_client_type` become partial unique indexes (`WHERE deleted_at IS NULL`), and the duplicate-client check ignores deleted clients. A deleted client's email and phone number can be used to onboard a new client, and a deleted account's type can be opened again for the same client. Otherwise a record nobody can see would block onboarding, and the error would reveal that it exists.
6. **Deleting a client** (supersedes the last sentence of ADR-0035 decision 4). client-service still asks account-service first and refuses with 409 while the client has accounts. "Has accounts" now means accounts that are not deleted, because account-service never lists deleted accounts. **A closed account that is not deleted still blocks the client's deletion.** Deleting a client does not delete its accounts. The rest of ADR-0035 decision 4 is unchanged: the caller's token is forwarded, and no answer within 2 seconds returns 503.
7. **Audit events do not change.** A delete is still recorded as a delete of that client or account.
8. **The schema change is made in each service's `data/V1_init.sql`**, not in a separate migration, because nothing has been deployed. Local databases created before this change must be recreated.
9. **Deleting is not erasure.** These endpoints retain the data, as the bank's archival policy expects (UM05). Erasing a client's personal data, on a data-protection request or when retention ends, remains the separate operation described in [ADR-0022](ADR-0022-audit-tamper-resistance-and-crypto-shredding.md) decision 5 and [ADR-0030](ADR-0030-client-data-envelope-encryption.md) decision 5: remove the client's data from every owning service and delete their data key. That operation and the retention period are not built yet ([open item 21](../architecture/open-items.md#21-erasing-soft-deleted-client-data)).

## Rationale

The Q&A answers settle soft deletion; the remaining choice is how to mark a record deleted. A separate `deleted_at` marker keeps the record's business state intact, works the same way for clients and accounts, and is supported by the ORM both services already use, so most queries get the filter without anyone writing it. Treating deleted records exactly like missing ones keeps the existing 404 behaviour and agent scoping, and gives UM19's "UI should not show any of those" for free, since the API never returns them.

Keeping "a client with accounts cannot be deleted" means only the meaning of "accounts" changes. Counting closed accounts as blocking is the stricter reading: an agent deletes a client's accounts, closed or not, before deleting the client, so a client is never hidden while one of its accounts is still visible.

## Consequences

### Positive

- Meets CPM08, CPM21, CPM25 and CPM33.
- **Deleting an account no longer orphans imported transactions.** The account row stays, so the receiver's foreign key (ADR-0006) is never violated, and if transactions later move to their own database they still refer to an account that exists. user-service returns 404 for a deleted account's transactions, because its ownership check (`GET /api/accounts/{id}`) returns 404. This resolves the corresponding Negative consequence of ADR-0035.
- `status` keeps one meaning.
- Admins deleting, updating or verifying a missing or deleted client, or deleting a missing or deleted account, now get 404. These calls previously surfaced the database's "no rows" error as 500.

### Negative / Trade-offs

- Any hand-written SQL added later must remember to filter deleted rows. Generated queries do it automatically, and regenerating the models without `add-soft-deletes` changes `Delete`'s signature, so the build fails rather than silently going back to hard deletes.
- A deleted client's personal data stays in clients-db and its backups until erasure exists (decision 9).
- Tables keep growing; nothing purges deleted rows.
- Account responses now include `deleted_at`, always `null` for a returned account, because the endpoints serialize the generated model.
- The receiver's linking trigger (ADR-0006) still matches imported transactions to a deleted account. They are stored but cannot be read through user-service. Whether to import them differently is for Feature 3.
- ADR-0035's race on client deletion is unchanged: an account opened between the check and the delete is left on a deleted client.

## Security & Privacy Impact

- Deleted records cannot be read through any endpoint, by admins or agents. Missing, deleted and foreign records all return 404, so nothing can be enumerated, and reusing a deleted client's email or phone number reveals nothing.
- Retaining personal data increases what a database or backup leak exposes. It is bounded by the planned envelope encryption (ADR-0030) and by the erasure operation and retention period still to be decided (decision 9). Retention must stay justified by the bank's records obligations and must end.
- Only database operators can see deleted rows.

## Cost Impact

Negligible: one nullable column per table and partial indexes. Rows are kept instead of removed, which at project scale is a few kilobytes.

## Operational Impact

- Local databases created before this change lack `deleted_at` and must be recreated: `make db-reset` in each service, and `docker compose -f services/sftp/compose.yaml down -v` for the SFTP stack's shared database.
- Nothing is deployed, so there is no data to migrate. Once a service is deployed, later schema changes need versioned migrations (ADR-0028).
- `add-soft-deletes = true` must stay in both `sqlboiler.toml` and `sqlboiler.docker.toml` of both services.

## Validation

- Repository tests in both services: a delete keeps the row with `deleted_at` set and leaves an account's status unchanged; deleting twice returns not found; reads, updates, verification and lists skip deleted records; a deleted client's email and phone and a deleted account's type can be reused; the duplicate-client check ignores deleted clients.
- Database-backed ownership tests: the owning agent gets 404 for their own deleted client or account.
- Controller test: updating a missing or deleted client returns 404.
- End to end (not yet run): deleting an account makes `GET /api/accounts/{id}` and its transactions return 404; a client whose accounts are all deleted can be deleted; a client with a closed but undeleted account returns 409.

## References

- [ADR-0035](ADR-0035-account-ownership-across-services.md) (decision 4, partly superseded), [ADR-0017](ADR-0017-microservices-per-business-area.md), [ADR-0006](ADR-0006-transaction-account-linkage-and-access.md), [ADR-0022](ADR-0022-audit-tamper-resistance-and-crypto-shredding.md), [ADR-0030](ADR-0030-client-data-envelope-encryption.md)
- Requirements Q&A rows CPM08, CPM21, CPM25, CPM33, UM05 and UM19
- `project_plan.md` §12 (Feature 2) and §17 (deletion obligations)
- [Right to erasure](../security/security-model.md#right-to-erasure-nfr-8-gdpr), [open item 21](../architecture/open-items.md#21-erasing-soft-deleted-client-data)
