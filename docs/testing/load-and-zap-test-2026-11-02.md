# Load and ZAP test on AWS, 2026-11-02 (run 1)

The first run of the k6 load tests ([`tests/load`](../../tests/load/README.md)) and the OWASP ZAP scans ([`tests/security/zap`](../../tests/security/zap/README.md)) against a real deployment, the evidence the [test strategy](test-strategy.md) asks for under NFR 1 (performance and scalability) and NFR 8 (security). It found four defects. They were fixed and tested again on the same deployment; that run is reported separately in [load-and-zap-retest-2026-11-02.md](load-and-zap-retest-2026-11-02.md).

Run by Neo Jia Wen's session (Claude Code) on `task/infrastructure/load-and-zap-tests`, from `dev` at `c2c9631`, in AWS account 727816232662, `ap-southeast-1`, on 2026-11-02 between 04:52 and 06:31 UTC. Synthetic data only. Every service, database, Cognito pool and edge component was real.

## Result

| Test | Result |
|---|---|
| Deploy the stacks | **Done after one fix.** Bootstrap (14 resources), mail-identity (16), CRM (420), business-dashboard (26) and agent-engagement (35); 10 images; frontend. The CRM apply failed on two CloudWatch alarms (defect 1). |
| Smoke: every route once | **Passed.** 25 of 25 checks across 11 routes, as three agents and one admin. |
| Dashboard cache (NFR 1) | **Passed.** 20 refreshes in 2 minutes returned the cached answer; a refresh after 3 minutes was generated again. |
| Scale-out and scale-in (NFR 1) | **Passed.** 33,676 requests over 16 minutes at up to 40 per second: p95 65 ms, 1 failure (0.003%). client-service scaled from 1 to 6 tasks, transaction-service and dashboard-service to 3, and all were back to 1 within 25 minutes of the load ending. |
| Throttle burst | **Failed, and found defect 3.** At 80 requests per second, 800 of 1,153 requests failed with 5xx after up to 29 s: client-service ran its database out of connections. API Gateway's throttle never engaged. |
| ZAP baseline (NFR 8) | **No failures, 8 warnings**, 60 rules passed. Two are Medium: no Content-Security-Policy, and no Subresource Integrity on the Google Fonts stylesheet (defect 4). |
| ZAP API scan (NFR 8) | **No failures**, 73 URLs across 9 specs. Two routes answered a malformed ID with 500, one with the database's error text (defect 2). |

## What was deployed

- The **prod configuration**, not staging. Staging had never been deployed and has no configuration files, and it runs on Fargate Spot in one zone without the WAF. The prod configuration is what the demo runs, so the results apply to it. This replaces the strategy's "in staging".
- `high_availability = false`. It adds only a Multi-AZ standby per database and a second NAT gateway, neither of which changes scaling. Its own test is the failover drill.
- Not deployed: the SFTP server (transactions were inserted directly; see below) and the alert worker.
- The app was at `https://sg.itsag3t6.com`, behind CloudFront, the WAF, API Gateway and the internal ALB.

## Test data and tokens

- **Users:** three agents and one admin, created through `POST /api/users` with the root admin's token.
- **Test-only sign-in change:** `ALLOW_ADMIN_USER_PASSWORD_AUTH` was added to the web app client in the working copy only, so a script could sign the test users in (password plus a TOTP code). The pool keeps MFA on. The change was never committed and was destroyed with the deployment, as in the [2026-10-31 test](event-alert-aws-deployment-test-2026-10-31.md).
- **Clients and accounts:** `seed.js` created 75 synthetic clients through the API, 25 per agent, with one savings account each.
- **Transactions:** 1,500 synthetic matched transactions, 20 per account, were inserted with `scripts/db-run-sql.sh`, since no SFTP server ran.
- No token or personal address is in the evidence.

## Dashboard cache

`dashboard-cache.js` asked for a reporting window no other script used, so the first call was a miss:

- 20 more calls over the next 2 minutes all returned the same `generatedAt`, so they were answered from dashboard-service's cache (ADR-0033).
- A call 190 s after the first returned a new one.

The latencies don't separate hits from misses: the fan-out for a past window with no data is quick, so the miss took 71 ms and the hits 24 to 86 ms ([API Gateway log](evidence/2026-11-02-load-zap/run-1/k6/dashboard-cache-api-gateway-log.txt)).

Service Connect's per-minute byte counts into client-, account- and transaction-service showed no bursts during the repeats. They stayed at the idle level, about 6.7 KB a minute. There was a small rise in the minute after the post-TTL call. These counts are aggregated by the proxy and do not line up exactly with request times, so they corroborate rather than prove. `generatedAt` is the evidence.

## Scale-out and scale-in

`scale-out.js` ramped to 40 requests per second over 3 minutes, held it for 12 and ramped down over 1. About 80% of the requests were agent reads and 20% admin reads. The peak stays under API Gateway's 50 per second.

| Metric | Value |
|---|---|
| Requests | 33,676 (35.0 per second on average); none dropped by k6 |
| Latency | median 25 ms, p90 40 ms, p95 65 ms, p99 121 ms, max 2.84 s |
| Failed | 1 (0.003%), a 503 |
| Thresholds | `http_req_failed < 1%` and `p95 < 2000 ms`: both passed |

Task counts sampled every 30 s ([task-counts.csv](evidence/2026-11-02-load-zap/run-1/scaling/task-counts.csv)), with the [scaling activities](evidence/2026-11-02-load-zap/run-1/scaling/scaling-activities.tsv):

| Time (UTC) | client-service | transaction-service | dashboard-service |
|---|---|---|---|
| 05:46 load starts | 1 | 1 | 1 |
| 05:49 at 40 per second | 1 | 1 | 1 |
| 05:52:13 | → 2 | | |
| 05:53:13 | → 3 | → 2 | |
| 05:54:00 | | | → 2 |
| 05:56:13 | → 6 (maximum) | → 3 | |
| 05:57:00 | | | → 3 |
| 05:57:52 | 6 running | 3 running | 3 running |
| 06:02 load ends | 6 | 3 | 3 |
| 06:14 to 06:26 | 5, 4, 3, 2, 1, one step per 3 min | 2 at 06:14, 1 at 06:18 | 2 at 06:16, 1 at 06:19 |

- **The request-count policy did all the scaling.** Every scale-out step came from it: 300 ALB requests per task per minute. CPU never reached its 60% target.
- **client-service needed less than it got.** At peak one 0.25 vCPU task served about 757 requests a minute at 16 to 19% average CPU, so 6 tasks were more than it needed. The 100% CPU readings at 05:53, 05:54 and 05:57 are single new tasks starting (the Maximum statistic); the average in those minutes was 19 to 22%.
- **The other services stayed at one task.** Their share of the traffic stayed under the target.
- **Scale-in** began 12 minutes after the load ended. It took one task off every 3 minutes (the 120 s cooldown plus the alarm period). Everything was back at one task 24 minutes after the load ended.

**The one failure.** At 06:00:01 the activity feed answered 503. user-service's gRPC call to the Auditor hit its 2-second deadline, and the Auditor's query was cancelled. It happened once in about 1,700 activity reads. The Auditor was at 23% CPU and the audit database at 10 to 18%, so the cause is not load. It is recorded as an observation (open item 3).

## Throttle burst

`throttle.js` sent 80 requests per second to `GET /api/clients?page=0&size=1` for 60 s, to show API Gateway answering 429 above its limit. It did not get that far:

- 800 of the 1,153 requests sent failed with 500 or 503, after up to 29 s, API Gateway's integration timeout. Only 1,153 were sent, because each waited.
- No request reached 50 per second, so API Gateway never throttled.
- client-service's CPU stayed under 4%.
- The clients database's connections went from 2 to 295.
- client-service logged `pq: sorry, too many clients already` and `remaining connection slots are reserved`. It also logged `audit event not saved` for requests whose outbox write could not get a connection. That is defect 3.

## ZAP scans

Both scans were passive. The API scan used GET-only copies of the 9 public specs with an agent's token, so it wrote nothing. Reports: [baseline](evidence/2026-11-02-load-zap/run-1/zap/baseline.md) and `api-*.md` in the same folder.

| Alert | Risk | Where | Action |
|---|---|---|---|
| Content Security Policy header not set (10038) | Medium | Frontend | Defect 4: fixed in the retest |
| Sub Resource Integrity attribute missing (90003) | Medium | Frontend's Google Fonts link | Defect 4: fixed in the retest (fonts self-hosted) |
| Server error response, application error disclosure (100000, 90022) | Low | `GET /api/accounts/{id}` and `GET /api/clients/{id}/documents` with a malformed ID | Defect 2: fixed in the retest |
| Permissions-Policy not set (10063) | Low | Frontend | Defect 4: fixed in the retest |
| Cross-Origin-Opener-Policy and Cross-Origin-Resource-Policy missing (90004) | Low | Frontend; the resource policy also on the API | Defect 4: fixed in the retest |
| Cross-Origin-Embedder-Policy missing (90004) | Low | Frontend | Accepted: see ADR-0069 decision 3 |
| Server leaks version information (10036) | Low | Frontend (`Server: AmazonS3`) | Defect 4: fixed in the retest |
| Unexpected Content-Type (100001) | Low | `/internal/*` paths | Expected: CloudFront answers any path outside `/api/*` with the app's page, which shows these routes are not reachable from outside |
| Client error response (100000) | Info | 22 URLs (400, 403, 404) | Expected: made-up IDs and an agent's token |
| Non-storable content, cache-control, retrieved from cache, modern web application (10049, 10015, 10050, 10109) | Info | Various | No action: API responses are `no-store`; the app is a single-page app |

The write routes were then checked by hand with a malformed ID, using an ID no row can match, so nothing was changed:

- account-service's `PATCH` and `DELETE /api/accounts/{id}` also answered 500, and `DELETE` sent the SQL error.
- client-service's `DELETE /api/clients/{id}` answered 503 "account service unavailable", because account-service's 400 for the ID read as an outage.

Every other service answered 400 or 404.

## Defects found

1. **The CRM stack could not be deployed.** It had been broken since commit 551b8ae. The Client Health analysis-failure and AI-safety alarms used `SEARCH` expressions, which CloudWatch alarms refuse, without the required period. Every apply since that commit stopped there. Fixed during this run, so the deployment could go ahead:
   - Each alarm is now a single Metrics Insights query.
   - The AI-safety alarm, which summed two metrics, is now two alarms, because an alarm takes only one such query.
   - The stack's Terraform test was updated.
   - For the Client Health owners (Garvit, Swayam) to review.
2. **Malformed IDs answered 500.** The full list is in the ZAP section above. Some 500s sent the database's error text, which names tables and SQL. Fixed in the retest.
3. **The Go services' database pools had no limit.** account-service, client-service, transaction-service and the Auditor opened one connection per concurrent request. A burst exhausted the `db.t4g.micro`'s connection slots, failing most requests and losing audit events. Autoscaling cannot react within seconds. Fixed in the retest.
4. **Missing security headers on the frontend, and Google Fonts.** Fixed in the retest, under [ADR-0069](../adr/ADR-0069-frontend-security-headers-and-self-hosted-fonts.md) (proposed).

## Open items

1. **The request-count scaling target is conservative for client-service.** One task handled 2.5 times the target at under 20% CPU. A target of 600 to 750 requests per task per minute would scale it to 2 or 3 tasks at this load instead of 6. That is a scaling-policy decision for its owner. `api.tf`'s throttle values are likewise still "starting values, not derived from measured load".
2. **API Gateway's throttle is untested.** Defect 3 stopped the burst short of 50 per second. The retest runs it again.
3. **One activity-feed read timed out at the Auditor** (2-second gRPC deadline) under light load. It was not reproduced.
4. **The load test ran on the prod configuration, not staging**, as described above.

## Evidence

[`evidence/2026-11-02-load-zap/run-1/`](evidence/2026-11-02-load-zap/run-1/) contains:
- `k6/`: the k6 summaries, with setup data removed.
- `scaling/`: task counts, CPU and requests per target per minute, and the scaling activities.
- `zap/`: the ZAP reports.

The raw run output stayed in the git-ignored `out/` folder.

## Cost

The infrastructure ran for about 4 hours across both runs, at roughly USD 0.50 to 1 an hour, plus the scale-out tasks. No Bedrock, Tavily or SES use.
