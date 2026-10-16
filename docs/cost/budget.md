# Environments and cost (target architecture)

> **Status:** Proposed target architecture, not yet accepted. **Owner:** Neo Jia Wen. **Last updated:** 2026-10-16.
> Drafted with AI assistance (Claude Code) from the team's architecture document. Verify AWS pricing, limits and service behaviour against official AWS documentation before accepting.  
> Part of the [target architecture](../architecture/system-overview.md); each decision is recorded as an ADR in the [ADR index](../adr/README.md), which gives its status.

Recorded in [ADR-0029](../adr/ADR-0029-environment-lifecycle-and-cost-controls.md) (Proposed).

| Environment | Where it runs | Lifecycle |
|---|---|---|
| **dev** | Developer machines: Docker Compose with one Postgres container per service database, LocalStack SQS, and an OpenSSH SFTP container. | Local only |
| **staging** | AWS, reduced size: single-AZ `db.t4g.micro` databases, one NAT gateway, Fargate Spot, no WAF. | Created with Terraform for promotion testing, destroyed afterwards |
| **prod / demo** | AWS, the full target design. | Runs for the final 2–3 weeks. ECS scales to 0 and RDS instances are stopped outside working and demo hours; the ALB and NAT gateways stay up. **Multi-AZ standbys and the second NAT gateway are switched on only for the high-availability demo week**; outside that week the databases run single-AZ with one NAT gateway. |

The diagram shows the full design; the demo-week-only parts are not marked on it.

**Rough prod cost if left on 24/7** (ap-southeast-1, USD/month; verify with the AWS Pricing Calculator). This is not the plan; it shows why the lifecycle above is needed:

| Item | Normal running | High-availability demo week (pro rata) |
|---|---|---|
| Fargate, 8 services × 1–1.25 tasks (0.25–0.5 vCPU, 1 GB each) + scheduled jobs | 165–205 | same |
| RDS, 7 × `db.t4g.micro` | 140–155 (single-AZ) | 280–310 (Multi-AZ) |
| ALB | ~20–25 | same |
| NAT gateway(s) + public IPv4 | ~45–50 (one) | ~90–100 (two) |
| WAF | ~10 | same |
| CloudWatch, SQS, API Gateway, CloudFront, Cognito, Lambda, KMS, Secrets, S3, DynamoDB, Backup, GuardDuty (core detection only, after the 30-day trial) and one CloudTrail trail | 23–37 | same |
| Bedrock and AgentCore web search (usage-based, capped searches, kill switches) | 5–15 | same |
| **Total** | **~570–660** | **~755–860** |

**Budget reality:** running this design 24/7 would cost about twice the USD 300 limit in a single month. Staying inside the limit depends on the lifecycle above.

**Planned lifecycle cost.** The team's line-item estimate (`AWS Cost Estimate.xlsx`, with Singapore prices checked in the AWS Pricing Calculator on 30 Sep 2026) puts the whole project at about **USD 206** (about USD 225 with 9% GST), including the customer-managed Sydney key required for encrypted RDS backup copies. That is about USD 94 under the **USD 300 hard limit**, with the USD 100 contingency untouched. It assumes:

- prod runs for 3 weeks: 2 normal weeks and the high-availability demo week;
- between sessions ECS scales to 0 and the RDS instances are stopped, with at most 2 sessions of 10 hours a week;
- the ALB, the NAT gateway(s) and database storage stay up for the whole period, so the NAT gateway keeps its Elastic IP on the SFTP server's allow-list;
- the Multi-AZ standbys and the second NAT gateway run only in the demo week (roughly USD 19 extra);
- one 48-hour staging test, destroyed afterwards.

Network and edge is the largest share (about USD 87, mostly the NAT gateways and the ALB staying up), followed by the databases (about USD 42) and compute (about USD 37). Each extra 10-hour session day adds about USD 7, and each extra 48-hour staging test about USD 18.

The original target, set before the x-factors, was roughly USD 240–290. AWS Budgets alerts are the guardrail, and Bedrock has its own alert. If spend runs ahead of plan, the first levers are fewer prod days or sessions, a shorter demo week, and dropping WAF from prod until the final week (about USD 4). Further levers:

- run Fargate on Graviton (ARM), about 20% less on compute (about USD 7);
- run the x-factor services only for their test and demo slots;
- keep alerts-db and health-db single-AZ even in the high-availability demo week;
- fall back to one shared x-factor RDS instance with a database each (about USD 13–15/month less, weaker isolation);
- keep the search and model kill switches on outside demo runs.

The external SFTP server used for the simulation is a `t4g.micro` EC2 instance (roughly USD 6–8/month, stopped when idle).
