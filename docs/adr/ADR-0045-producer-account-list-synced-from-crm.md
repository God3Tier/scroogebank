# ADR-0045: The simulated bank's producer uses an account list synced from the CRM

- Status: Accepted (2026-11-01; accepted for the team by Neo Jia Wen)
- Decision date/time: 2026-10-16 22:48 +08:00 (Asia/Singapore); revised 2026-10-24 17:35 +08:00 so each transaction takes its account's currency (decisions 1 and 3)
- Decision owner(s): Neo Jia Wen; Joseph Yau (Feature 3 owner, whose producer and SFTP harness this changes; agreement pending)
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/transaction-integration/producer-account-sync`
- Related issue/PR: TBD
- Supersedes: N/A (extends [ADR-0024](ADR-0024-transaction-ingestion-sftp-lambda.md) decision 1's file generator)
- Superseded by: N/A

## Context

The producer stands in for the bank's core banking system on the simulated external SFTP server ([ADR-0024](ADR-0024-transaction-ingestion-sftp-lambda.md) decision 1). It writes transactions for the account IDs it is given in `CRM_ACCOUNT_IDS`, and without any it writes random `ACC-000123` references, which the import stores as `Unmatched` ([ADR-0037](ADR-0037-transactions-database-and-account-linking.md)).

On the deployed server, the IDs came from the sftp-server stack's `crm_account_ids` variable, which has two problems:

- **The list cannot follow the CRM.** Accounts are opened in the deployed app after the stack exists. Each change to the list needs a Terraform apply that replaces the instance, because the list is in its startup script.
- **With no list, every file is unusable.** Every 5 minutes the import stores a file of `Unmatched` rows that no agent or admin sees, so the demo shows nothing.
- **Every transaction is in SGD, whatever its account's currency.** account-service accepts any three-letter currency, and the import checks only that a row's code has three letters, not that it matches the account's ([ADR-0037](ADR-0037-transactions-database-and-account-linking.md)). An account opened in USD would list SGD transactions, and the dashboard's single-currency check ([ADR-0038](ADR-0038-dashboard-metric-definitions.md)) compares transactions only with each other, so it would add them up without noticing.

The producer cannot look the accounts up itself. It runs in its own VPC, as a third party would, and account-service's `/internal` routes are reachable only inside the CRM's VPC ([ADR-0037](ADR-0037-transactions-database-and-account-linking.md)).

## Decision Drivers

- The producer's regular files use only accounts that exist in the CRM, and the producer publishes nothing when there are none.
- A transaction is in its account's currency.
- The simulated external system stays independent: it never calls the CRM, and no CRM route is exposed outside its VPC.
- The list can change without a Terraform apply or a new instance.
- An error reading the CRM must never be taken for "no accounts".

## Options Considered

### Option A: publish nothing when `crm_account_ids` is empty

- Pros: a few lines in the producer.
- Cons: the list is still fixed at deploy time, and changing it still replaces the instance.

### Option B: a file on the instance, synced from the CRM by an operator script (chosen)

- Pros: the producer still never calls the CRM; the list follows the CRM whenever the script runs; the instance is not replaced.
- Cons: someone must run the script after accounts change.

### Option C: the producer asks account-service for the accounts

- Pros: fully automatic.
- Cons: needs a new account-service route that lists account IDs, a Cognito app client and scope for the producer, and a route to `/internal` from outside the CRM's VPC, which would expose a list of every account ID. The simulated external system would depend on the CRM.

## Decision

1. **The producer reads its accounts from a file on every run** when `CRM_ACCOUNT_IDS_FILE` is set, as on the deployed server. The file holds entries separated by new lines, commas or spaces. An entry is `<id>:<currency>`, and the account's transactions are in that currency; a bare `<id>` is in SGD, the deployment's currency, as are random external references.
   - When the file lists no account, the regular run publishes nothing and logs why.
   - When the file cannot be read, the run publishes nothing and logs the error; the next run tries again.
   - Fault files ([ADR-0024](ADR-0024-transaction-ingestion-sftp-lambda.md) decision 1) are asked for on purpose, so they are published even with an empty list, on random external references.
   - Without `CRM_ACCOUNT_IDS_FILE`, as in the local Compose stack, `CRM_ACCOUNT_IDS` works as before, including random references when it is empty. It takes the same entries, so existing lists of bare IDs still work.
2. **The deployed server keeps the file at `/etc/producer/account-ids`**, the sftp-server stack's `producer_account_ids_file` output. The startup script creates it empty, so the producer publishes nothing until the first sync. The container mounts the file's directory read-only, not the file: the sync replaces the file by renaming it, which a single-file mount would not see. The `crm_account_ids` variable is removed.
3. **`scripts/sync-producer-accounts.sh` copies the list from the CRM**:
   - it reads the accounts database with `scripts/db-run-sql.sh` from inside the CRM's VPC;
   - it takes the accounts that are neither deleted ([ADR-0036](ADR-0036-soft-deletion-of-clients-and-accounts.md)) nor closed, the 100 most recently opened, each with its currency. A closed account takes no new transactions; 100 keeps the Run Command request small;
   - each entry is tagged in the query output and the output ends with a marker. Without the marker, the script stops and leaves the list unchanged, so output it could not read is never taken for "no accounts";
   - only entries that match a UUID, a colon and three capital letters reach the instance;
   - it writes the list through SSM Run Command (`AWS-RunShellScript`) to a file beside the list, then renames it over the list, so the producer never reads half a list.

   An operator runs it after opening, closing or deleting accounts, and after the instance is replaced.

## Rationale

Option B gives the demo realistic, linked transactions without making the simulated bank depend on the CRM. The data still moves the way an onboarding feed from the CRM to a core banking system would: by an operator, out of band. Option C's automation is not worth exposing account IDs outside the VPC or adding a service identity for test data.

## Consequences

### Positive

- After a sync, every regular file links to real accounts, so agents and admins see its transactions.
- An account's transactions are in its own currency. If accounts in more than one currency are synced, the dashboard sees transactions in more than one currency and leaves their value out ([ADR-0038](ADR-0038-dashboard-metric-definitions.md)), as it should.
- With no accounts, no file is written. The import has nothing to store, and the SFTP server no longer fills with `Unmatched` files.
- Changing the list no longer replaces the instance.

### Negative

- The list is only as fresh as the last sync. A transaction for an account deleted since then is stored as `Unmatched`, which is harmless.
- An account whose currency changes after a sync gets transactions in its old currency until the next sync, and the import rejects them ([ADR-0061](ADR-0061-imported-transactions-in-their-accounts-currency.md)).
- A new instance starts with an empty list, so the script must be run again after the sftp-server stack replaces it.
- Regular files still include about 1% invalid rows on purpose (`transaction.GenerateRandomSet`); this decision does not change that.

## Security & Privacy Impact

- No new route, identity or network path into the CRM. The script uses the operator's own AWS access, as `db-run-sql.sh` and Session Manager already do.
- Only account UUIDs and their currency codes leave the CRM, and only to the team's own simulated server. They contain no client data.
- The instance only accepts entries that match a UUID and a three-letter code, so the database's content cannot change the command run on it.

## Cost Impact

None. SSM Run Command on an EC2 instance is free, and each sync runs one short ECS task, as `db-run-sql.sh` already does.

## Operational Impact

- [deploy-aws.md](../runbooks/deploy-aws.md) §9.5 adds the sync, and §9.1–9.2 drop `crm_account_ids`.
- Applying the sftp-server stack with this change replaces the instance once, because its startup script changes. The host key, and so its fingerprint, stays the same.

## Validation

- Producer tests: the file is read again on every run; a run with a synced list uses only its accounts, each in its own currency; a bare ID is in SGD; an empty or missing list publishes nothing; without a file, the old behaviour stays.
- Terraform test (`tests/producer.tftest.hcl`): the startup script passes `CRM_ACCOUNT_IDS_FILE`, mounts the directory read-only, and passes no fixed list.
- The sync script's query was run against account-service's schema: it leaves out closed and deleted accounts, and gives each account as `<id>:<currency>` (rerun 2026-10-24 on Postgres 16).
- Deployed stack, 2026-10-24: the sync wrote `<id>:<currency>` entries to `/etc/producer/account-ids`, and the producer's files were imported; a USD account's transactions were listed in USD, and the SGD accounts' in SGD.

## References

- [ADR-0024](ADR-0024-transaction-ingestion-sftp-lambda.md), [ADR-0036](ADR-0036-soft-deletion-of-clients-and-accounts.md), [ADR-0037](ADR-0037-transactions-database-and-account-linking.md), [ADR-0038](ADR-0038-dashboard-metric-definitions.md).
