# ADR-0029: Environment lifecycle and cost controls

- Status: Proposed
- Decision date/time: 2026-10-01 21:50 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/target-architecture`
- Related issue/PR: TBD (pull request from `feat/target-architecture` to `dev`)
- Supersedes: N/A
- Superseded by: N/A

## Context

The project has a USD 300 hard spending limit and a USD 100 protected contingency (`project_plan.md` §1). The full target design has 8 Fargate services, 7 RDS instances with Multi-AZ standbys, two NAT gateways, WAF and AI usage. Left on 24/7 it costs roughly USD 338–402 a month normally and USD 523–607 with high availability ([environments and cost](../cost/budget.md)), so it would exceed the limit. The high-availability features still have to be demonstrated.

## Decision Drivers

- Stay inside USD 300 without touching the contingency
- Still demonstrate Multi-AZ availability and failover (NFR 2)
- Keep a staging environment for promotion testing

## Options Considered

### Option A: run the full design 24/7

- Pros: always ready.
- Cons: about USD 340–400 a month, or 520–610 with high availability; over budget.

### Option B: cut the design (for example single-AZ only, no WAF, fewer services)

- Pros: cheap.
- Cons: fails mandatory NFRs and drops features.

### Option C: full design, run only when needed (chosen)

- Pros: the design stays complete; cost scales with use.
- Cons: discipline and automation are needed; environments are not always on.

## Decision

| Environment | Where | Lifecycle |
|---|---|---|
| dev | Developer machines: Docker Compose with one Postgres container per database, LocalStack SQS, an OpenSSH SFTP container | Local only |
| staging | AWS, reduced size: single-AZ `db.t4g.micro`, one NAT gateway, Fargate Spot, no WAF | Created with Terraform for promotion testing, destroyed afterwards |
| prod / demo | AWS, the full target design | Runs for the final 2–3 weeks; ECS scales to 0 and RDS stops outside working and demo hours |

- **Multi-AZ standbys and the second NAT gateway run only in the high-availability demo week** (roughly USD 35–40 extra). Outside that week, the databases run single-AZ with one NAT gateway.
- **AWS Budgets alerts** at 50, 70, 85, 95 and 100% of USD 300, plus a separate Bedrock alert.
- **First levers if spend runs ahead:**
  - fewer prod days;
  - a shorter demo week;
  - drop WAF from prod until the final week.
- **X-factor levers:**
  - run the x-factor services only for test and demo slots;
  - keep alerts-db and health-db single-AZ even in the demo week;
  - fall back to one shared x-factor RDS instance (about USD 13–15/month less);
  - keep the search and model kill switches on outside demo runs.
- Re-run the AWS Pricing Calculator before deploying the x-factors ([open item 17](../architecture/open-items.md#17-budget-with-the-x-factors)).

## Rationale

The architecture can be shown complete and highly available while paying for high availability for only about one week. Destroying staging and stopping prod outside hours removes idle spend, which the course budget rule forbids anyway.

## Consequences

### Positive

- Fits the limit without the contingency (original target, before the x-factors: USD 240–290).
- Failover is still demonstrated in the demo week.

### Negative / Trade-offs

- Outside the demo week the system is not highly available.
- Stopping RDS and scaling ECS to 0 needs automation, or someone remembering to do it.
- With 8 services and 7 databases the same lifecycle costs roughly a quarter more than the original target, so the levers above are likely to be needed.

## Security & Privacy Impact

None beyond the base design. Staging uses synthetic data only and is destroyed after use.

## Cost Impact

This ADR is the cost control. Every figure is rough and must be verified with the AWS Pricing Calculator.

## Operational Impact

Schedules or scripts to stop and start RDS and scale ECS; a calendar for the demo week; Budgets alerts through SNS ([ADR-0027](ADR-0027-observability-baseline.md)). Any use of the contingency needs its own ADR (`project_plan.md` §1).

## Validation

Monthly spend tracked against the plan in AWS Cost Explorer. The failover drill runs in the demo week ([test strategy](../testing/test-strategy.md)).

## References

- [Environments and cost](../cost/budget.md)
- [Open item 17](../architecture/open-items.md#17-budget-with-the-x-factors)
- `project_plan.md` §1 (budget guardrail)
