# Target architecture: x-factor features (Features 4–6)

> **Status:** Proposed target architecture, not yet accepted. **Owner:** Neo Jia Wen. **Last updated:** 2026-11-01.  
> Drafted with AI assistance (Claude Code) from the team's architecture document. Verify AWS pricing, limits and service behaviour against official AWS documentation before accepting.  
> Part of the [target architecture](system-overview.md); each decision is recorded as an ADR in the [ADR index](../adr/README.md), which gives its status.

Features 4–6 are the team's innovation features (x-factors). The designs below come from the team's feature branches and the target architecture's decisions. Each subsection lists what was chosen and what was not, with the reason.

## X-factor 1: event-driven market-news alerts

Recorded in [ADR-0031](../adr/ADR-0031-xf1-alert-processing-search-region-boundary.md) (Proposed; amended on 2026-11-01 to follow [ADR-0050](../adr/ADR-0050-event-search-provider-after-scp-block.md), Accepted, for the search provider and the model), [ADR-0032](../adr/ADR-0032-xf1-instrument-master-deterministic-matching.md) (Proposed), [ADR-0048](../adr/ADR-0048-alert-worker-terraform-stack.md) (Accepted) and [ADR-0066](../adr/ADR-0066-crm-linked-current-alert-holdings.md) (approved by the feature owner).

Agents receive alerts when public market news may matter to instruments their clients hold. Singapore keeps all private client and portfolio processing. Two calls leave Singapore, and both carry public data only: a news search through the Tavily search API, and the model call that classifies each news item and writes its summary.

| Singapore (privacy and decision boundary) | Outside Singapore (public data only) |
|---|---|
| Holdings and client data; the instrument master | — |
| Determine instruments and sectors; choose public search terms | → Tavily (a third-party API, called directly over HTTPS through the NAT gateway) searches approved news domains within a date range |
| ← Check each result's domain and date again, remove duplicates, store the public source | Return news results: title, URL, publication date and an excerpt |
| Send the public source to Bedrock | → Amazon Nova 2 Lite, through its global inference profile, classifies the event and writes one cited summary; AWS may process the request in any Region the profile covers |
| ← Validate the output (schema and citations; abstain when invalid); match holdings deterministically; score client relevance and materiality; add each client's private context deterministically; route alerts to assigned agents | — |

**Flow:**

- Agents record each account's securities positions against its CRM account ([ADR-0066](../adr/ADR-0066-crm-linked-current-alert-holdings.md)); who owns a portfolio follows the client's current agent in client-service. The bundled synthetic fixture remains the default for local work.
- The instrument's sector and industry come from the **instrument master** (a deterministic lookup). Account sector exposures are recalculated from the current portfolios; an account has no single permanent sector.
- A worker started every 4 hours selects instruments held by **at least 2 clients and at least 20% of clients with positions, or making up at least 50% of one account's value** (configurable; [ADR-0016](../adr/ADR-0016-holdings-derived-search-universe.md), Proposed). The search terms never carry client counts, shares or which rule chose the instrument.
- It builds up to four single-issuer queries a run (for example DBS, D05, Financials, earnings, regulatory action; [ADR-0051](../adr/ADR-0051-single-issuer-search-and-relevance-gate.md), Proposed) and sends them to Tavily, restricted to approved domains and a date range.
- Back in Singapore, affected holdings are found by **exact ticker or instrument match, sector match and named-company match**. Relevance weighs direct versus sector-level impact, exposure amount and share, event confidence, data freshness and alert thresholds.
- The model writes one shared summary per event, from public data only. Private context (for example a client's 45% financial-sector exposure) is computed in Singapore without the model. Only agents assigned to affected clients (`assignedAgentSubject`) receive the alert. Their decisions (`INFORM_CLIENT`, `MONITOR`, `DISMISS`) and feedback are audited.

**Tavily and the model never receive:** client or account identifiers; agent assignments; holdings, quantities or values; portfolio percentages; the number or share of clients holding an instrument; client-specific prompts or summaries. **Tavily receives only** company names, tickers, sectors, event keywords, approved domains and date ranges; **the model receives only** the public news source.

**Why a third-party search API.** The plan was Bedrock AgentCore web search in Tokyo, the only Region that offers it. An AWS Organization service control policy, outside the project's control, denies both the AgentCore Gateway and Bedrock's own web search in the project account, so the worker calls Tavily instead ([ADR-0050](../adr/ADR-0050-event-search-provider-after-scp-block.md)). The API key is in Secrets Manager. Search sits behind one interface (`PublicSearchProvider`), so an AWS-native search can replace Tavily later without changing the rest of the pipeline.

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
| Web search | Tavily search API, called directly from the worker, restricted to approved domains, a date range and at most 10 results a query | Bedrock AgentCore web search in Tokyo, and Bedrock's own web search: both denied by an AWS Organization service control policy in the project account; asking for an exception stays open as a parallel track. No search: the feature would have no news source. |
| Categorisation | Deterministic instrument master | LLM classification: not reproducible and can invent sectors. Vector similarity: not deterministic, needs a vector store, harder to explain and audit. |
| Matching news to holdings | Deterministic: ticker, sector, named company | Vector relevance scoring: dropped for the same reasons; no vector infrastructure is needed. |
| Topic selection | At least 2 clients and at least 20% of clients hold the instrument, or it makes up at least 50% of one account's value | Searching every holding: sends search terms for every rare holding and multiplies search cost. |
| Schedule | Every 4 hours: a worker started by EventBridge (6 runs a day). At the cap of 4 rotating single-issuer searches per run, that is about 720 searches a month, within the 1,000-query monthly quota. | Every 6 hours: a third fewer searches and model calls, but news up to about 6 hours old. Daily: news could be almost a day old when agents see it. Per-trade triggering: many more searches, and the privacy thresholds need a whole-population view. |
| Summaries | Amazon Nova 2 Lite through its global inference profile, called from Singapore, from public data only ([ADR-0050](../adr/ADR-0050-event-search-provider-after-scp-block.md)) | Bedrock in Singapore only: no model the project account can invoke runs in-Region there (Nova only through inference profiles; third-party models are blocked by their Marketplace subscription). An Asia-Pacific profile: Nova 2 Lite has only a global one. A global profile is acceptable here, and not for x-factor 3, because this model sees no client data. |
| Storage | Own RDS instance, alerts-db | A shared database cluster (branch ADR-0008): weaker isolation; inconsistent with one database per service. |
| Holdings source | Portfolios agents record against CRM accounts, chosen with `alert_holdings_mode = "database"` ([ADR-0066](../adr/ADR-0066-crm-linked-current-alert-holdings.md)); the bundled synthetic fixture (alerts branch ADR-0005) stays the default | Extending the fixture: a second source of identities and assignments, rebuilt into the image for every change. A production holdings feed: future integration work. |
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

Recorded in [ADR-0034](../adr/ADR-0034-xf3-health-monitor-rules-and-bedrock-boundary.md) (Accepted; its Bedrock region is superseded by [ADR-0067](../adr/ADR-0067-nova-lite-asia-pacific-for-client-derived-wording.md), Accepted).

Detects explainable changes in client banking activity and turns them into prioritised, auditable follow-up tasks for agents. The MVP has three deterministic signals: **recurring-credit interruption**, **transaction-activity decline** and **sustained net-outflow trend**; more will follow.

- The Java rules engine calculates every signal, determines severity, supplies exact evidence, and handles suppression and deduplication. One active task per client collects all qualifying evidence.
- Bedrock only turns verified facts into readable wording, with Amazon Nova Lite through its Asia-Pacific inference profile, called from Singapore ([ADR-0067](../adr/ADR-0067-nova-lite-asia-pacific-for-client-derived-wording.md)). AWS may process a request in Sydney, Tokyo, Seoul, Osaka or Mumbai as well as Singapore, never outside Asia-Pacific. Only the daily analysis task calls it. It receives signal type, percentage or banded changes, transaction counts, time-window lengths, expected cadence and days since an expected credit.
- Bedrock **never** receives names, client IDs, account numbers, raw transactions, descriptions, exact balances, agent IDs or contact information.
- If Bedrock fails or returns unsupported content, a deterministic template is used, so AI failure never stops task creation.
- The analysis task reads matched activity from transaction-service under [ADR-0044](../adr/ADR-0044-client-health-transaction-intake-and-replay.md) and assignments from client-service through the internal ALB with scoped service tokens, because a standalone scheduled task cannot use Service Connect. The contract-faithful synthetic provider supports local work until the Feature 3 owners deliver the production replay endpoint and six-month history.

| Decision | Chosen | Not chosen, and why |
|---|---|---|
| Detection | Deterministic Java rules engine | LLM detection or prioritisation: not explainable, not reproducible, hard to audit. |
| AI use | Bedrock for wording only, with a template fallback | No AI: tasks are harder to read, and at least one x-factor must use AI. AI in the critical path: an outage would stop task creation. |
| Trigger | EventBridge-scheduled analysis task, same image, once a day at 08:00 SGT | A timer inside the always-on service: runs in every copy and restarts with each deployment. Hourly or more often: the 30-day and 90-day comparison windows barely change within a day, so extra runs repeat work. Overnight: outside the demo week the databases are stopped outside working hours, and agents see new tasks at the start of their day either way. |
| Bedrock region | Nova Lite through its Asia-Pacific profile, called from Singapore | Singapore only: no model the project account can invoke runs in-Region there. A global profile: the facts come from client activity, so they stay in Asia-Pacific. |
| Storage | Own RDS instance, health-db | A shared database: weaker isolation. |
