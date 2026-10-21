# Test strategy

> **Status:** Proposed target architecture, not yet accepted. **Owner:** Neo Jia Wen. **Last updated:** 2026-10-16.  
> Drafted with AI assistance (Claude Code) from the team's architecture document. Verify AWS pricing, limits and service behaviour against official AWS documentation before accepting.  
> Part of the [target architecture](../architecture/system-overview.md); each decision is recorded as an ADR in the [ADR index](../adr/README.md), which gives its status.

This file currently holds the architecture evaluation only. Feature-level test plans live in each feature plan.

## Architecture evaluation: how the design will be evaluated

Each mandatory quality goal has a test that shows the design works, not only a description of it. CI checks run on every pull request; the load, security and restore tests run against staging before the demo; the failover drill runs in the high-availability demo week, when the Multi-AZ standbys and the second NAT gateway are switched on ([Environments and cost (target architecture)](../cost/budget.md)).

| Quality goal | Design | How we prove it |
|---|---|---|
| **Performance and scalability** (NFR 1) | CloudFront caching; target-tracking auto scaling from 1 to 6 Fargate tasks per service; the auditor scales on queue backlog; 3-minute dashboard cache. | **k6 load tests** in staging while watching the services scale out and back in, recording response times and error rates. A repeat dashboard refresh within the cache TTL must cause no extra calls to the other services. |
| **Availability** (NFR 2) | Tasks, the ALB, the VPC Link and the NAT gateways in two AZs; a Multi-AZ standby for every database; audit writes decoupled through SQS. | **Failover drill** in the demo week: force an RDS failover (reboot with failover) on a service's database, and stop that service's tasks in one AZ. The rest of the CRM must keep serving requests, and the service must recover on its own in the other AZ (it runs one task at rest, so it can be briefly unavailable); the recovery time is recorded. |
| **Security** (NFR 8) | JWT checked at API Gateway and again in every service; no public IPs in the application tier; per-client data keys (crypto-shredding); S3 Object Lock anchor for the audit chain; least-privilege IAM roles. | **CodeQL** and dependency scanning in CI; **ECR image scanning** on push; an **OWASP ZAP** baseline scan against staging; authorization tests (an agent cannot read another agent's client); **GuardDuty** findings and **CloudTrail** records reviewed. |
| **Maintainability** (NFR 3) | One service and one database per bounded context; OpenAPI with additive changes only under `/api`; a versioned gRPC contract for audit reads. | **Contract tests in CI:** `buf lint` and `buf breaking` fail any pull request that would break a gRPC caller; each service's OpenAPI contract is tested. |
| **Resiliency and durability** (NFR 5, 9) | Per-service outbox, SQS and a dead-letter queue; timeouts, retries and circuit breakers; AWS Backup with a cross-region copy. | **Dead-letter-queue replay test:** send a malformed audit event, check it moves to the dead-letter queue after 5 failed receives and raises the alarm, then fix and redrive it and check it is chained exactly once. **Auditor outage test:** stop the auditor, make changes, restart it, and check every audit event arrives. **SQS outage test:** point a service at an unreachable queue, make changes, restore the queue, and check every event arrives exactly once. **Restore rehearsal:** rebuild the stack in ap-southeast-2 from the AWS Backup copies within the 12-hour RTO. |

Results are recorded in this file and summarised in the final report.
