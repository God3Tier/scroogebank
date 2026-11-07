# Load and ZAP small-scale trial run 2: retest after the fixes, 2026-11-02

A follow-up to [trial run 1](load-and-zap-test-2026-11-02.md), which found four defects. Both trial runs were small: 3 agents, 1 admin, 75 clients; the full-scale runs are [load-test-full-scale-2026-11-02.md](load-test-full-scale-2026-11-02.md) and [zap-active-and-direct-2026-11-02.md](zap-active-and-direct-2026-11-02.md). This report covers the fixes and repeats the tests that found them, on the same deployment, to show the defects are gone.

Run by Neo Jia Wen's session (Claude Code) on `task/infrastructure/load-and-zap-tests`, between 06:28 and 06:57 UTC on 2026-11-02. Same account, configuration, users and synthetic data as run 1, plus two synthetic identity documents for the browser check.

## Result

| Defect from run 1 | Fix | Retest | Gone? |
|---|---|---|---|
| 1. The CRM stack could not be deployed (CloudWatch alarms) | Alarms use Metrics Insights queries; AI safety split into two alarms | The run-1 deployment used it; Terraform tests pass (14 of 14) | **Yes** |
| 2. Malformed IDs answered 500, some with the database's error text | 400 or 404 for a malformed ID; no 500 sends an error's text | 7 hand checks and the ZAP API scan | **Yes** |
| 3. A burst ran client-service's database out of connections | Bounded pools in the four Go services | The same burst at 80 requests per second | **Yes** |
| 4. No CSP, Permissions-Policy or cross-origin policies; Google Fonts without integrity; `Server: AmazonS3` | ADR-0069: CloudFront header policies and bundled fonts | ZAP baseline and API scans, a browser check | **Yes**, apart from the accepted COEP alert |

Nothing regressed:
- The smoke test passed again: 25 of 25 checks.
- A shorter scale-out run at the same 40 requests per second passed both thresholds and scaled out as before.

## What changed

Images were tagged `c2c9631-fix2` for account-service, client-service, transaction-service and the Auditor. The crm stack was applied, adding 8 resources, changing 9 and replacing 6 (task definitions, plus the two header policies). The frontend was rebuilt and uploaded. The services rolled over with no failed deployment by 06:40:16.

- **Alarms (defect 1):** `stacks/crm/observability.tf`.
  - The analysis-failure alarm is `SELECT SUM(client_health_analysis_runs_total) FROM "Scrooge/ClientHealth" WHERE outcome = 'failed'`.
  - The AI-safety alarm now covers validation rejections only. A new `client-health-ai-provider-failures` alarm covers provider failures.
  - Each has a 300 s period. The plan test checks the new expression.
- **Malformed IDs (defect 2):**
  - **account-service:** `GET`, `PATCH` and `DELETE /api/accounts/{id}` answer 400 "id must be a UUID", as the transactions route already did. A 500 from delete is logged, not sent.
  - **client-service:** a malformed client ID is "client not found" (404) for the document routes (in `findClient`) and for delete (before account-service is asked). Every 500 goes through `writeServerError`, which logs the error and answers "internal server error".
  - Tests cover each case.
- **Connection pools (defect 3):**
  - account-service, client-service, transaction-service and the Auditor set at most 10 open and 10 idle connections, with a 5-minute idle timeout.
  - At six tasks that is 60 per database, under a `db.t4g.micro`'s limit.
  - The Java services already had Hikari's pool of 10.
- **Headers and fonts (defect 4):** two CloudFront response-headers policies in `stacks/crm/edge.tf` and fonts from `@fontsource-variable`, as [ADR-0069](../adr/ADR-0069-frontend-security-headers-and-self-hosted-fonts.md) records.

All four Go services' test suites, the frontend's 269 tests and lint, and the crm stack's Terraform tests pass.

## Retest results

### Malformed IDs

| Request | Run 1 | Retest |
|---|---|---|
| `GET /api/accounts/accountId` | 500 | 400 `{"error":"id must be a UUID"}` |
| `PATCH /api/accounts/not-a-uuid` | 500 | 400 |
| `DELETE /api/accounts/not-a-uuid` | 500, with the SQL error | 400 |
| `GET /api/clients/clientId/documents` | 500, with the SQL error | 404 "client not found" |
| `DELETE /api/clients/not-a-uuid` | 503 "account service unavailable" | 404 "client not found" |
| `GET /api/accounts/00000000-…` (well-formed, missing) | 404 | 404 (unchanged) |

### Throttle burst (defect 3)

The same `throttle.js`, at 80 requests per second for 60 s:

| | Run 1 | Retest |
|---|---|---|
| Requests sent | 1,153 (each waited up to 29 s) | 4,800 (the full rate) |
| Server errors | 800 | **0** |
| Throttled by API Gateway (429) | 0 (never reached its limit) | 1,662 (about 52 a second served, its 50 per second plus burst) |
| p95 latency | 29 s | 30 ms |
| Clients database connections, peak | 295 | **10** |
| "audit event not saved" in client-service's log | many | **0** |

This also completes run 1's open item 2: API Gateway's stage throttle works as configured.

### Shorter scale-out (no regression)

`scale-out.js` with a 2-minute ramp and a 6-minute hold at 40 requests per second, from 06:44:45 to 06:53:48:

- 18,078 requests: p95 117 ms, p99 152 ms.
- 12 failed (0.06%), all 429 from API Gateway in the first minute, at under 10 requests per second.
- Both thresholds passed.

The 429s followed `setup()`'s burst of about 80 discovery calls, which can drain API Gateway's token bucket; AWS describes HTTP API throttling as approximate. No server errors.

| Route | Requests | p50 ms | p95 ms | p99 ms |
|---|---|---|---|---|
| `GET /api/clients` | 3,008 | 22 | 42 | 107 |
| `GET /api/clients/{clientId}` | 2,885 | 21 | 40 | 94 |
| `GET /api/accounts` | 2,162 | 20 | 31 | 71 |
| `GET /api/dashboard/overview` | 2,105 | 24 | 43 | 86 |
| `GET /api/accounts/{accountId}/transactions` | 2,097 | 26 | 40 | 104 |
| `GET /api/alerts` | 1,435 | 23 | 35 | 91 |
| `GET /api/transactions` (admin) | 936 | 21 | 32 | 71 |
| `GET /api/activities` (admin) | 897 | 134 | 185 | 251 |
| `GET /api/users` (admin) | 888 | 22 | 35 | 83 |
| `GET /api/dashboard/agents` (admin) | 879 | 24 | 45 | 90 |
| `GET /api/engagement/events` | 708 | 22 | 35 | 71 |

**The activity feed is the slowest route**, at a median of 134 ms. It is the only one that goes through user-service to the Auditor over gRPC and reads the audit database, which by then held every audit event the load had produced. It stayed well inside the threshold.

**Scaling.** client-service went from 1 to 3 tasks and transaction-service and dashboard-service from 1 to 2 by 06:53, again on the request-count policy, the same pattern as run 1 over a shorter hold. Scale-in was not watched to the end this time; run 1 showed it.

### ZAP scans (defects 2 and 4)

| Scan | Run 1 | Retest |
|---|---|---|
| Baseline | 0 failures, 8 warnings, 60 passed | 0 failures, **4 warnings**, 64 passed |
| API (9 specs) | 0 failures, 18 warnings | 0 failures, **4 warnings** |

The alerts that remain:

| Alert | Risk | Action |
|---|---|---|
| Cross-Origin-Embedder-Policy missing (90004) | Low | Accepted, ADR-0069 decision 3 |
| Unexpected Content-Type on `/internal/*` (100001) | Low | Expected: those paths get the app's page, which shows the routes are not reachable from outside |
| Client error responses, non-storable content, cache-control, retrieved from cache, modern web application | Informational | No action, as in run 1 |

Gone are:
- the CSP and Subresource Integrity alerts, the two Mediums;
- Permissions-Policy, the cross-origin opener and resource policies, and the `Server` version leak;
- the server-error and application-error-disclosure alerts.

`Server` now reads `CloudFront`, with no version, which ZAP passes.

### Browser check (ADR-0069)

Neo Jia Wen signed in as the root admin in Firefox with the console open:
- Sign-in through Cognito with MFA, the admin and dashboard pages, and the activity feed worked under the new CSP.
- The icons rendered from the bundled font.
- Both document previews displayed: a PNG as a `blob:` image and a PDF in a `blob:` frame.

There were no Content-Security-Policy violations. The console showed two warnings from Firefox's built-in PDF viewer when the PDF opened: "Invalid absolute docBaseUrl: blob:…", and a note that microtasks were reordered. Both come from PDF.js opening a `blob:` document and are not CSP-related.

## Still open

1. **ADR-0069** was accepted by Neo Jia Wen on 2026-11-02.
2. **The fixes need their owners' review:**
   - account-service and client-service (Feature 2);
   - transaction-service (Feature 3);
   - the Auditor;
   - the alarms (Client Health: Garvit, Swayam).
3. **Run 1's open items 1 and 3 stand:** the request-count target is conservative for client-service, and one activity-feed read timed out once.
4. **A few 429s right after a burst of setup calls**, at low request rates. Worth knowing when sizing the throttle, which is still at its starting values.

## Teardown

The runbook's teardown ran from 06:57 to 07:15 UTC, in this order:

1. The crm stack was applied with `deletion_protection=false`.
2. business-dashboard (26 resources) and agent-engagement (35) were applied, then destroyed.
3. crm (419) and mail-identity (16) were destroyed. Audit Object Lock did not block the crm destroy.
4. The state bucket's 120 object versions and delete markers were removed, and bootstrap (14) was destroyed.
5. At Neo Jia Wen's request, the two secrets still pending deletion from earlier deployments (2026-10-23 and 2026-10-31) were force-deleted, so the next deployment starts clean.

The empty-account check found:
- no buckets, clusters, databases, SES identities, secrets, repositories, user pools, CloudFront distributions or header policies;
- no NAT gateways, VPCs, load balancers, DynamoDB tables, KMS aliases or Elastic IPs.

Only the `itsag3t6.com` hosted zone remains, as the runbook intends. The test-only Cognito change was reverted in the working copy, and the test users' passwords, TOTP secrets and tokens were deleted from the session's scratch folder.

## Evidence

[`evidence/2026-11-02-load-zap/run-2/`](evidence/2026-11-02-load-zap/run-2/): the k6 summaries (smoke, throttle, scale-out) with setup data removed, the per-route figures above, the task counts, and the ZAP reports.
