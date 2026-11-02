# Test strategy

> **Status:** Accepted target architecture (2026-11-01). **Owner:** Neo Jia Wen. **Last updated:** 2026-11-02.
> Drafted with AI assistance (Claude Code) from the team's architecture document. Verify AWS pricing, limits and service behaviour against official AWS documentation before accepting.
> Part of the [target architecture](../architecture/system-overview.md); each decision is recorded as an ADR in the [ADR index](../adr/README.md), which gives its status.

This file currently holds the architecture evaluation only. Feature-level test plans live in each feature plan.

## Architecture evaluation: how the design will be evaluated

Each mandatory quality goal has a test that shows the design works, not only a description of it. CI checks run on every pull request; the load, security and restore tests run against staging before the demo; the failover drill runs in the high-availability demo week, when the Multi-AZ standbys and the second NAT gateway are switched on ([Environments and cost (target architecture)](../cost/budget.md)).

| Quality goal | Design | How we prove it |
|---|---|---|
| **Performance and scalability** (NFR 1) | CloudFront caching; target-tracking auto scaling from 1 to 6 Fargate tasks per service; the auditor scales on queue backlog; 3-minute dashboard cache. | **k6 load tests** in staging while watching the services scale out and back in, recording response times and error rates. A repeat dashboard refresh within the cache TTL must cause no extra calls to the other services. |
| **Availability** (NFR 2) | Tasks, the ALB, the VPC Link and the NAT gateways in two AZs; a Multi-AZ standby for every database; audit writes decoupled through SQS. | **Failover drill** in the demo week: force an RDS failover (reboot with failover) on a service's database, and stop that service's tasks in one AZ. The rest of the CRM must keep serving requests, and the service must recover on its own in the other AZ (it runs one task at rest, so it can be briefly unavailable); the recovery time is recorded. |
| **Security** (NFR 8) | JWT checked at API Gateway and again in every service; no public IPs in the application tier; per-client data keys (crypto-shredding); S3 Object Lock anchor for the audit chain; least-privilege IAM roles. | **CodeQL** in CI (Java, Go, JavaScript/TypeScript, Python and the workflows) fails any pull request with an alert; **OSV-Scanner** in CI fails on any known vulnerability in a dependency, except those accepted with a reason and an expiry date in `osv-scanner.toml`; **ECR image scanning** on push; an **OWASP ZAP** baseline scan against staging; authorization tests (an agent cannot read another agent's client); **GuardDuty** findings and **CloudTrail** records reviewed. |
| **Maintainability** (NFR 3) | One service and one database per bounded context; OpenAPI with additive changes only under `/api`; a versioned gRPC contract for audit reads. | **Contract tests in CI:** `buf lint` and `buf breaking` fail any pull request that would break a gRPC caller. Every REST route has an OpenAPI contract in `docs/api`; each service's tests check every response they produce, and every route it serves, against it, and consumers check their canned replies against the provider's contract; `oasdiff breaking` fails any pull request that would break a REST caller ([ADR-0063](../adr/ADR-0063-openapi-contract-tests.md)). |
| **Resiliency and durability** (NFR 5, 9) | Per-service outbox, SQS and a dead-letter queue; timeouts, retries and circuit breakers; a daily AWS Backup plan (a cross-region copy once the account allows it). | **Dead-letter-queue replay test:** send a malformed audit event, check it moves to the dead-letter queue after 5 failed receives and raises the alarm, then fix and redrive it and check it is chained exactly once. **Auditor outage test:** stop the auditor, make changes, restart it, and check every audit event arrives. **SQS outage test:** point a service at an unreachable queue, make changes, restore the queue, and check every event arrives exactly once. **Restore rehearsal:** restore a database from AWS Backup into a Terraform-built stack within the 12-hour RTO; once the account allows the cross-region copy, rebuild the stack in ap-southeast-2 from it. |

Results are recorded in this file and summarised in the final report.

## Results

| Date | Quality goal | Test | Result | Report |
|---|---|---|---|---|
| 2026-11-02 | Performance and scalability (NFR 1) | k6 load tests on the prod configuration (staging has never been deployed) | At 40 requests per second: p95 65 ms, 0.003% failed. client-service scaled 1→6 tasks, transaction- and dashboard-service 1→3, all back to 1 within 25 minutes. The dashboard cache answered every refresh inside 3 minutes. A burst at 80 per second first failed most requests: the Go services' database pools had no limit. After the fix the same burst had no errors and API Gateway throttled above 50 per second. | [Run 1](load-and-zap-test-2026-11-02.md), [follow-up](load-and-zap-retest-2026-11-02.md) |
| 2026-11-02 | Security (NFR 8) | OWASP ZAP baseline and passive API scans | Run 1: no failures. Two Medium alerts (no Content-Security-Policy; Google Fonts without Subresource Integrity), and 500s with database error text for malformed IDs. After the fixes: no Medium alerts. Remaining Lows are accepted (no COEP, ADR-0069) or expected (`/internal/*` returns the app page). | [Run 1](load-and-zap-test-2026-11-02.md), [follow-up](load-and-zap-retest-2026-11-02.md) |
