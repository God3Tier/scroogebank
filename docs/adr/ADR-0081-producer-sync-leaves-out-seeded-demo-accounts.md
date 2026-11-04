# ADR-0081: The producer sync leaves out seeded demo accounts

- Status: Proposed (2026-11-04; review pending)
- Decision date/time: 2026-11-04 19:30 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen (proposer and owner, owner of ADR-0045); reviewer: Joseph Yau (Feature 3 owner, whose producer and SFTP harness this touches)
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/transaction-integration/producer-sync-exclusions`
- Related issue/PR: TBD
- Supersedes: [ADR-0045](ADR-0045-producer-account-list-synced-from-crm.md) decision 3 in part (which open accounts the sync copies); decisions 1 and 2 are unchanged
- Superseded by: N/A

## Context

The Client Health presentation uses four synthetic clients, each with one open SGD account. The producer's `-history` flag gives them six months of deterministic transactions, one scenario per account: recurring-credit interruption, transaction-activity decline, sustained net outflow and a healthy control. The analysis is only meaningful if those accounts hold that history and nothing else.

Under ADR-0045 decision 3, `scripts/sync-producer-accounts.sh` copies every open, non-deleted account to the producer. The demo accounts are open, so a sync puts them back on the list. From its next run, every 5 minutes, the producer adds random transactions to them, with dates spread over past months, inside the history's window. That happened on 2026-11-04: a sync shortly after seeding let one regular file add 7 random transactions to the demo accounts. They were deleted from transaction-db by ID before the daily analysis read them.

The sync must still be run whenever accounts change, so "do not run the sync" is not a safe rule.

## Decision Drivers

- Seeded demo accounts get no transactions except their deterministic history, whoever runs the sync and whenever.
- The sync stays the one way the producer's list is set (ADR-0045).
- The demo account IDs stay out of the repository, as the seeding procedure asks.
- No schema, service or Terraform change for presentation data.

## Options Considered

### Option A: an exclude file read by the sync script (chosen)

- Pros: a few lines in the script; the IDs live in a gitignored local file; every later sync honours it.
- Cons: the file is per operator machine. Someone syncing from another clone without it puts the demo accounts back.

### Option B: mark demo accounts in the CRM and filter in the sync query

- Pros: works from every clone.
- Cons: a new account-service column and migration, and a way to set it, only for presentation data.

### Option C: stop the producer container for the presentation

- Pros: no code change.
- Cons: no live transactions for any account; easy to forget to start it again.

### Option D: remove the demo lines from the list on the instance after each sync

- Pros: no code change.
- Cons: manual, and a regular run can fall between the sync and the removal, which is what happened.

## Decision

1. **`scripts/sync-producer-accounts.sh` leaves out the accounts listed in an exclude file.** The file is `scripts/sync-producer-accounts.exclude`, or the path in `PRODUCER_ACCOUNTS_EXCLUDE`. Every account UUID in it is left out, whatever its currency; other text, such as `#` comments, is ignored. A missing or empty file leaves nothing out, so the script behaves as under ADR-0045.
2. **The script says how many accounts it left out**, so an operator sees that the file was applied.
3. **`scripts/sync-producer-accounts.exclude` is gitignored.** The IDs are synthetic, but the seeding procedure keeps them private, and the list belongs to the operator running the presentation.
4. Everything else in ADR-0045 decision 3 stands: open, non-deleted accounts, the 100 most recently opened, the end marker, the entry check and the rename on the instance. The limit of 100 is applied before the exclusion, so a sync may copy fewer than 100 accounts.

## Rationale

Option A is the smallest change that makes every later sync safe for the demo, and it keeps the IDs out of the repository. Option B's schema change would outlive the presentation. Options C and D depend on remembering a manual step at the right moment.

## Consequences

### Positive

- A sync no longer adds random transactions to the seeded demo accounts.
- The demo accounts' history stays exactly as seeded, so the presentation's four scenarios stay as designed.

### Negative

- Only clones that have the exclude file are protected. Whoever runs the sync must have it; the runbook says so.
- Excluded accounts get no regular transactions at all until they are taken out of the file.

## Security & Privacy Impact

- No new route, identity or network path. The exclude file is read locally, and only UUIDs from it are used, as fixed-string matches.
- The demo account IDs stay out of the repository.

## Cost Impact

None.

## Operational Impact

- [deploy-aws.md](../runbooks/deploy-aws.md) §9.5 says how to list accounts to leave out.
- After seeding a demo account, add its ID to the exclude file before the next sync.

## Validation

- The script's exclusion was run on sample sync output with no exclude file, some accounts excluded (one listed with a currency suffix), all accounts excluded, and no accounts: each gave the expected list and count. `bash -n` passes.
- To do on the deployed stack: run the sync with the exclude file and check that `/etc/producer/account-ids` lists no excluded ID.

## References

- [ADR-0024](ADR-0024-transaction-ingestion-sftp-lambda.md), [ADR-0034](ADR-0034-xf3-health-monitor-rules-and-bedrock-boundary.md), [ADR-0045](ADR-0045-producer-account-list-synced-from-crm.md).
