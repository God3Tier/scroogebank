# Event-Driven Alerts Offline Integration Evidence

Date: 2026-09-30 (Asia/Singapore)

Branch: `task/event-driven-alerts/integration`

Owner: Azeez

Required reviewer: Hayyun

## 2026-10-23 Tavily and dependency-security verification

- The selected Tavily adapter is exercised against a local fake HTTPS-contract
  server for bearer authentication, restrictive domain/date/result parameters,
  exact local window/domain validation, excerpt cleanup, response-size caps and
  safe provider error mapping.
- The complete event-alert verification passes with Docker: 189 tests, including
  all PostgreSQL 16 Testcontainers classes, the source-to-alert worker, Tavily
  contract coverage, migrations, and Bedrock tool-use tests.
- Frontend verification passes 116 tests, ESLint and the Vite production build.
  `npm audit` reports zero known vulnerabilities after upgrading Vite to 8.3.3
  and Vitest to 5.0.3.
- The first Docker-backed run exposed 16 duplicate-key errors because four test
  seed routines repeated the synthetic portfolios added by migration V6. Those
  inserts are now idempotent and the clean rerun passes 189/189. Terraform
  format/validate/test could not run because the Terraform CLI is not installed.
  A real Tavily key,
  provider call, AWS secret/task, latency/quality/credit benchmark and staging
  source-to-alert proof remain explicit gates; no live evidence is claimed.

## Scope

This evidence covers repository-local and Docker-backed behavior only. No AWS
credentials, endpoints, AgentCore gateway, Bedrock model, Cognito issuer,
CloudWatch resource, or production Kafka broker was contacted.

## Verified path

1. A synthetic stored public source is classified through the strict AI boundary.
2. A citation-bearing, explicitly AI-labelled briefing is produced.
3. Synthetic assigned holdings are read and deterministic exposure/materiality
   factors are calculated.
4. A client/agent-scoped alert, factors, briefing, and audit-outbox facts are
   committed together.
5. Replay does not duplicate the alert; another agent cannot read it.
6. Audit-capacity exhaustion fails before event or alert persistence.
7. The publisher claims bounded rows with leases, emits the auditor-compatible
   envelope, and changes delivery state only after acknowledgement. An embedded
   Kafka round trip verifies the actual producer/consumer wire contract; one
   failed record does not block the rest of its claimed batch.
8. The Go auditor accepts the explicit event-alert allowlist and rejects unknown
   actions; the source event ID becomes the audit-chain primary key so duplicate
   Kafka delivery cannot create a second audit record.
9. Retention removes expired briefing content and redacts expired source metadata
   without deleting audit evidence.

## Commands

```powershell
mvn.cmd -pl services/event-alert-service -am clean verify
cd frontend
npm.cmd test
npm.cmd run lint
npm.cmd run build
```

The auditor contract is also enforced by CI in the repository's disposable Go
1.27.1 Docker toolchain after generating its ignored SQLBoiler models. Its
PostgreSQL service must report healthy before generation and testing begin, and
the stack is removed after every run.

Results: the clean Maven reactor build passed all 105 Java tests and packaged the
service. The frontend passed 9 tests across 2 files, ESLint, and the Vite
production build. Focused Go Kafka validation and audit-repository deduplication
tests passed against the disposable PostgreSQL stack. Recovery coverage verifies
expired audit-lease reclamation, the retry cap, and isolation between records in
a publisher batch.
The ingestion tests also prove that the public search universe is selected from
distinct synthetic client holdings using minimum-count/share controls, mapped to
sector terms through stable instrument IDs, and contains no private portfolio
facts.

## Browser evidence

- [Desktop synthetic alert workspace](../event-alerts-desktop.png)
- [Mobile synthetic alert workspace](../event-alerts-mobile.png)

The browser pass checked the queue, selected alert, synthetic exposure, AI label
and uncertainty, deterministic factors, public citation, decision controls, and
responsive layout. Automated interaction behavior is additionally covered by the
frontend test suite.

## Remaining gates

- Hayyun review and acceptance of the shared/domain/frontend work.
- Human acceptance of proposed ADR-0005 through ADR-0015 as applicable.
- Human review of the AI fixture labels and thresholds.
- AWS-only IaC, IAM, Tavily/Bedrock live evidence, cost/latency benchmark,
  CloudWatch alarms, deployment, and staging recovery drills.
