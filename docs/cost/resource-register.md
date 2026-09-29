# Event-Driven Alerts Resource Register

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
