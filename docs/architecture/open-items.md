# Target architecture: open items requiring team decisions

> **Status:** Proposed target architecture, not yet accepted. **Owner:** Neo Jia Wen. **Last updated:** 2026-10-14.  
> Drafted with AI assistance (Claude Code) from the team's architecture document. Verify AWS pricing, limits and service behaviour against official AWS documentation before accepting.  
> Part of the [target architecture](system-overview.md); each decision is recorded as an ADR in the [ADR index](../adr/README.md), which gives its status.

Each item needs a decision from the owner it names. When an item is decided, record the decision in an ADR and mark the item here as resolved, with a link to that ADR.

## 1. Agent visibility of activity

The spec says audit logs are accessible only to administrators, while the UI mock-up says agents see their own clients' activities. Proposal: raw audit log for Admins only; a filtered, PII-masked activity view for Agents.

**Resolved (2026-10-09)** by [ADR-0021](../adr/ADR-0021-audit-read-path-grpc-service-connect.md) decisions 2 and 3. Clients never use the CRM, so "client activities" are agents' actions on their clients, as the mock-up's "My Recent Activities" shows. Agents see their own activities; admins see all agents'. The feed shows changes only, and the raw audit log is shown to no one through it.

## 2. Domain name

Needed for the ACM certificate on the internal ALB, at about USD 13/year plus USD 0.50/month for the hosted zone.

## 3. Admin-wide activity view in user-service

Proposed as the owner for now. The business dashboard (x-factor 2, [X-factor 2: business dashboard](x-factors.md#x-factor-2-business-dashboard)) could host this view later.

**Resolved (2026-10-09)** by [ADR-0021](../adr/ADR-0021-audit-read-path-grpc-service-connect.md) decision 2: user-service owns it, as `GET /api/activities`. It is a Feature 1 requirement in the base spec, so it does not move to the business dashboard, which is an x-factor. The same feed serves agents their own activities.

## 4. Reconcile the x-factor branch ADRs with the target architecture

Alerts ADR-0006 (schedule settled 2026-10-09: every 4 hours; [ADR-0031](../adr/ADR-0031-xf1-alert-processing-search-region-boundary.md) changed to match, so ADR-0006 and the `initial-lookback: 4h` setting stay as they are), ADR-0008 (shared database cluster, not its own RDS instance; revised drafts of ADR-0006 and ADR-0008 await the owner) and ADR-0010 (global-inference model, not Bedrock in Singapore; the model receives only public data, so this is a choice about residency and cost, not privacy); dashboard ADR-0003 (audit events through logs, not SQS); the health-monitor plan's open deployment decisions (now: daily scheduled task at 08:00 SGT, Bedrock in Singapore).

**Dashboard part settled (2026-10-14).** The first dashboard branch's ADR-0003 was not carried over: [ADR-0033](../adr/ADR-0033-xf2-dashboard-topology-cache-audit.md) replaces it, and dashboard-service on `dev` sends `DASHBOARD_VIEW` events straight to the audit queue. Still open: the alerts ADR-0008 and ADR-0010 points (on `dev`, the alert service defaults to the global-inference model through Bedrock in us-east-1, with AI off by default) and the health-monitor deployment decisions.

## 5. SFTP file format and schedule

File naming, CSV or JSON, header and field layout, and how often files are produced, agreed between the ingestion worker's owner and the SFTP server's owner.

**Partly settled (2026-10-12)** by [ADR-0024](../adr/ADR-0024-transaction-ingestion-sftp-lambda.md) (Accepted 2026-10-14): files are JSON arrays of transactions named `report-<time>-<id>.json`, as on `dev`. There is no checksum file. Still to agree: the field layout as a written contract, how often files are produced, and whether each file carries a control record (record count and amount total) to catch damage that still parses.

## 6. Splitting accounts out of client-profile-management-service

**Resolved (2026-10-08).** `services/account-service` (Go) now owns accounts, extracted from `client-profile-management-service`, on branch `task/client-profile-management/account-service-split`. The foreign key is replaced by API checks in both directions, and each account stores its owning agent ([ADR-0035](../adr/ADR-0035-account-ownership-across-services.md)). Transactions now have their own database too: the import links each row to its account by asking account-service, instead of the foreign key and trigger of ADR-0006 ([ADR-0037](../adr/ADR-0037-transactions-database-and-account-linking.md)). `client-profile-management-service` keeps its name for now.

## 7. Go runtime exception

**Resolved (2026-10-14).** The auditor, client-service and account-service are written in Go, not the Java 25 baseline. [ADR-0017](../adr/ADR-0017-microservices-per-business-area.md) (Accepted 2026-10-08) records the exception (project_plan §2.2). The Feature 3 components (the SFTP receiver: the import and transaction-service's read API) are Go too; [ADR-0024](../adr/ADR-0024-transaction-ingestion-sftp-lambda.md) decision 7 (Accepted 2026-10-14) extends the exception to them.
## 8. Migrations on startup or as a task

Confirm whether each service runs db-migrate as a separate task in staging and prod (as drawn), or migrates on startup ([Schema migrations (db-migrate)](../database/migrations.md#schema-migrations-db-migrate)).

## 9. Client ID validation at import

For now, transaction-ingest does not check Client IDs against client-service; ownership is checked when transactions are read ([Ingestion decisions](data-flow.md#ingestion-decisions)). Account IDs, by contrast, are checked at import: the poller asks account-service which account references exist and stores their client IDs ([ADR-0037](../adr/ADR-0037-transactions-database-and-account-linking.md)). The Feature 3 owner must confirm this, or choose another way to validate, in the Feature 3 plan and [ADR-0024](../adr/ADR-0024-transaction-ingestion-sftp-lambda.md) (project_plan §13 requires the ingestion design to be decided explicitly).

## 10. How transaction reads enforce agent ownership

**Resolved** by [ADR-0006](../adr/ADR-0006-transaction-account-linkage-and-access.md) decisions 3 and 5 (Accepted, Joseph Yau) and [ADR-0035](../adr/ADR-0035-account-ownership-across-services.md) decision 6. user-service serves `GET /api/accounts/{accountId}/transactions`. It forwards the caller's token to account-service's `GET /api/accounts/{id}`, which returns 404 unless the caller is an admin or owns the account, and only then reads the transactions from the receiver's private endpoint. Each account stores its owning agent, so the check needs neither clients-db nor a copy of ownership beside the transactions.

This covers one account at a time. For the business dashboard's transaction totals, [ADR-0039](../adr/ADR-0039-dashboard-aggregate-endpoints.md) (Proposed) uses the same pattern: user-service asks account-service for the agent's account IDs, then asks the receiver for totals over those accounts only.

## 11. Audit payloads with client personal data

Decide whether they are encrypted with the client's key from client-keys (only client-service can use it, so other services would send IDs only), or with a separate per-client key owned by the auditor.

**Not needed yet (2026-10-09).** client-service's audit events carry IDs and endpoint details only, never personal data ([ADR-0030](../adr/ADR-0030-client-data-envelope-encryption.md)). Decide this before any event carries personal data, for example before/after values.

## 12. Searching encrypted client fields

Confirm which fields the UI must search or sort by; each needs a blind index or must stay plaintext.

**Resolved (2026-10-09)** by [ADR-0030](../adr/ADR-0030-client-data-envelope-encryption.md) decision 6. client-service has no search endpoint, so only email and phone number get blind indexes, for their uniqueness. They use one shared KMS HMAC key over normalised values. A future search on another field needs its own blind index and an ADR.

## 13. Tamper findings and chain-verify on dev

The auditor code on `dev` differs from [Audit integrity and GDPR](../security/security-model.md#audit-integrity-and-gdpr): (a) chain-verify runs as a long-lived process with internal timers (every 24 hours and every hour), not a task started by EventBridge; (b) on a mismatch it deletes the row from `audit_log`, re-links the chain and moves the row to `corrupted_audit_log`, which needs DELETE and UPDATE on `audit_log` ([Tamper resistance](../security/security-model.md#tamper-resistance) allows only INSERT and SELECT) and leaves no break in the chain to show where tampering happened; (c) it also writes an "Error logging" event into the chain itself; (d) no chain head is anchored in S3 Object Lock yet; (e) the findings are served over REST on the auditor (`GET`, `POST` and `DELETE /api/corrupted-logs`), including a delete, rather than read-only through user-service. The auditor owner must bring the code and the target architecture into line, and settle the findings table's columns. [ADR-0021](../adr/ADR-0021-audit-read-path-grpc-service-connect.md) decision 4 leaves to this item and [ADR-0022](../adr/ADR-0022-audit-tamper-resistance-and-crypto-shredding.md) which operations admins have on findings (read only, or also create and delete) and how they are served; it fixes only that admins reach them through user-service. Since the activity feed (branch `task/base-features/activity-feed`), the auditor refuses `/api/corrupted-logs` without an admin's access token, so the endpoints can no longer be called around user-service; whether create and delete should exist is still open.

**Partly resolved (2026-10-12)** by [ADR-0022](../adr/ADR-0022-audit-tamper-resistance-and-crypto-shredding.md), amended and accepted:
- The hash covers every field of a row and its parent's hash.
- chain-verify is a program that checks the chain once per daily start and never writes to `audit_log`. It records each problem once in `corrupted_audit_log`, with the row's id and a reason.
- The auditor's database role has only `INSERT` and `SELECT` on `audit_log`.
- Each run checks the latest S3 Object Lock anchor and writes a new one.

This settles (a) to (d) and the findings table's columns. Both are now on `dev`: the hash chain and run-once chain-verify (PR #87) and the S3 Object Lock anchor (PR #88).

Still open, from (e): which operations admins have on findings (read only, or also create and delete). Until that is decided, `dev`'s `GET`, `POST` and `DELETE` stay. Owner: the auditor's owner.

## 14. Tokyo web search availability

Confirm in the live AWS spike that Bedrock AgentCore web search is available in Tokyo (and whether it is now available in Singapore).

## 15. Instrument master source

Choose the approved security-master or market-data provider, and how reclassifications are reviewed.

## 16. Health-monitor activity contract

Transaction-service must provide matched activity (client and account IDs, stable IDs, type, amount, time, status, source fingerprint) with six months of replay. Since [ADR-0037](../adr/ADR-0037-transactions-database-and-account-linking.md), the SFTP import stores `account_id` and `client_id` on every matched row; unmatched rows have neither. Still missing: a read API the analysis task can call with a service token, and the six months of replay.

## 17. Budget with the x-factors

The line-item estimate for the planned lifecycle is about USD 205 ([Environments and cost (target architecture)](../cost/budget.md)). Track actual spend weekly in AWS Cost Explorer, and agree which cost levers to apply if it runs ahead of plan.

## 18. Audit sender verification

With one shared queue, the auditor trusts the `source_service` field in each message. Decide whether the auditor requests the SQS `SenderId` attribute and rejects events whose sending IAM role does not match `source_service`, and restrict the queue policy to producer roles. The auditor owner must decide.

## 19. Client verification request (Feature 2)

The spec says the system sends a verification request to the client, who provides the NRIC. For now the agent collects and uploads the NRIC and verifies it manually, and no request is sent by the system; the SES e-mail is deferred until verification is automated. The Feature 2 owner should confirm this meets the requirement and record the decision in an ADR.

**Resolved (2026-10-13)** by [ADR-0041](../adr/ADR-0041-client-identity-documents-and-manual-verification.md): no request is sent; the agent collects the document and uploads it through client-service, and the requirements Q&A confirms verification is manual (CPM04, CPM07, CPM23). Uploading never verifies the client: the agent checks the documents and then verifies, naming the documents checked. Documents are optional, as the Q&A does not expect an upload (CPM06); a verification without one is recorded and shown as such. Joseph Yau, the Feature 2 code owner, is asked to agree on the implementation's pull request.

## 20. Sign-in flow and PKCE

The target architecture proposed Cognito managed login with the authorization-code flow and PKCE ([Identity and security](../security/security-model.md#identity-and-security)). Cognito does not enforce PKCE, so it holds only if the client sends it. The Feature 1 owner and the frontend owner were to confirm: (a) managed login (a redirect, where PKCE applies) rather than a custom login form using Cognito's SRP API (no redirect, so PKCE does not apply); (b) the app client settings, set in Terraform; (c) a test that the `/oauth2/authorize` request carries `code_challenge` with `code_challenge_method=S256`.

**Resolved (2026-10-13)** by [ADR-0025](../adr/ADR-0025-identity-cognito-mfa-pkce-service-tokens.md) decision 2: managed login with PKCE, through `oidc-client-ts` and `react-oidc-context`, from a public app client with the authorization-code grant only, and a frontend test for (c). This item had said `feat/frontend` already signed in that way; no branch has that code, so the sign-in is built new.

## 21. Erasing soft-deleted client data

Deleting a client or account is a soft delete ([ADR-0036](../adr/ADR-0036-soft-deletion-of-clients-and-accounts.md)): the rows stay in clients-db and accounts-db, as the requirements Q&A asks (CPM08, CPM21, UM05). Nothing yet removes them. The Feature 2 owner, with the security owner, must decide: (a) how long deleted records are kept; (b) who can request erasure of one client's personal data, and how; (c) whether erasure deletes the rows in every owning service, deletes the client's data key ([ADR-0030](../adr/ADR-0030-client-data-envelope-encryption.md)), or both, as [ADR-0022](../adr/ADR-0022-audit-tamper-resistance-and-crypto-shredding.md) decision 5 describes. Whichever is chosen, erasure must also clear the client's email and phone blind indexes, which the shared index key would otherwise still match ([ADR-0030](../adr/ADR-0030-client-data-envelope-encryption.md) decision 5), and delete the client's identity documents and their `client_documents` rows ([ADR-0041](../adr/ADR-0041-client-identity-documents-and-manual-verification.md) decision 7).

## 22. Identity documents: wrong uploads and re-verification

[ADR-0041](../adr/ADR-0041-client-identity-documents-and-manual-verification.md) keeps every uploaded document, because a document can become a verification's evidence, and leaves a verified client verified after any later edit. The Feature 2 owner must decide: (a) whether an agent or admin may remove a document uploaded by mistake (for example another client's) while it is not yet a verification's evidence, and how that is audited; (b) whether changing a verified client's identity details (name, date of birth) sets the client back to unverified, so the new details are checked against a document.

## 23. Admin writes to client data

The code lets admins update, delete and verify any client, and upload its documents: `RequireClientOwner` lets every admin through, as [ADR-0005](../adr/ADR-0005-cognito-security-across-features-1-and-2.md) decision 4, [ADR-0041](../adr/ADR-0041-client-identity-documents-and-manual-verification.md) and the [Feature 2 plan](../feature-plans/feature-2_plan.md) describe. The requirements Q&A says admins only read client data: "Admin have read only access to client info" (UM04); "Admin dont have write access on client related activities. Admin can't create new client account, update profile etc." (UM18); "Only Agent hand all client related activities. Admin has read access of it." (CPM31). The Feature 2 owner must decide whether to make admins read-only in client-service and account-service, and record it in an ADR that amends ADR-0005 and ADR-0041.

## 24. Client reassignment

When an agent is disabled, deleted or leaves, an admin assigns their clients to another agent (Q&A UM07, UM15, UM17). Nothing implements this: a client's `created_by_agent_id` never changes, and account-service copies it as each account's `owner_agent_id` ([ADR-0035](../adr/ADR-0035-account-ownership-across-services.md)). The Feature 1 and Feature 2 owners must decide the endpoint, who may call it (the Q&A gives it to admins, which item 23 must allow), how the account copies are updated with it, and how it is audited.

## 25. Business dashboard panels not served yet

The business dashboard mockup showed panels that dashboard-service does not serve: most active clients by transaction value, ingestion freshness (when transactions were last imported), clients without an account, rejected verifications, and failed or long-pending transactions with their clients. The frontend hides them until they exist. Each needs a contract change to [`docs/api/dashboard-api.yaml`](../api/dashboard-api.yaml) and, where it names clients, a decision on how the transaction rows are joined to client names without moving personal data out of client-service ([ADR-0038](../adr/ADR-0038-dashboard-metric-definitions.md), [ADR-0039](../adr/ADR-0039-dashboard-aggregate-endpoints.md)). The business dashboard owner must decide which are worth building.
