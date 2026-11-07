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
- Everyone who runs the sync gets the same list, from any clone.
- No schema, service or Terraform change for presentation data.

## Options Considered

### Option A: the demo account IDs written into the sync script (chosen)

- Pros: a few lines in the script; every sync from every clone leaves them out; the list is reviewed in PRs like any other change.
- Cons: the IDs belong to one deployment. After a teardown (ADR-0058) the reseeded accounts have new IDs, and the list must be updated.

### Option A2: an exclude file each operator keeps locally, gitignored

- Pros: the IDs stay out of the repository.
- Cons: only clones with the file are protected, so two operators' syncs can give different lists. The IDs are dummy accounts, so keeping them private gains nothing.

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

1. **`scripts/sync-producer-accounts.sh` leaves out the accounts in its `demo_accounts` list**: the four Client Health demo accounts, each commented with its scenario. They are left out whatever their currency.
2. **The script says how many accounts it left out**, so an operator sees that the list was applied.
3. **The IDs are committed.** They are dummy accounts holding synthetic data only, and every operator's sync must give the same list.
4. **When the demo accounts are reseeded with new IDs**, as after a teardown, the list is updated in the same change.
5. Everything else in ADR-0045 decision 3 stands: open, non-deleted accounts, the 100 most recently opened, the end marker, the entry check and the rename on the instance. The limit of 100 is applied before the exclusion, so a sync may copy fewer than 100 accounts.

## Rationale

Option A is the smallest change that makes every later sync safe for the demo, from any clone. Option A2 protects only the clones that have the file. Option B's schema change would outlive the presentation. Options C and D depend on remembering a manual step at the right moment.

## Consequences

### Positive

- A sync no longer adds random transactions to the seeded demo accounts.
- The demo accounts' history stays exactly as seeded, so the presentation's four scenarios stay as designed.

### Negative

- The list belongs to one deployment's accounts. After a teardown and reseed, it must be updated, or the new demo accounts get random transactions again.
- Excluded accounts get no regular transactions at all until they are taken out of the list.

## Security & Privacy Impact

- No new route, identity or network path. The IDs are fixed strings in the script, matched as fixed strings.
- The committed IDs belong to dummy accounts with synthetic data only; no client data is involved.

## Cost Impact

None.

## Operational Impact

- [deploy-aws.md](../runbooks/deploy-aws.md) §9.5 says the sync leaves out the demo accounts.
- After seeding demo accounts with new IDs, update `demo_accounts` before the next sync.

## Validation

- The script's exclusion was run on sample sync output with demo and other accounts (one demo account in USD), no demo accounts, only demo accounts, and no accounts: each gave the expected list and count. `bash -n` passes.
- To do on the deployed stack: run the sync and check that `/etc/producer/account-ids` lists no demo account.

## References

- [ADR-0024](ADR-0024-transaction-ingestion-sftp-lambda.md), [ADR-0034](ADR-0034-xf3-health-monitor-rules-and-bedrock-boundary.md), [ADR-0045](ADR-0045-producer-account-list-synced-from-crm.md), [ADR-0058](ADR-0058-every-deployment-ends-with-full-teardown.md).
