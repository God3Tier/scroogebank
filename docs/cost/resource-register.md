# AWS Resource Register

## Platform Terraform resources

Status: defined in Terraform and validated with mocked providers; no successful
live apply or deployed ARN is claimed. Human owner: Neo Jia Wen. The exact
resource inventory and observed charges must be added from Terraform state and
Cost Explorer after INF-02's approved test-account apply.

| Resource group | Environment / region | Owner | Purpose and expected cost | Shutdown / cleanup |
|---|---|---|---|---|
| Bootstrap state S3 bucket | Account-wide / Singapore | Neo Jia Wen | Encrypted, versioned Terraform state and native lock files; low storage cost | Preserve until every dependent stack is destroyed; empty versions before final bootstrap teardown |
| CloudTrail bucket and multi-Region trail | Account-wide | Neo Jia Wen | Management events and audit-anchor data events; included in the managed-services estimate | Lifecycle expires logs after the configured retention; destroy bootstrap last |
| AWS Budgets | Account-wide | Neo Jia Wen | USD 300 and Bedrock budget alerts | Destroy only at final account cleanup; budgets must exist before application resources |
| GuardDuty detector (not in Terraform) | Account-wide | Account owner | The account's existing detector provides threat detection; bootstrap does not create one, because a second fails (ADR-0042 decision 11) | Not managed or destroyed by the team |
| VPC, subnets, route tables, endpoints, NAT gateway(s), Elastic IP(s) | Per CRM environment / Singapore | Neo Jia Wen | Private application/database tiers; NAT and public IPv4 are major cost items | `running=false` removes NAT gateways but retains their Elastic IPs; destroy staging after its test |
| CloudFront, frontend S3 and WAF | Per CRM environment; CloudFront/WAF in Virginia | Neo Jia Wen | HTTPS SPA/API edge and managed WAF rules; request logging is left out because the account's service control policy denies its log group (ADR-0042 decision 11) | WAF exists only in prod; frontend bucket is emptied by destroy |
| API Gateway, VPC Link and internal ALB | Per CRM environment / Singapore | Neo Jia Wen | JWT-checked public API into private ECS services; ALB has an idle cost even while stopped | Destroy staging; prod ALB remains until the prod stack is destroyed |
| Cognito user pool, clients, groups, domain and trigger Lambda | Per CRM environment / Singapore | Neo Jia Wen | Managed login, required MFA, service identity and auth-event auditing | Scales per request; user-pool deletion protection is lifted only during deliberate teardown |
| ECS cluster, Service Connect, task definitions/services and ECR repositories | Per CRM environment / Singapore | Neo Jia Wen | Six API/worker images, 1–6 prod tasks while running; compute is a major running cost | `running=false` scales services to zero; ECR retains ten images per repository |
| dashboard-service: ECR repository, task definition/service, target group and listener rule, security group | Per CRM environment / Singapore, in the CRM's cluster (`stacks/business-dashboard`, ADR-0047) | Neo Jia Wen | Business dashboard (X-factor 2); one 1 vCPU / 2 GB task while running, about USD 1.50 a day; no new cost, moved from the CRM stack | Follows the CRM's `running` after its own apply; destroy before the CRM stack |
| Six RDS PostgreSQL instances | Per CRM environment / Singapore | Neo Jia Wen | Service-owned databases; single-AZ normally and Multi-AZ only for the demo week | Stop between sessions; AWS restarts stopped instances after seven days, so stop them again |
| DynamoDB client-keys and encrypted ID-document S3 bucket | Per CRM environment / Singapore | Neo Jia Wen | Envelope-encryption keys and encrypted synthetic identity documents | On-demand DynamoDB; deletion protection and bucket cleanup follow the teardown flag |
| Audit SQS/DLQ and Object-Lock anchor bucket | Per CRM environment / Singapore | Neo Jia Wen | Durable audit delivery and tamper-evident daily anchors | Queues disappear with the stack; locked anchor versions require expiry or authorized governance bypass |
| AWS Backup plan and primary vault | Prod / Singapore | Neo Jia Wen | Daily database/key-table backups. The Sydney vault, its KMS key and the cross-Region copy are left out because the account's service control policy denies the vault (ADR-0042 decision 11) | Not created in staging; remove recovery points before teardown |
| CloudWatch logs, metrics, alarms/dashboard and SNS | Per CRM environment | Neo Jia Wen | 30-day operational logs and alert delivery; usage-based managed-service cost | Log groups follow stack teardown; confirm SNS subscriptions during deployment |
| External SFTP VPC, EC2, EIP, ECR and host-key parameter | Separate prod simulation / Singapore | Neo Jia Wen with Feature 3 owner | Synthetic external-bank simulation; approximately USD 6–8/month while its `t4g.micro` runs | Stop between sessions and destroy before the CRM stack |

The current paid-plan estimate and lifecycle assumptions are in
[`budget.md`](budget.md). All entries use synthetic data only.

## Event-Driven Alerts resources

Status: planned resources only. No deployed ARN, cloud connection, or charge is
claimed.

| Resource | Planned region/scope | State | Cost/cleanup evidence required |
|---|---|---|---|
| EventBridge schedule | Singapore | Planned | Schedule, tagged ARN, disable/delete proof |
| ECS worker task/service | Singapore | Planned | Task sizing, duration, tagged ARN, stop/delete proof |
| AgentCore search/index | Tokyo | Planned | Request count, tagged identifier, index cleanup proof |
| Bedrock model invocation | Approved available region | Planned | Model ID, token totals, regional price, access disable proof |
| PostgreSQL alert schema | Existing shared database | Partially implemented locally | Incremental storage/I/O and migration rollback evidence |
| CloudWatch logs | Per deployed runtime | Planned | Retention, ingestion/storage volume, log-group cleanup proof |
| CloudWatch metrics/alarms/dashboard | Singapore control plane | Planned | Metric/alarms/dashboard identifiers and delete proof |
| AWS Budget notification | Project account | Planned | Threshold, recipients, test notification, delete proof |

Every deployed item must carry the project and feature tags required by the
project operating model. Add the real identifier, owner, observed charge, and
cleanup result only after an approved deployment. Never copy credentials or
private client data into this register.
