# ADR-0065: Client Health matched transaction history

- Status: Proposed; human acceptance and Client Health/Platform review required
- Date: 2026-10-26 (Asia/Singapore)
- Owner: Joseph Yau
- Supersedes: None
- Related: [ADR-0024](ADR-0024-transaction-ingestion-sftp-lambda.md), [ADR-0037](ADR-0037-transactions-database-and-account-linking.md), [Client Health contracts](../api/client-health-monitor-contracts.md)

## Context

Client Health needs a time-bounded history of matched synthetic transactions. The existing Admin list route serves a different purpose and includes import-oriented transaction fields. The analysis service also needs a read-only service identity rather than relying on network location or the Admin UI's caller.

## Decision proposal

1. Add `GET /internal/v1/transactions` to transaction-service as a private route for the Client Health Monitor. Keep the existing account and Admin routes unchanged.
2. Authenticate the route with Cognito client-credentials access tokens. Accept only configured client IDs and the exact `crm-transactions/read` scope. Terraform provisions a client with only that scope and stores its secret in Secrets Manager.
3. Require RFC3339 `from` and `to` timestamps, with `from` inclusive and `to` exclusive. Return matched rows across all clients in that range and use bounded keyset pagination ordered by `(transaction_date, transaction_id)` descending. Do not require a `clientId` query parameter.
4. Return only matched rows with non-null CRM `client_id` and `account_id`. The response contains transaction ID, client/account IDs, type, amount, currency, transaction date, status, activity kind, and nullable stable source fingerprint. It excludes description, source account reference, import status, and import timestamps.
5. Classify transaction activity as `CustomerInitiated`, `Automated`, or `Unknown`. A source fingerprint is nullable and stable for repeated synthetic credits from one source.
6. Provide repeatable synthetic producer history for six complete calendar months. Transaction IDs are stable for a given account and transaction timestamp; the recurring-credit fingerprint is stable per synthetic account/source.

Migration V2 adds `activity_kind` with an `Unknown` default and enum check, nullable `source_fingerprint` constrained to 24–64 characters matching `synthetic-[a-z0-9]+(-[a-z0-9]+)*-[a-f0-9]{12}`, and a partial date/ID index for matched rows. These columns are not defined in `init.sql`, which remains V1.

## Consequences

- Client Health can page through bounded, matched-only history without reading transaction internals.
- The route is independently protected by a scoped machine identity and private networking.
- Cognito signing keys must be refreshed when the pool rotates them; the verifier must fail closed when a token cannot be verified.
- The app client secret must be granted only to the Client Health service task role and must not be committed or exposed in logs.
- Synthetic replay requires at least four known CRM account UUIDs synced to the producer. It emits recurring-credit interruption, transaction-activity decline, sustained net-outflow, and healthy-control cases.
- This ADR is a proposal until Joseph and the transaction, Client Health, and security/platform owners accept the contract and deployment details.

## Verification required before acceptance

- Confirm API contract fields, time semantics, pagination, and client scope with Garvit/Swayam.
- Confirm Terraform secret access and network path with the platform owner.
- Run receiver/producer tests, SQLBoiler and Mockery generation, and an end-to-end token/import/read check before integration.
