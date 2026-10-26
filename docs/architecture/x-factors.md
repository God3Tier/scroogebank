# Target architecture: x-factor features (Features 4–6)

> **Status:** Proposed target architecture, not yet accepted. **Owner:** Neo Jia Wen. **Last updated:** 2026-10-14.  
> Drafted with AI assistance (Claude Code) from the team's architecture document. Verify AWS pricing, limits and service behaviour against official AWS documentation before accepting.  
> Part of the [target architecture](system-overview.md); each decision is recorded as an ADR in the [ADR index](../adr/README.md), which gives its status.

Features 4–6 are the team's innovation features (x-factors). The designs below come from the team's feature branches and the target architecture's decisions. Each subsection lists what was chosen and what was not, with the reason.

## X-factor 1: event-driven market-news alerts

Recorded in [ADR-0031](../adr/ADR-0031-xf1-alert-processing-search-region-boundary.md), [ADR-0032](../adr/ADR-0032-xf1-instrument-master-deterministic-matching.md) (Proposed).

Agents receive alerts when public market news may matter to instruments their clients hold. Singapore keeps all private client and portfolio processing; Tokyo performs only a public web search.

| Singapore (privacy and decision boundary) | Tokyo (public web search only) |
|---|---|
| Holdings and client data; the instrument master | — |
| Determine instruments and sectors; choose public search terms | → Search approved public sources for news |
| — | Create a public event report: headline, source, URL, time, factual summary, affected companies and sectors, event class, direction, confidence, evidence |
| ← Validate the report, remove duplicates, check safety rules, store the public event | Return sources and the public summary |
| Match holdings deterministically; score client relevance and materiality; write one shared summary from the public report (Bedrock in Singapore; the model sees no client data); add each client's private context deterministically; route alerts to assigned agents | — |

**Flow:**

- A client buys or sells an instrument; the holding stores client and account ID, instrument ID, quantity, current value and assigned agent.
- The instrument's sector and industry come from the **instrument master** (a deterministic lookup). Account sector exposures are recalculated as holdings change; an account has no single permanent sector.
- A worker started every 4 hours selects instruments held by **at least 2 clients and at least 5% of clients** (both configurable), so a rare holding can never be revealed through an outbound query.
- It builds public queries (for example DBS, D05, Financials, Banks, earnings, regulatory action) and sends them to Tokyo.
- Back in Singapore, affected holdings are found by **exact ticker or instrument match, sector match and named-company match**. Relevance weighs direct versus sector-level impact, exposure amount and share, event confidence, data freshness and alert thresholds.
- The model writes one shared summary per event, from public data only. Private context (for example a client's 45% financial-sector exposure) is computed in Singapore without the model. Only agents assigned to affected clients (`assignedAgentSubject`) receive the alert. Their decisions (`INFORM_CLIENT`, `MONITOR`, `DISMISS`) and feedback are audited.

**Tokyo never receives:** client or account identifiers; agent assignments; holdings, quantities or values; portfolio percentages; the number or share of clients holding an instrument; client-specific prompts or summaries. **Tokyo receives only** company names, tickers, sectors, event keywords, approved domains and date ranges.

**Tokyo is an implementation choice, not a business requirement.** The AWS-native web-search capability is not available in Singapore. If it becomes available there, the search moves back without redesigning the rest of the pipeline. Regional availability must be confirmed in the live AWS spike ([open item 14](open-items.md#14-tokyo-web-search-availability)).

**Instrument master.** Categories are stored against the instrument, not against each holding, so thousands of holdings reference one record:

| Instrument ID | Ticker | Issuer | Sector | Industry |
|---|---|---|---|---|
| SGX:D05 | D05 | DBS Group | Financials | Banks |
| NASDAQ:MSFT | MSFT | Microsoft | Technology | Software |
| SGX:C6L | C6L | Singapore Airlines | Industrials | Airlines |

- Production fields: stable instrument ID, ISIN and exchange ticker, issuer, asset class, sector, industry and sub-industry, currency, country or market, active status, classification provider and version, last-updated time.
- Data comes from an approved security-master or market-data provider. Updates are versioned and audited, for example a reclassification from Industrials to Technology.
- An instrument missing from the master is marked **UNCLASSIFIED** and excluded from automated sector searches until reviewed. An LLM is never asked to guess.
- The MVP has an instrument table (ID, ticker, issuer, sector, currency, active status) with synthetic reference data; loading a full production catalogue is future work.

| Decision | Chosen | Not chosen, and why |
|---|---|---|
| Web search | Bedrock AgentCore web search in Tokyo | Singapore: not available. A third-party news API: needs a terms review and benchmark evidence (later ADR). No search: the feature would have no news source. |
| Categorisation | Deterministic instrument master | LLM classification: not reproducible and can invent sectors. Vector similarity: not deterministic, needs a vector store, harder to explain and audit. |
| Matching news to holdings | Deterministic: ticker, sector, named company | Vector relevance scoring: dropped for the same reasons; no vector infrastructure is needed. |
| Topic selection | At least 2 clients and at least 5% of clients hold the instrument | Searching every holding: would expose rare holdings through outbound queries and multiply search cost. |
| Schedule | Every 4 hours: a worker started by EventBridge (6 runs a day), as in branch ADR-0006. At the branch cap of 4 grouped searches per run, that is about 720 searches (USD 5.04) a month, within the 1,000-query monthly quota. | Every 6 hours: a third fewer searches and model calls, but news up to about 6 hours old. Daily: news could be almost a day old when agents see it. Per-trade triggering: many more searches, and the privacy thresholds need a whole-population view. |
| Summaries | Bedrock in Singapore, from public data only | Global cross-region inference or another provider (branch ADR-0010, and the `dev` default): the model sees no client data, so this is a choice about residency and cost, settled in [open item 4](open-items.md#4-reconcile-the-x-factor-branch-adrs-with-the-target-architecture). |
| Storage | Own RDS instance, alerts-db | A shared database cluster (branch ADR-0008): weaker isolation; inconsistent with one database per service. |
| Holdings source | Synthetic catalogue in the alert service (alerts branch ADR-0005) | A production holdings feed: future integration work. |
| Dissemination to clients | Outside the system: the agent decides and contacts the client | Automatic e-mail through SES: out of scope for the MVP. |

## X-factor 2: business dashboard

Recorded in [ADR-0033](../adr/ADR-0033-xf2-dashboard-topology-cache-audit.md) (Accepted). What each figure means is in [ADR-0038](../adr/ADR-0038-dashboard-metric-definitions.md) (Accepted), and the owning services' endpoints are in [ADR-0039](../adr/ADR-0039-dashboard-aggregate-endpoints.md) (Proposed). Plan: [business-dashboard_plan.md](../feature-plans/business-dashboard_plan.md).

Admins see bank-wide and per-agent figures; agents see their own book and work queue. The pages ship inside the existing frontend; the figures come from `dashboard-service`.

- The owning services serve the figures on private `/aggregates` routes, outside `/api`, scoped by the caller's token and audited once by the dashboard.
- Transaction figures come from transaction-service (today, the SFTP receiver's read API), which scopes an agent's request to the accounts account-service says they own ([ADR-0064](../adr/ADR-0064-transaction-service-authenticates-its-own-reads.md)).

| Decision | Chosen | Not chosen, and why |
|---|---|---|
| Topology | Stateless dashboard-service; each owning service computes its own aggregates (pushdown), fetched with the caller's JWT | Its own RDS read model: a costly extra instance and a second copy of client data to secure and erase. Event-sourced projection over a broker: infrastructure for its own sake. Composing in the browser: cross-service joins and failure handling in the frontend, untestable payloads, more round trips. Reading other services' databases: breaks data ownership. |
| Access control | Forward the caller's JWT; each service scopes its own answer | A service credential for the dashboard: it could read more than the caller, and the ownership rule would be duplicated. |
| Cache | In-process Caffeine per task, 3 minutes, keyed by caller | ElastiCache: a billable cluster for a small dataset. No cache: every refresh fans out to four services. |
| Audit | `DASHBOARD_VIEW` events sent straight to SQS, best-effort; cache hits still audited | An outbox: there is no database to hold one. Failing the request when auditing fails: auditing a read must not take the read down. |

## X-factor 3: Client Relationship Health Monitor

Recorded in [ADR-0034](../adr/ADR-0034-xf3-health-monitor-rules-and-bedrock-boundary.md) (Proposed).

Detects explainable changes in client banking activity and turns them into prioritised, auditable follow-up tasks for agents. The MVP has three deterministic signals: **recurring-credit interruption**, **transaction-activity decline** and **sustained net-outflow trend**; more will follow.

- The Java rules engine calculates every signal, determines severity, supplies exact evidence, and handles suppression and deduplication. One active task per client collects all qualifying evidence.
- Bedrock only turns verified facts into readable wording. It receives signal type, percentage or banded changes, transaction counts, time-window lengths, expected cadence and days since an expected credit.
- Bedrock **never** receives names, client IDs, account numbers, raw transactions, descriptions, exact balances, agent IDs or contact information.
- If Bedrock fails or returns unsupported content, a deterministic template is used, so AI failure never stops task creation.
- The analysis task reads matched activity from transaction-service and assignments from client-service through the internal ALB with a service token, because a standalone scheduled task cannot use Service Connect. The SFTP import now stores `account_id` and `client_id` on matched rows ([ADR-0037](../adr/ADR-0037-transactions-database-and-account-linking.md)), but the rest of the activity contract is still open ([open item 16](open-items.md#16-health-monitor-activity-contract)).

| Decision | Chosen | Not chosen, and why |
|---|---|---|
| Detection | Deterministic Java rules engine | LLM detection or prioritisation: not explainable, not reproducible, hard to audit. |
| AI use | Bedrock for wording only, with a template fallback | No AI: tasks are harder to read, and at least one x-factor must use AI. AI in the critical path: an outage would stop task creation. |
| Trigger | EventBridge-scheduled analysis task, same image, once a day at 08:00 SGT | A timer inside the always-on service: runs in every copy and restarts with each deployment. Hourly or more often: the 30-day and 90-day comparison windows barely change within a day, so extra runs repeat work. Overnight: outside the demo week the databases are stopped outside working hours, and agents see new tasks at the start of their day either way. |
| Bedrock region | Singapore | Cross-region inference: even banded facts would leave the region for no benefit. |
| Storage | Own RDS instance, health-db | A shared database: weaker isolation. |
