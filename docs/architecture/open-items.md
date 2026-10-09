# Target architecture: open items requiring team decisions

> **Status:** Proposed target architecture, not yet accepted. **Owner:** Neo Jia Wen. **Last updated:** 2026-10-09.  
> Drafted with AI assistance (Claude Code) from the team's architecture document. Verify AWS pricing, limits and service behaviour against official AWS documentation before accepting.  
> Part of the [target architecture](system-overview.md); each decision is recorded as a Proposed ADR in the [ADR index](../adr/README.md).

Each item needs a decision from the owner it names. When an item is decided, record the decision in an ADR and mark the item here as resolved, with a link to that ADR.

## 1. Agent visibility of activity

The spec says audit logs are accessible only to administrators, while the UI mock-up says agents see their own clients' activities. Proposal: raw audit log for Admins only; a filtered, PII-masked activity view for Agents.

## 2. Domain name

Needed for the ACM certificate on the internal ALB, at about USD 13/year plus USD 0.50/month for the hosted zone.

## 3. Admin-wide activity view in user-service

Proposed as the owner for now. The business dashboard (x-factor 2, [X-factor 2: business dashboard](x-factors.md#x-factor-2-business-dashboard)) could host this view later.

## 4. Reconcile the x-factor branch ADRs with the target architecture

Alerts ADR-0006 (schedule settled 2026-10-09: every 4 hours; [ADR-0031](../adr/ADR-0031-xf1-alert-processing-search-region-boundary.md) changed to match, so ADR-0006 and the `initial-lookback: 4h` setting stay as they are), ADR-0008 (shared database cluster, not its own RDS instance; revised drafts of ADR-0006 and ADR-0008 await the owner) and ADR-0010 (global-inference model, not Bedrock in Singapore); dashboard ADR-0003 (audit events through logs, not SQS); the health-monitor plan's open deployment decisions (now: daily scheduled task at 08:00 SGT, Bedrock in Singapore).

## 5. SFTP file format and schedule

File naming, CSV or JSON, header and field layout, and how often files are produced, agreed between the ingestion worker's owner and the SFTP server's owner.

## 6. Splitting accounts out of client-profile-management-service

**Resolved (2026-10-08).** `services/account-service` (Go) now owns accounts, extracted from `client-profile-management-service`, on branch `task/client-profile-management/account-service-split`. The foreign key is replaced by API checks in both directions, and each account stores its owning agent ([ADR-0035](../adr/ADR-0035-account-ownership-across-services.md)). Transactions now have their own database too: the import links each row to its account by asking account-service, instead of the foreign key and trigger of ADR-0006 ([ADR-0037](../adr/ADR-0037-transactions-database-and-account-linking.md)). `client-profile-management-service` keeps its name for now.

## 7. Go runtime exception

The auditor, client-service and account-service are written in Go, not the Java 25 baseline. [ADR-0017](../adr/ADR-0017-microservices-per-business-area.md) (Accepted 2026-10-08) records the exception (project_plan §2.2).

## 8. Migrations on startup or as a task

Confirm whether each service runs db-migrate as a separate task in staging and prod (as drawn), or migrates on startup ([Schema migrations (db-migrate)](../database/migrations.md#schema-migrations-db-migrate)).

## 9. Client ID validation at import

For now, transaction-ingest does not check Client IDs against client-service; ownership is checked when transactions are read ([Ingestion decisions](data-flow.md#ingestion-decisions)). Account IDs, by contrast, are checked at import: the poller asks account-service which account references exist and stores their client IDs ([ADR-0037](../adr/ADR-0037-transactions-database-and-account-linking.md)). The Feature 3 owner must confirm this, or choose another way to validate, in the Feature 3 plan and [ADR-0024](../adr/ADR-0024-transaction-ingestion-sftp-lambda.md) (project_plan §13 requires the ingestion design to be decided explicitly).

## 10. How transaction reads enforce agent ownership

**Resolved** by [ADR-0006](../adr/ADR-0006-transaction-account-linkage-and-access.md) decisions 3 and 5 (Accepted, Joseph Yau) and [ADR-0035](../adr/ADR-0035-account-ownership-across-services.md) decision 6. user-service serves `GET /api/accounts/{accountId}/transactions`. It forwards the caller's token to account-service's `GET /api/accounts/{id}`, which returns 404 unless the caller is an admin or owns the account, and only then reads the transactions from the receiver's private endpoint. Each account stores its owning agent, so the check needs neither clients-db nor a copy of ownership beside the transactions.

This covers one account at a time. For the business dashboard's transaction totals, [ADR-0039](../adr/ADR-0039-dashboard-aggregate-endpoints.md) (Proposed) uses the same pattern: user-service asks account-service for the agent's account IDs, then asks the receiver for totals over those accounts only.

## 11. Audit payloads with client personal data

Decide whether they are encrypted with the client's key from client-keys (only client-service can use it, so other services would send IDs only), or with a separate per-client key owned by the auditor.

## 12. Searching encrypted client fields

Confirm which fields the UI must search or sort by; each needs a blind index or must stay plaintext.

## 13. Tamper findings and chain-verify on dev

The auditor code on `dev` differs from [Audit integrity and GDPR](../security/security-model.md#audit-integrity-and-gdpr): (a) chain-verify runs as a long-lived process with internal timers (every 24 hours and every hour), not a task started by EventBridge; (b) on a mismatch it deletes the row from `audit_log`, re-links the chain and moves the row to `corrupted_audit_log`, which needs DELETE and UPDATE on `audit_log` ([Tamper resistance](../security/security-model.md#tamper-resistance) allows only INSERT and SELECT) and leaves no break in the chain to show where tampering happened; (c) it also writes an "Error logging" event into the chain itself; (d) no chain head is anchored in S3 Object Lock yet; (e) the findings are served over REST on the auditor (`GET`, `POST` and `DELETE /api/corrupted-logs`), including a delete, rather than read-only through user-service. The auditor owner must bring the code and the target architecture into line, and settle the findings table's columns.

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

## 20. Sign-in flow and PKCE (unconfirmed)

The target architecture proposes Cognito managed login with the authorization-code flow and PKCE ([Identity and security](../security/security-model.md#identity-and-security)), but the team has not confirmed it. Cognito does not enforce PKCE, so it holds only if the client sends it. The `feat/frontend` branch signs in through `react-oidc-context` (`oidc-client-ts`) with `response_type: "code"`, which adds PKCE by default. The Feature 1 owner and the frontend owner must confirm: (a) managed login (a redirect, where PKCE applies) rather than a custom login form using Cognito's SRP API (no redirect, so PKCE does not apply); (b) the app client settings in [Identity and security](../security/security-model.md#identity-and-security), set in Terraform; (c) a test that the `/oauth2/authorize` request carries `code_challenge` with `code_challenge_method=S256`. Record the decision in [ADR-0025](../adr/ADR-0025-identity-cognito-mfa-pkce-service-tokens.md).

## 21. Erasing soft-deleted client data

Deleting a client or account is a soft delete ([ADR-0036](../adr/ADR-0036-soft-deletion-of-clients-and-accounts.md)): the rows stay in clients-db and accounts-db, as the requirements Q&A asks (CPM08, CPM21, UM05). Nothing yet removes them. The Feature 2 owner, with the security owner, must decide: (a) how long deleted records are kept; (b) who can request erasure of one client's personal data, and how; (c) whether erasure deletes the rows in every owning service, deletes the client's data key ([ADR-0030](../adr/ADR-0030-client-data-envelope-encryption.md)), or both, as [ADR-0022](../adr/ADR-0022-audit-tamper-resistance-and-crypto-shredding.md) decision 5 describes.
