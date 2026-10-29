# Event-Driven Alerts Budget Baseline

Status: planning baseline. No AWS resource or measured charge is claimed by this
document.

## Boundaries

| Boundary | Amount | Purpose |
|---|---:|---|
| Project allocation | USD 300 | Total approved project budget |
| Project contingency | USD 100 | Reserved; event alerts must not consume it without human approval |
| Event-alert monthly warning | USD 25 | Application kill-switch threshold and investigation trigger |
| Event-alert development hard cap | USD 50 | Maximum monthly feature envelope before explicit owner approval |

The application threshold is deliberately below the development hard cap so a
human can investigate before the feature consumes its full allocation.

## Planning envelope

| Cost source | Planning quantity | Planning estimate | Evidence still required |
|---|---:|---:|---|
| Tavily basic news search | 720 credits/month (one credit/search per current API contract) | Unknown until account-plan benchmark | Tavily usage dashboard, invoice/plan terms and measured credits |
| Bedrock model inference | Bounded by 200,000 tokens/month | Unknown | Selected model, measured input/output tokens, current price |
| Scheduled ECS worker and EventBridge | Bounded scheduled runs | Unknown | Deployed task size, duration, schedule, and bill |
| PostgreSQL, logs, metrics, alarms, data transfer | Shared/incremental usage | Unknown | Tagged resource inventory and billing evidence |

The search quantity follows the approved feature plan's prototype rate. ADR-0050
replaced AgentCore with Tavily; no dollar amount is approved until the owner
records the account plan and a billed lower-environment benchmark. It is a
planning assumption, not a billing claim.

The portfolio-aware universe does not raise this ceiling: ADR-0051 permits at
most four single-issuer searches per scheduled run. A 12-instrument universe
therefore rotates over about 12 hours without losing its per-instrument window.

On 2026-10-29 the local benchmark made six Tavily basic-search calls (three
two-query worker passes) and no AWS or model calls. Under the documented basic
contract this is six search credits; the adapter does not request a usage object,
so account-dashboard billing evidence is still required. The benchmark failed
the usefulness gate and does not authorize live ingestion.

The 2026-10-29 query-design investigation made another 18 Tavily basic-search
calls and no AWS/model calls. Tavily reported one credit for every call. These
calls compared `news`/`finance`, exact-match, preferred-web and restricted
official/vetted sources; they are design evidence, not a passing benchmark.

The ADR-0051 implementation smoke test then made 28 basic-search calls: two
12-issuer passes and a four-issuer confirmation. It made no AWS/model call.
The calls established a 7/7 manually reviewed precision sample after local
title and preview filtering, but billing-dashboard confirmation and an
independent Recall@10 set are still required.

The full repeat verification made a further 28 basic-search calls and no AWS or
model calls: 12 calls from a discarded local-metric run, 12 calls from the valid
12-issuer labelled pass, and four calls through the packaged worker. The valid
pass measured 7/8 Recall@10 and 8/8 manually actionable precision. Tavily did not
return a usage-credit field in these responses, so the provider dashboard remains
the billing source of truth; the discarded calls must still be counted there.

## Enforced application controls

- Ingestion and model access default to disabled.
- Search use is limited to 4 reservations per run and 1,000 per UTC month.
- Model use is limited to 2,000 tokens per invocation and 200,000 per UTC month.
- Reaching the USD 25 estimated monthly threshold disables ingestion and model
  access together.
- Re-enabling a configured capability requires an identified human actor, a
  correlation identifier, and a successful durable audit write.
- The USD 100 project contingency is excluded from automatic operation.

Before cloud enablement, the human owner must approve the provider prices,
replace planning figures with measured evidence, confirm the AWS Budget/alarms,
and record the decision. Update this file when pricing, quotas, or architecture
changes.
