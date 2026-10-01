# Environments and cost (target architecture)

> **Status:** Proposed target architecture, not yet accepted. **Owner:** Neo Jia Wen. **Last updated:** 2026-10-01.  
> Drafted with AI assistance (Claude Code) from the team's architecture document. Verify AWS pricing, limits and service behaviour against official AWS documentation before accepting.  
> Part of the [target architecture](../architecture/system-overview.md); each decision is recorded as a Proposed ADR in the [ADR index](../adr/README.md).

Recorded in [ADR-0029](../adr/ADR-0029-environment-lifecycle-and-cost-controls.md) (Proposed).

| Environment | Where it runs | Lifecycle |
|---|---|---|
| **dev** | Developer machines: Docker Compose with one Postgres container per service database, LocalStack SQS, and an OpenSSH SFTP container. | Local only |
| **staging** | AWS, reduced size: single-AZ `db.t4g.micro` databases, one NAT gateway, Fargate Spot, no WAF. | Created with Terraform for promotion testing, destroyed afterwards |
| **prod / demo** | AWS, the full target design. | Runs for the final 2–3 weeks. ECS scales to 0 and RDS instances are stopped outside working and demo hours. **Multi-AZ standbys and the second NAT gateway are switched on only for the high-availability demo week**; outside that week the databases run single-AZ with one NAT gateway. |

The diagram shows the full design; the demo-week-only parts are not marked on it.

**Rough prod cost** (ap-southeast-1, USD/month if left on 24/7; verify with the AWS Pricing Calculator):

| Item | Normal running | High-availability demo week (pro rata) |
|---|---|---|
| Fargate, 8 services × 2 small tasks + scheduled jobs | 95–115 | same |
| RDS, 7 × `db.t4g.micro` | 140–155 (single-AZ) | 280–310 (Multi-AZ) |
| ALB | ~20 | same |
| NAT gateway(s) + public IPv4 | ~45–50 (one) | ~90–100 (two) |
| WAF | ~10 | same |
| CloudWatch, SQS, API Gateway, CloudFront, Cognito, Lambda, KMS, Secrets, S3, DynamoDB, Backup, GuardDuty (core detection only, after the 30-day trial) and one CloudTrail trail | 23–37 | same |
| Bedrock and AgentCore web search (usage-based, capped searches, kill switches) | 5–15 | same |
| **Total** | **~338–402** | **~523–607** |

**Budget reality:** with the three x-factors, running this design 24/7 for a month **exceeds** the USD 300 limit. Staying inside it depends on the lifecycle above:

- prod runs only 2–3 weeks, with ECS scaled to 0 and RDS stopped outside working and demo hours;
- the Multi-AZ standbys and second NAT run for about one week only (roughly USD 35–40 extra);
- staging is destroyed after each promotion test.

Original target, set before the x-factors: roughly USD 240–290 in total, inside the **USD 300 hard limit**, leaving the USD 100 contingency untouched. This is tight. AWS Budgets alerts are the guardrail, and Bedrock has its own alert. If spend runs ahead of plan, the first levers are fewer prod days, a shorter demo week, and dropping WAF from prod until the final week.

The USD 240–290 target was set for 6 services and 5 databases. With 8 services and 7 databases the same lifecycle costs roughly a quarter more, which puts it at or above the limit. Before the x-factors are deployed, re-run the AWS Pricing Calculator and choose among these levers:

- run the x-factor services only for their test and demo slots;
- keep alerts-db and health-db single-AZ even in the high-availability demo week;
- fall back to one shared x-factor RDS instance with a database each (about USD 13–15/month less, weaker isolation);
- keep the search and model kill switches on outside demo runs.

The external SFTP server used for the simulation is a `t4g.micro` EC2 instance (roughly USD 6–8/month, stopped when idle).
