# ADR-0061: The import rejects a transaction not in its account's currency

- Status: Proposed
- Decision date/time: 2026-10-24 18:30 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen; Joseph Yau (Feature 3 owner and Feature 2 primary developer, whose receiver and account-service this changes; agreement pending)
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/transaction-integration/producer-account-currency`
- Related issue/PR: TBD
- Supersedes: [ADR-0037](ADR-0037-transactions-database-and-account-linking.md) decision 2's response body and decision 4's rule that every row whose account is returned is `Matched`, only
- Superseded by: N/A

## Context

The import links each row to a CRM account through account-service's lookup ([ADR-0037](ADR-0037-transactions-database-and-account-linking.md)). The lookup returns each account's client but not its currency, and the import checks only that a row's currency, if any, has three letters. So a row in USD on an SGD account, or with no currency at all, is stored as `Matched`:

- the client's transactions page lists it under the account, in a currency the account is not held in;
- the dashboard's transaction value ([ADR-0038](ADR-0038-dashboard-metric-definitions.md)) adds amounts only when the window's transactions share one currency. It compares the transactions with each other, not with their accounts, so a file entirely in the wrong currency is added up without notice.

The producer now writes each account's transactions in its currency ([ADR-0045](ADR-0045-producer-account-list-synced-from-crm.md), revised 2026-10-24). But the producer stands in for a core banking system the CRM does not control. The check belongs where the file enters the CRM.

## Decision Drivers

- A `Matched` transaction is in its account's currency, whatever sent the file.
- A wrong row is rejected and audited the way other invalid rows are, so the file's `FILE_PROCESSING_COMPLETED` event says what was refused and why.
- Fail closed ([ADR-0037](ADR-0037-transactions-database-and-account-linking.md) decision 5): "could not check" never becomes an answer.
- Deploying the services in either order loses no transaction.

## Options Considered

### Option A: no check in the import

- Pros: no change.
- Cons: a mismatched row is stored as `Matched` and reaches the client's page and the dashboard.

### Option B: store a mismatched row as `Unmatched`

- Pros: nothing is dropped.
- Cons: `Unmatched` means the account does not exist. Here it does, so the row would be wrongly labelled and hidden from the agent without saying why.

### Option C: fill in the account's currency when the row has none

- Pros: accepts more rows.
- Cons: guesses what the source meant; an amount with no currency cannot be taken to be in any particular one.

### Option D: reject a mismatched row, or a matched one with no currency (chosen)

- Pros: the same handling as every other invalid row; the audit event names the row and the reason; stored data never contradicts its account.
- Cons: a row is refused that a person might have corrected. The source must re-send it.

## Decision

1. **account-service's lookup returns each account's currency:** `{"accounts": [{"id", "client_id", "currency"}]}`. The field is added; existing callers are unaffected.
2. **A row whose account is returned must be in the account's currency.** The import upper-cases the row's code first, as account currencies are upper case. A row with no currency, or another one, is rejected: it is counted in `records_rejected` and listed in the file's `FILE_PROCESSING_COMPLETED` event with the row's number and the reason, and the file moves to `rejected/`, like a file with any other invalid row ([ADR-0024](ADR-0024-transaction-ingestion-sftp-lambda.md) decision 4). The file's other rows are still imported.
3. **A row whose account is not returned is unchanged:** `Unmatched`, with or without a currency, as there is no account to compare it with.
4. **A lookup answer that gives an account no currency is a lookup failure** (ADR-0037 decision 5): nothing from the file is saved and the file waits for the next poll. So a receiver deployed before account-service holds files rather than rejecting every matched row.

## Rationale

The import already refuses rows it cannot trust and says why. A transaction in the wrong currency is one more such row, and refusing it keeps a wrong amount off the client's page and out of the dashboard's totals, as [ADR-0038](ADR-0038-dashboard-metric-definitions.md) refuses a cross-currency sum. Filling in a currency, or calling the account unknown, would each store something untrue.

## Consequences

### Positive

- Every `Matched` transaction is in its account's currency, so the dashboard's single-currency check over transactions also holds per account.
- The audit trail names each refused row.

### Negative

- A file whose rows carry no currency is now partly or wholly rejected when its accounts exist. The producer always sets one.
- Files wait, and are not imported, while the receiver runs against an account-service without this change. Deploy account-service first.

## Security & Privacy Impact

- The lookup now also returns each account's currency, to the same single service client and scope (ADR-0037 decision 3). A currency code is not client data.
- No new route, identity or permission.

## Cost Impact

None.

## Operational Impact

- Deploy account-service before the receiver. Until then, the receiver's log and a `SYSTEM_EXCEPTION` event say the lookup returned an account without a currency, and files wait in `reports/`.
- [services/sftp/receiver/README.md](../../services/sftp/receiver/README.md) and [services/account-service/README.md](../../services/account-service/README.md) describe the check and the field.

## Validation

- account-service: the lookup controller test returns each account's currency; `make test` passes.
- Receiver: a poller test imports a lower-case code on an SGD account as `SGD`, rejects a USD row and a row with no currency on that account with their row numbers and reasons, keeps an `Unmatched` row without a currency, and moves the file to `rejected/`; a lookup-client test fails when an account comes back without a currency.
- Deployed stack, 2026-10-24: a file of three rows on an SGD account imported the row in `sgd` and rejected the row in USD and the row with no currency, with those reasons. A run while account-service's new tasks were still rolling out logged `lookup returned account … without a currency` and saved nothing from its file; the next run imported a producer file normally.

## References

- [ADR-0024](ADR-0024-transaction-ingestion-sftp-lambda.md), [ADR-0037](ADR-0037-transactions-database-and-account-linking.md), [ADR-0038](ADR-0038-dashboard-metric-definitions.md), [ADR-0045](ADR-0045-producer-account-list-synced-from-crm.md).
