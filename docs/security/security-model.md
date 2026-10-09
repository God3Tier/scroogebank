# Security model (target architecture)

> **Status:** Proposed target architecture, not yet accepted. **Owner:** Neo Jia Wen. **Last updated:** 2026-10-09.  
> Drafted with AI assistance (Claude Code) from the team's architecture document. Verify AWS pricing, limits and service behaviour against official AWS documentation before accepting.  
> Part of the [target architecture](../architecture/system-overview.md); each decision is recorded as a Proposed ADR in the [ADR index](../adr/README.md).

## Identity and security

Recorded in [ADR-0025](../adr/ADR-0025-identity-cognito-mfa-pkce-service-tokens.md) (Proposed).

- **Cognito user pool:** mandatory MFA (TOTP); Cognito handles password hashing; `Admin` and `Agent` groups carried in the JWT `cognito:groups` claim; sign-in through Cognito managed login using the OAuth 2.0 authorization-code flow.
- **PKCE (proposed, not yet confirmed; [open item 20](../architecture/open-items.md#20-sign-in-flow-and-pkce-unconfirmed)):** the frontend is a single-page app and cannot keep a client secret, so the sign-in should use PKCE. The app makes a random `code_verifier`, sends only its SHA-256 hash (`code_challenge`) with the sign-in redirect, and must present the verifier to exchange the code for tokens, so an intercepted code is useless on its own. Cognito checks PKCE when a challenge is sent but does not require one, so the protection depends on configuration: a public app client with no client secret, the authorization-code grant only (implicit grant off), and a frontend library that adds PKCE automatically, such as `oidc-client-ts` or Amplify `signInWithRedirect()`.
- **Defense in depth:** API Gateway validates every token, and each service validates it again. Service-to-service calls use Cognito client-credentials tokens with scopes; no call is trusted just because it comes from inside the network.
- **Authorization:** agents can only access their own clients (services filter on `created_by_agent_id = jwt.sub`). The root admin cannot be deleted or disabled.
- **Perimeter:** AWS WAF on CloudFront, API Gateway throttling, and a security group chain: VPC Link to ALB, ALB to services, each service to its own database only on port 5432, named callers to the auditor's gRPC port, and the ingestion worker outbound to the external SFTP server on port 22.
- **Threat detection and audit trail:** GuardDuty and CloudTrail.
- **Encryption in transit:** HTTPS at CloudFront, API Gateway and the ALB (ACM certificate); self-signed TLS between the ALB and tasks; TLS on gRPC and to every RDS database; SSH (SFTP) to the external server with key authentication and a pinned host key.
- **Client data at rest:** field-level envelope encryption of client personal data, with per-client keys in DynamoDB wrapped by a symmetric KMS key ([Client data encryption (envelope encryption)](#client-data-encryption-envelope-encryption)), on top of RDS storage encryption.
- **Secrets:** Secrets Manager or SSM Parameter Store; nothing secret in git or container images. Least-privilege IAM role per task.
- **PII in logs:** masked by each service's logging configuration before anything is written.
- **Supply chain:** ECR image scanning, dependency scanning and CodeQL in CI; an OWASP ZAP baseline scan against staging.
- **PCI-DSS:** out of scope, because no card data is stored.

## Audit integrity and GDPR

Recorded in [ADR-0022](../adr/ADR-0022-audit-tamper-resistance-and-crypto-shredding.md) (Proposed).

### Tamper resistance

- Each `audit_log` row stores the previous row's hash (`parent_hash`) and its own `hash`, computed over its fields plus the parent hash. Changing or deleting any past row breaks every later hash.
- The **chain head** is the newest row's `sequence_no` and `hash`. Because each hash covers everything before it, the head stands for the whole history up to that row.
- The auditor's database role has only `INSERT` and `SELECT` on `audit_log`, with no `UPDATE` or `DELETE`.
- The daily **chain-verify** task recomputes the chain, raises an alarm on any mismatch, and writes the current chain head to the **S3 Object Lock** bucket. Someone with database access could rewrite rows and recompute every hash, but could not change the anchors already locked in S3, so the next check detects it.
- Object Lock uses governance mode with short retention so the project can still be torn down.
- **Tamper findings table.** chain-verify records each mismatch it finds in a separate table in audit-db (on `dev`: `corrupted_audit_log`, holding the affected row and the detection time), apart from the chain itself. Admins read the findings through user-service, which calls the auditor over gRPC; the auditor stays internal-only. The current `dev` code handles mismatches differently ([open item 13](../architecture/open-items.md#13-tamper-findings-and-chain-verify-on-dev)).

### Why chain-verify is a separate task, not part of the auditor service

chain-verify is built from the auditor's code and container image (hence its place in the auditor column), but it runs as its own scheduled ECS task:

- **One run per day.** The auditor runs 2–6 copies; a timer inside it would run the check in every copy.
- **Reliable schedule.** EventBridge fires on a fixed schedule. A timer inside the auditor restarts on every deployment and scaling event.
- **No load on the live service.** A full-chain scan does not compete with event consumption and gRPC reads.
- **Least privilege.** Only chain-verify's role can write to the anchor bucket, so a compromised auditor cannot forge an anchor to match a rewritten chain.
- **Cost.** A daily task is billed for a few minutes only.

chain-verify reads audit-db directly rather than through the auditor's gRPC API: a full scan would load the live service, the gRPC API is designed for scoped queries, and a checker should read the stored rows itself. This does not break database-per-service, because the auditor and chain-verify are two deployables of one service.

### Right to erasure (NFR 8, GDPR)

- **Deleting is not erasure.** `DELETE /api/clients/{id}` and `DELETE /api/accounts/{id}` are soft deletes: the row is kept with `deleted_at` set and hidden from every user, admins included, as the requirements Q&A asks ([ADR-0036](../adr/ADR-0036-soft-deletion-of-clients-and-accounts.md)). A client cannot be deleted while it has accounts that are not deleted.
- **Erasure** (a data-protection request, or the end of the retention period) removes a client's data from every service that holds it: the profile in client-service, their accounts in account-service and their transactions in transaction-service. Each removal goes through the owning service's API. Neither the erasure operation nor the retention period exists yet ([open item 21](../architecture/open-items.md#21-erasing-soft-deleted-client-data)).
- Client personal data in clients-db is encrypted with the client's **data key** ([Client data encryption (envelope encryption)](#client-data-encryption-envelope-encryption)). On an erasure request, deleting that client's item from the client-keys table makes every remaining copy of their encrypted data unreadable, including copies in database backups ("crypto-shredding").
- Audit payloads that contain personal data (for example before/after values) are also encrypted with a per-client key, so the hash chain, computed over the ciphertext, still verifies after erasure. Whether they use the same client-keys store is [open item 11](../architecture/open-items.md#11-audit-payloads-with-client-personal-data).
- Point-in-time recovery on the client-keys table keeps deleted items for up to 35 days, and backups age out within the documented retention period, so erasure is complete only after both windows pass.

### Authentication events

- Login comes from Cognito's `PostAuthentication` trigger; password-reset confirmation from the `PostConfirmation_ConfirmForgotPassword` trigger.
- Logout, user creation, update, disable, role change and admin password reset come from user-service.
- user-service sends its events through its outbox. The Cognito triggers have no database, so they send each event straight to the audit queue.
- **Fail open.** A trigger retries the send briefly (Cognito allows a trigger about 5 seconds). If it still fails, the trigger writes the full event to CloudWatch Logs as `AUDIT_SEND_FAILED`, a metric filter raises an alarm, and an operator replays the event to the queue. The login or password reset itself goes ahead: an SQS outage must not lock every user out.
- **Trade-off accepted:** a login can be missing from the audit chain until it is replayed. The event keeps its `event_id`, so a replay never creates a duplicate.

## Client data encryption (envelope encryption)

Recorded in [ADR-0030](../adr/ADR-0030-client-data-envelope-encryption.md) (Accepted).

Every client field in clients-db is encrypted by client-service before it is written, except the fields needed to find and authorize records.

- **Plaintext fields:** client UUID, owning agent ID, status and timestamps. These are what lookups, agent filtering and pagination need.
- **Encrypted fields:** the 11 personal fields (first and last name, date of birth, gender, email, phone number, address, city, state, country and postal code), encrypted in the application with AES-256-GCM. Each value is bound to its client and field, so it cannot be copied to another client or field.
- **One data key per client.** When a client is created, client-service calls KMS `GenerateDataKey` on a symmetric customer-managed key. It uses the plaintext key to encrypt, and stores only the **wrapped** (KMS-encrypted) key in the DynamoDB `client-keys` table, keyed by client UUID. The client ID is passed as KMS encryption context, so a wrapped key only unwraps for its own client.
- **Reading a client:** client-service fetches the wrapped key from DynamoDB, calls KMS `Decrypt`, and caches the plaintext key in memory for a short time to limit KMS calls.
- **Separation:** the keys are not in clients-db. A leaked clients-db snapshot or SQL dump is unreadable without both the client-keys table and permission to use the KMS key. Only client-service's IAM role can read the table and use the key.
- **Uniqueness:** email and phone number must stay unique among clients that are not deleted, so each has a **blind index** next to its ciphertext: an HMAC-SHA-256 computed by KMS `GenerateMac` with one shared HMAC key, over the value trimmed and lowercased (email) or stripped of spaces, hyphens, dots and brackets (phone). The key never leaves KMS, so a database copy alone cannot be used to test or recover emails and phone numbers. It covers every client, so it is the most sensitive key in this design.
- **Erasure:** deleting a client's item from client-keys crypto-shreds all of that client's encrypted data; erasure must also clear the client's two blind indexes ([Right to erasure (NFR 8, GDPR)](#right-to-erasure-nfr-8-gdpr)).
- **Trade-off:** encrypted fields cannot be searched or sorted in SQL. client-service has no search endpoint; a future search on another field needs its own blind index ([open item 12](../architecture/open-items.md#12-searching-encrypted-client-fields), resolved).
