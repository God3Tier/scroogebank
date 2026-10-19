# SFTP transaction import (Feature 3)

`compose.yaml` runs local stand-ins for the external core banking system and the CRM's transactions database ([ADR-0024](../../docs/adr/ADR-0024-transaction-ingestion-sftp-lambda.md)):

| Service | What it is |
|---|---|
| `sftp` | The external SFTP server. Files wait in `/home/reportuser/reports`. |
| `producer` | The bank's file generator. Every `PUBLISH_INTERVAL_SECONDS` it writes a synthetic `report-<time>-<id>.json`, first as `.tmp` and then renamed. Its transactions use the account IDs in `CRM_ACCOUNT_IDS`, or random `ACC-000123` references when it is empty. |
| `db` | transactions-db. |
| `receiver-api` | transaction-service's internal read API, run with `go run` in the development image. The receiver's Dockerfile builds the production image by default; [the deployment runbook](../../docs/runbooks/deploy-aws.md) pushes it. |

The import itself, the poller, runs from `receiver/`. [receiver/README.md](receiver/README.md) explains how it links rows to accounts, rejects files, retries the connection and audits.

## Running it

1. Copy `.env.example` to `.env` and set `SFTP_KEY_FILE` to the private key whose public key is in the repository's `keys/authorized/`, and `SFTP_HOST_KEY_FINGERPRINT` to the server's SHA-256 host key fingerprint.
2. Run `make poller` to poll every `SFTP_POLL_INTERVAL`, or `make poller-once` for one poll.
3. Run `make test` for the receiver's and the producer's tests.

To see audit events, set `AUDIT_QUEUE_URL` (and `AWS_ENDPOINT_URL` for LocalStack) to the auditor's queue. With it empty, nothing is audited.

## The producer's accounts when deployed

The deployed producer reads its accounts from the file named in `CRM_ACCOUNT_IDS_FILE` instead, on every run, and publishes nothing while the file lists none. `scripts/sync-producer-accounts.sh` fills the file with the CRM's open accounts ([ADR-0045](../../docs/adr/ADR-0045-producer-account-list-synced-from-crm.md), [deploy-aws.md](../../docs/runbooks/deploy-aws.md) §8.5). To try the same locally, mount a file into the `producer` container and set `CRM_ACCOUNT_IDS_FILE` to its path. Fault files are published either way.

## Fault files for the demo

`make fault KIND=<kind>` publishes one file with a fault in it, then exits. The regular producer keeps running.

| Kind | The file | What the import does |
|---|---|---|
| `corrupt` | A JSON array cut short, like a truncated upload | `FILE_FORMAT_INVALID`, and the file moves to `rejected/` |
| `partial` | 3 valid rows, and 5 rows the import rejects: no transaction ID, a negative amount, an unknown status, an unknown type, a 4-letter currency | Imports the 3 rows. `FILE_PROCESSING_COMPLETED` with status Partially Successful lists the 5 rejected rows. The file moves to `rejected/` |
| `all-invalid` | Only the 5 invalid rows | `FILE_PROCESSING_COMPLETED` with status Failed. The file moves to `rejected/` |
| `unsupported` | 3 transactions as CSV, in a `.csv` file | `FILE_FORMAT_INVALID`, and the file moves to `rejected/` |
| `resend` | Two files a second apart: a Pending transaction, then the same transaction as Completed with another amount | The status moves to Completed; the amount keeps its first value |

To see where the files went:

```sh
docker compose exec sftp ls /home/reportuser/reports/processed /home/reportuser/reports/rejected
```

Two failures need no fault file:

- **Server outage:** `docker compose stop sftp`. Each poll retries, one `CONNECTION_FAILURE` is audited, and after 3 failed polls the circuit opens for 15 minutes. After `docker compose start sftp`, the first poll once the circuit lets one through imports the waiting files.
- **Host-key mismatch:** set `SFTP_HOST_KEY_FINGERPRINT` to another value and restart the poller. Each poll refuses the server without retrying, and one `CONNECTION_FAILURE` is audited.
