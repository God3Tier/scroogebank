# ADR-0066: CRM-linked current holdings for agent alerts

- Status: Proposed; design draft only, not approved or implemented
- Decision date/time: 2026-10-29 22:45 +08:00 (Asia/Singapore)
- Human owner/decision-maker: Azeez
- Required reviewers: Hayyun (holdings/domain/API); Neo Jia Wen (CRM ownership/security)
- AI-assisted draft: Yes; Codex
- Feature/branch: `task/event-driven-alerts/current-holdings-plan`
- Related plan: [current-holdings plan](../feature-plans/event-driven-alerts-current-holdings_plan.md)
- Supersedes: On acceptance, ADR-0005's bundled-fixture runtime source only
- Amends on acceptance: ADR-0012/0049 current-agent authorization/routing and holdings-read contract; selection thresholds are unchanged
- Superseded by: N/A

## Context and decision drivers

The all-remote audit found clients/accounts and current agent reassignment in CRM,
but no maintained securities-position source. The alert provider still reads
bundled synthetic identities and valuations. Historical scoring snapshots are
not a mutable portfolio book. The requirement is to let newly onboarded project
clients participate after their holdings are recorded, while keeping the agent
in control of decisions.

Account data remains owned by account-service; client assignment remains owned
by client-service. ADR-0060 on current dev explicitly notes that alert holdings
have independent fixture agents. Current retrieval and CRM changes must first be
integrated through their feature branches.

## Options considered

1. Extend/regenerate the bundled fixture: smallest demo change, but onboarding
   requires an image update and has a second identity/assignment source.
2. Store current alert holdings in alerts-db and validate CRM references through
   owning-service APIs (proposed): reuses the existing service and scoring
   contracts, at the cost of private directory dependencies.
3. Add a separate investment/market-data service: a wider authoritative book and
   import system, outside this project task's need and operating budget.
4. Infer securities from bank balances or cash transactions: rejected because
   neither source identifies instruments or their quantities.

## Proposed decision

Use option 2 for dummy project data. Store complete revisioned account portfolios
in alerts-db, expose agent-owned read/replace operations, and retain historical
score inputs separately. New clients have no positions until recorded; no holdings
are fabricated.

Resolve account/client links and current agent through bounded authenticated
private CRM contracts. Check current authority on protected operations and
worker fan-out. Admins remain read-only. Old snapshots and decision actors remain
historical evidence; operational visibility follows the current assigned agent.
Ownership checks are not a cross-database transaction; a detected version change
fails/retries, and the residual check-to-commit race is documented.

An affected instrument selects complete account portfolios for exposure, not
only matching rows. Define holdings-read v2 and explicit snapshot membership.
For one client/event/instrument held in multiple accounts, retain existing alert
cardinality and select the highest qualifying per-account score, with a stable
account-ID tie break; do not merge account percentages. This is a proposed
domain choice requiring Hayyun's approval.

Use full atomic replacements with optimistic revision and idempotency, the
existing fixed-decimal/valuation freshness rules and transactional audit outbox.
Do not auto-refresh valuation timestamps or use a fixture fallback on failure.
The instrument master remains governed; unknown instruments reject an incomplete
replacement rather than reducing its denominator.

## Consequences, security and privacy

New CRM-linked dummy portfolios can participate without code/image changes.
Private directory availability becomes a requirement for current authorization.
Deletion, reassignment and multiple accounts need explicit tests; a missing
answer is not a confirmed absence of holdings. Neither public search nor the
model receives CRM identities, holdings or values.

Existing immutable evidence needs an additive migration away from the
fixture-only `synthetic_portfolio` relationship. Current and historical records
must not be conflated or erased during rollback. Fixture mode remains explicit
and isolated for tests/demos.

## Cost and operations

Reuse current application/database topology; propose no new compute service,
broker or market-data subscription. Narrow service credentials, private requests,
secret storage and optional metrics add measurable overhead. Price/billing
verification and Terraform review precede cloud enablement. No AWS changes,
provider calls or production enablement are authorized by this draft.

## Validation

The plan's matrix requires 30 CRM-linked dummy clients, adding holdings without
rebuilds, current-agent reassignment and old-agent denial, full-account 10/100
exposure, multi-account deduplication, audit/idempotency/concurrency, migration
with old alerts, upstream outage/partial responses, privacy payload inspection,
and Docker/Java/Go/UI/contract/Terraform checks.

## References

- [ADR-0005](ADR-0005-alert-holdings-and-valuation-boundary.md)
- [ADR-0012](ADR-0012-alert-api-event-and-authorization-contracts.md)
- [ADR-0013](ADR-0013-alert-audit-delivery-and-failure-policy.md)
- [ADR-0035](ADR-0035-account-ownership-across-services.md)
- [ADR-0036](ADR-0036-soft-deletion-of-clients-and-accounts.md)
- [ADR-0037](ADR-0037-transactions-database-and-account-linking.md)
- Current-dev ADR-0059/0060/0063: see the pinned branch evidence rather than
  assuming those files already exist on the older feature baseline.
