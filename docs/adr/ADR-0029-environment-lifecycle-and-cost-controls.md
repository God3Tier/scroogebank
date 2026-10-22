# ADR-0029: Environment lifecycle and cost controls

- Status: Proposed
- Decision date/time: 2026-10-01 21:50 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/target-architecture`
- Related issue/PR: TBD (pull request from `feat/target-architecture` to `dev`)
- Supersedes: N/A
- Superseded by: [ADR-0058](ADR-0058-every-deployment-ends-with-full-teardown.md) in part, if accepted (prod is destroyed at the end of every deployment; stopping is only a pause within one; budget alerts made by hand: USD 200 at 10, 20, 40, 60, 80 and 100%, and a Bedrock budget)

## Context

The project has a USD 300 hard spending limit and a USD 100 protected contingency (`project_plan.md` §1). The full target design has 8 Fargate services, 7 RDS instances with Multi-AZ standbys, two NAT gateways, WAF and AI usage. Left on 24/7 it costs roughly USD 570–660 a month normally and USD 755–860 with high availability ([environments and cost](../cost/budget.md)), about twice the limit. The high-availability features still have to be demonstrated.

## Decision Drivers

- Stay inside USD 300 without touching the contingency
- Still demonstrate Multi-AZ availability and failover (NFR 2)
- Keep a staging environment for promotion testing

## Options Considered

### Option A: run the full design 24/7

- Pros: always ready.
- Cons: about USD 570–660 a month, or 755–860 with high availability; about twice the budget.

### Option B: cut the design (for example single-AZ only, no WAF, fewer services)

- Pros: cheap.
- Cons: fails mandatory NFRs and drops features.

### Option C: full design, run only when needed (chosen)

- Pros: the design stays complete; cost scales with use.
- Cons: discipline and automation are needed; environments are not always on.

### Option D: create and destroy the whole stack for every session

- Pros: about USD 65 cheaper than Option C (about USD 140 in total), because the ALB, NAT gateways and database storage bill only during sessions.
- Cons: needs a persistent stack (Cognito users and their TOTP enrolments, client-keys, KMS keys, the S3 anchor bucket, ECR, secrets) split from an ephemeral one; RDS must be restored from snapshots every session; a recreated NAT gateway gets a new Elastic IP, which breaks the SFTP server's allow-list; more Terraform runs and slower session starts.

## Decision

| Environment | Where | Lifecycle |
|---|---|---|
| dev | Developer machines: Docker Compose with one Postgres container per database, LocalStack SQS, an OpenSSH SFTP container | Local only |
| staging | AWS, reduced size: single-AZ `db.t4g.micro`, one NAT gateway, Fargate Spot, no WAF | Created with Terraform for promotion testing, destroyed afterwards |
| prod / demo | AWS, the full target design | Runs for the final 2–3 weeks; ECS scales to 0 and RDS stops outside working and demo hours |

- **Between sessions ECS scales to 0 and RDS is stopped (stop model); the ALB and NAT gateways stay up**, so the NAT gateway keeps its Elastic IP on the SFTP server's allow-list. Stopped RDS instances still bill for storage.
- **Multi-AZ standbys and the second NAT gateway run only in the high-availability demo week** (roughly USD 19 extra). Outside that week, the databases run single-AZ with one NAT gateway.
- **AWS Budgets alerts** at 50, 70, 85, 95 and 100% of USD 300, plus a separate Bedrock alert. (Superseded by [ADR-0058](ADR-0058-every-deployment-ends-with-full-teardown.md) decision 7, if accepted: USD 200 at 10, 20, 40, 60, 80 and 100%, and USD 25 a month for Bedrock at 50, 80 and 100%, made by hand in the Billing console.)
- **First levers if spend runs ahead:**
  - fewer prod days;
  - a shorter demo week;
  - drop WAF from prod until the final week.
- **X-factor levers:**
  - run the x-factor services only for test and demo slots;
  - keep alerts-db and health-db single-AZ even in the demo week;
  - fall back to one shared x-factor RDS instance (about USD 13–15/month less);
  - keep the search and model kill switches on outside demo runs.
- Track actual spend weekly in AWS Cost Explorer against the line-item estimate ([open item 17](../architecture/open-items.md#17-budget-with-the-x-factors)).

## Rationale

The architecture can be shown complete and highly available while paying for high availability for only about one week. Destroying staging and stopping prod outside hours removes idle spend, which the course budget rule forbids anyway.

## Consequences

### Positive

- Fits the limit without the contingency: the line-item estimate for 3 weeks of prod (at most 2 sessions of 10 hours a week) and one 48-hour staging test is about USD 205, or USD 224 with GST.
- Failover is still demonstrated in the demo week.

### Negative / Trade-offs

- Outside the demo week the system is not highly available.
- Stopping RDS and scaling ECS to 0 needs automation, or someone remembering to do it.
- Keeping the ALB and NAT gateways up between sessions costs about USD 65 more than tearing the stack down after each session, but keeps data, the NAT gateway's Elastic IP and the Cognito users in place.
- Each extra 10-hour session day adds about USD 7, and each extra staging test about USD 18.

## Security & Privacy Impact

None beyond the base design. Staging uses synthetic data only and is destroyed after use.

## Cost Impact

This ADR is the cost control. The team's line-item estimate (`AWS Cost Estimate.xlsx`, with Singapore prices checked in the AWS Pricing Calculator on 30 Sep 2026) is about USD 205 for the planned lifecycle, leaving about USD 95 under the limit. Network and edge is the largest share (about USD 87).

## Operational Impact

Schedules or scripts to stop and start RDS and scale ECS; a calendar for the demo week; Budgets alerts through SNS ([ADR-0027](ADR-0027-observability-baseline.md)). Any use of the contingency needs its own ADR (`project_plan.md` §1).

## Validation

Monthly spend tracked against the plan in AWS Cost Explorer. The failover drill runs in the demo week ([test strategy](../testing/test-strategy.md)).

## References

- [Environments and cost](../cost/budget.md)
- [Open item 17](../architecture/open-items.md#17-budget-with-the-x-factors)
- `project_plan.md` §1 (budget guardrail)
