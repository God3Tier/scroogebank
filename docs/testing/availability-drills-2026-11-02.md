# Availability drills on AWS, 2026-11-02

The drills the [test strategy](test-strategy.md) sets for NFR 2 (availability) and NFR 5 (resiliency):
- a service's task fails;
- the Auditor is down while users make changes;
- a database's primary fails over to its standby in the other zone.

They ran on the same deployment as the [full-scale load test](load-test-full-scale-2026-11-02.md) and the [ZAP scans](zap-active-and-direct-2026-11-02.md), after both. The deployment was then torn down (below).

Run by Neo Jia Wen's session (Claude Code) at `4ddcaad`, account 727816232662, prod configuration, 2026-11-02 11:18–12:32 UTC. `high_availability` was switched on at 10:29 for drill 3, which gives Multi-AZ standbys and a second NAT gateway; the conversion finished at 10:39 with no downtime. The outage in each drill was measured by `poller.js`: one agent request to `GET /api/clients` a second, logged with its status.

## Result

| Drill | Outage seen by users | Recovery | Data |
|---|---|---|---|
| 1. client-service's only task stopped | 59 s of 503 on client routes only; every other service unaffected | Automatic: ECS started a replacement, healthy behind the ALB 1 min 41 s after the stop | — |
| 2. Auditor stopped for 3 min 54 s | **None**: all 20 changes succeeded | Automatic: the 30 queued audit events were chained 22 s after the Auditor started | 30 events arrived once each; dead-letter queue empty; chain-verify: 216,181 entries, 0 problems |
| 3. clients-db forced failover (twice) | 12 s and 8 s of errors on client routes | Automatic: the standby took over in 29 s and 44 s | A client committed before the failover read back intact both times |

## 1. Task failure

| Time (UTC) | Event |
|---|---|
| 11:19:03 | `aws ecs stop-task` on client-service's only task |
| 11:19:03–11:19:46 | The stopping task is drained but keeps answering: no errors |
| 11:19:46 | ECS deregisters it from the ALB and starts a replacement |
| 11:19:47–11:20:45 | **59 s of 503**: the ALB has no healthy client-service target (59 failed probe requests) |
| 11:20:44 | The replacement passes its health check and is registered; requests succeed |

- **Detection and failover are automatic.** The service's desired count is 1, so ECS replaces a missing task; the ALB's health check decides when it takes traffic.
- **The outage is the time to start one task**, because each service runs a single task at rest to save cost (ADR-0029). Only client-service's routes were down.
- **The fix is a cost choice:** two tasks per service would make a single failure invisible, at about twice the Fargate cost. Open item 1.

## 2. Auditor outage

1. The Auditor's autoscaling was suspended (its queue-depth policy would otherwise have restarted it), and its desired count set to 0 at 11:38:52.
2. As the ZAP test agent, 20 changes were made: 5 clients created, 5 updated, 10 accounts opened. **All 20 succeeded.** Users do not wait for the audit trail: each service writes the event to its own outbox and a relay sends it to SQS (ADR-0020).
3. `crm-sg-prod-audit-insert` held **30 messages** with nothing consuming them. There were 30 rather than 20 because opening an account also records the read of its client.
4. The Auditor was set back to 1 and autoscaling resumed at 11:41:36. Its task was running at 11:42:46, and the queue was empty at 11:43:08. **The dead-letter queue stayed at 0.**
5. In the audit database:
   - 30 new rows with 30 distinct event IDs, at consecutive sequence numbers 216,152–216,181 (15 CREATE, 5 UPDATE, 10 GET);
   - no broken parent-hash links;
   - the chain-verify task then checked all 216,181 entries and found 0 problems.

An Auditor outage delays the audit trail but loses nothing and blocks no user.

## 3. Database failover

clients-db ran Multi-AZ, with the primary in ap-southeast-1a and the standby in 1b. A marker client, "Failover Marker", was created and committed first. Then:

| Run | Failover started → completed | Probe errors | Endpoint |
|---|---|---|---|
| 1 | 12:07:58 → 12:08:27 (29 s) | 12 s (12:08:02–12:08:13), 17 requests: timeouts and 500 | 10.0.20.x → 10.0.21.235 (ap-southeast-1b) |
| 2 (recorded, after failing back to 1a) | 12:26:43 → 12:27:27 (44 s) | 8 s (12:26:47–12:26:54), 13 requests | 10.0.20.20 (1a) → 10.0.21.235 (1b) at 12:26:50 |

- **No committed data was lost.** The marker client read back with every field intact after each failover: `GET /api/clients/{id}` answered 200 at 12:09:09 and 12:31:41.
- **The outage is shorter than the failover.** Requests failed only while connections were cut over. client-service's pool reconnected to the new primary as soon as the endpoint's DNS changed.
- **Synchronous replication** (RDS Multi-AZ) is why the committed write survived: the standby had it before the commit returned.
- **The zone field lags.** `DescribeDBInstances` and the console kept reporting the old zone for several minutes after each failover. The endpoint's address (each zone has its own database subnet) and the RDS events show the change at once, so the drill used those.

## Open items

1. **One task per service** means a task failure costs about a minute of that service. Setting `min_tasks = 2` for the services agents depend on most (client, account, transaction) would remove that, at roughly USD 0.01 an hour per extra task. A cost decision for the owners (ADR-0029). **Done 2026-11-03:** every service now runs at least 2 tasks ([ADR-0075](../adr/ADR-0075-two-tasks-minimum-per-service.md)).
2. **A few requests fail during a database failover** (8–12 s). The services could retry an idempotent read once on a dropped connection; today the error reaches the user.

## Teardown

The runbook's teardown ran from 12:15 to 13:03 UTC.

An interrupted first attempt left the crm state locked and 7 task definitions deregistered. No Terraform process was still running, so the lock was released; a re-plan then recreated the task definitions before the destroy.

1. The crm stack was applied with `deletion_protection = false`.
2. business-dashboard (26 resources) and agent-engagement (35) were applied, then destroyed.
3. crm was destroyed: 438 resources, including the test tooling and the HA standbys.
4. No secret was left pending deletion.
5. mail-identity (16) was destroyed.
6. The state bucket's 137 object versions and delete markers were removed, and bootstrap (14) was destroyed.

The account-empty check at 13:02:56 found none of the following:
- buckets, clusters, databases, secrets, repositories, user pools or SES identities;
- non-default VPCs, NAT gateways, Elastic IPs or load balancers;
- DynamoDB tables, customer KMS aliases, CloudFront distributions or web ACLs.

Only the `itsag3t6.com` hosted zone remains, as the runbook intends.

The test-only Cognito change was reverted in the working copy. The test users' passwords, TOTP secrets and tokens were deleted from the session's scratch folder.

**Cost:** the deployment ran from 08:10 to 13:03 UTC, about 5 hours, including about 2.5 hours with Multi-AZ databases. Estimated at USD 6–10, mostly RDS, NAT, Fargate during the stress test, and the ZAP tasks; the billing data was not read here.

## Evidence

[`evidence/2026-11-02-full-scale/drills/`](evidence/2026-11-02-full-scale/drills/):
- the probe's per-request CSVs and summaries for each drill;
- `drill-outputs.txt`, with the ECS, SQS, audit-database, chain-verify and RDS outputs.

Screen recordings (ECS task replacement, the audit queue, the failover) are kept outside the repo with the report.
