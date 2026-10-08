# SFTP receiver transaction API

The receiver includes an internal HTTP server in `src/serverd` for reading imported transactions. It connects to the CRM database and returns transactions for a CRM account UUID. The public transaction endpoint is provided by user-service, which authenticates the caller and checks account ownership through the client-profile service before calling this receiver. Keep the receiver reachable only from trusted internal services.

The local SFTP Compose stack initializes the CRM schema before the transaction schema and adds a foreign-key `account_id` to each imported transaction when its source account reference matches an existing CRM account UUID. Unmatched external references remain unlinked and are not returned. For an existing Compose database volume, `make -C services/sftp up` applies the account-link migration.

Run the internal HTTP server from this module with the `DB_*` environment variables used by the SFTP poller. It listens on `APP_ADDR` (default `:8080`).

## Endpoint

Internal endpoint: `GET /api/accounts/{accountId}/transactions`. It has no authentication middleware and must stay private. The repository uses SQLBoiler to filter by the transaction's linked `account_id`. Results are ordered newest first. An account with no matching transactions returns `200` and an empty array.

The receiver stores the provided CRM account UUID in `source_account_ref` and links it to `accounts.id`; legacy or unmatched external references remain unlinked and are not returned by this endpoint. For local demo data, create an account through client-profile, then set `CRM_ACCOUNT_IDS` to its returned `account_id` before starting the producer. Multiple UUIDs can be comma-separated.
