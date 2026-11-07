# Target architecture: x-factor features (Features 4–6) and the business dashboard (Feature 7)

> **Status:** Accepted target architecture (2026-11-01). **Owner:** Neo Jia Wen. **Last updated:** 2026-11-01.  
> Drafted with AI assistance (Claude Code) from the team's architecture document. Verify AWS pricing, limits and service behaviour against official AWS documentation before accepting.  
> Part of the [target architecture](system-overview.md); each decision is recorded as an ADR in the [ADR index](../adr/README.md), which gives its status.

Features 4–6 are the team's innovation features (x-factors): market-news alerts, Agent Engagement and the Client Relationship Health Monitor. Feature 7, the business dashboard, is an additional feature ([ADR-0074](../adr/ADR-0074-x-factor-classification.md)). The designs below come from the team's feature branches and the target architecture's decisions. Each subsection lists what was chosen and what was not, with the reason.

## X-factor 1: event-driven market-news alerts

Recorded in [ADR-0031](../adr/ADR-0031-xf1-alert-processing-search-region-boundary.md) (Accepted; amended on 2026-11-01 to follow [ADR-0050](../adr/ADR-0050-event-search-provider-after-scp-block.md), Accepted, for the search provider and the model), [ADR-0032](../adr/ADR-0032-xf1-instrument-master-deterministic-matching.md) (Accepted), [ADR-0048](../adr/ADR-0048-alert-worker-terraform-stack.md) (Accepted) and [ADR-0066](../adr/ADR-0066-crm-linked-current-alert-holdings.md) (approved by the feature owner).

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
- A worker started every 4 hours selects instruments held by **at least 2 clients and at least 20% of clients with positions** (configurable; the old "or 50% of one account" rule was removed on 2026-11-07; [ADR-0016](../adr/ADR-0016-holdings-derived-search-universe.md)). The search terms never carry client counts, shares or which rule chose the instrument.
- It makes at most four searches a run: two focused queries (results, and corporate actions) for each of two issuers, rotating through the eligible instruments ([ADR-0051](../adr/ADR-0051-single-issuer-search-and-relevance-gate.md)). Each goes to Tavily restricted to a date range and to approved domains: the issuer's official, exchange and regulator domains from the instrument master, plus Reuters, CNBC and the FT. A result must name an approved issuer and an actionable event to pass the relevance gate.
- Back in Singapore, affected holdings are found by **exact ticker or instrument match, sector match and named-company match**. Relevance weighs direct versus sector-level impact, exposure amount and share, event confidence, data freshness and alert thresholds.
- The model writes one shared summary per event, from public data only. Private context (for example a client's 45% financial-sector exposure) is computed in Singapore without the model. Only agents assigned to affected clients (`assignedAgentSubject`) receive the alert. Each agent sees one briefing per event with the affected clients listed, and records `INFORM_CLIENT`, `MONITOR` or `DISMISS`; decisions and feedback are audited. Informing a client is the agent's action: they can open a follow-up e-mail draft from the alert in Agent Engagement.

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
| Topic selection | At least 2 clients and at least 20% of clients hold the instrument | Searching every holding: sends search terms for every rare holding and multiplies search cost. |
| Schedule | Every 4 hours: a worker started by EventBridge (6 runs a day). At the cap of 4 rotating single-issuer searches per run, that is about 720 searches a month, within the 1,000-query monthly quota. | Every 6 hours: a third fewer searches and model calls, but news up to about 6 hours old. Daily: news could be almost a day old when agents see it. Per-trade triggering: many more searches, and the privacy thresholds need a whole-population view. |
| Summaries | Amazon Nova 2 Lite through its global inference profile, called from Singapore, from public data only ([ADR-0050](../adr/ADR-0050-event-search-provider-after-scp-block.md)) | Bedrock in Singapore only: no model the project account can invoke runs in-Region there (Nova only through inference profiles; third-party models are blocked by their Marketplace subscription). An Asia-Pacific profile: Nova 2 Lite has only a global one. A global profile is acceptable here, and not for x-factor 3, because this model sees no client data. |
| Storage | Own RDS instance, alerts-db | A shared database cluster (branch ADR-0008): weaker isolation; inconsistent with one database per service. |
| Holdings source | Portfolios agents record against CRM accounts, chosen with `alert_holdings_mode = "database"` ([ADR-0066](../adr/ADR-0066-crm-linked-current-alert-holdings.md)); the bundled synthetic fixture (alerts branch ADR-0005) stays the default | Extending the fixture: a second source of identities and assignments, rebuilt into the image for every change. A production holdings feed: future integration work. |
| Dissemination to clients | The agent decides; an optional follow-up e-mail is drafted from the alert in Agent Engagement and sent only by the agent ([ADR-0068](../adr/ADR-0068-follow-ups-from-client-health-and-alerts-and-draft-retention.md)) | Automatic e-mail from the alert service: no agent judgement, and alert content (instrument, headline) would reach the drafting model. |

## X-factor 2: Agent Engagement (Feature 5)

Recorded in [ADR-0051](../adr/ADR-0051-engagement-service-and-mail-dispatcher.md) to [ADR-0057](../adr/ADR-0057-holiday-sync-from-data-gov-sg.md) (the engagement records), [ADR-0067](../adr/ADR-0067-nova-lite-asia-pacific-for-client-derived-wording.md) and [ADR-0068](../adr/ADR-0068-follow-ups-from-client-health-and-alerts-and-draft-retention.md) (Accepted). Plan: [agent-engagement_plan.md](../feature-plans/agent-engagement_plan.md).

Gives agents one place to plan client contact: meetings that appear in their own mail calendar, reminder e-mails, and ready-to-edit follow-up e-mails. The Health Monitor and the alerts decide who needs attention; Agent Engagement handles how and when the agent makes contact, and never sends anything without the agent.

- engagement-service runs the API and a mail dispatcher in every task. Rows are claimed with `FOR UPDATE SKIP LOCKED` and a lease, so delivery is at least once and nothing is sent while the stack is stopped.
- Calendar invites are e-mailed iCalendar requests and cancellations with a stable `UID` and a rising `SEQUENCE`; Gmail and Outlook add, move or remove the meeting. The agent is the only attendee.
- Drafts come from Amazon Nova Lite with placeholders, the purpose and public holidays only. The service fills in the client's first name and the agent's name when it returns a draft, and turns them back into placeholders when it saves ([ADR-0056](../adr/ADR-0056-drafts-edited-as-plain-text.md)).
- Two follow-up purposes are linked to their source: a check-in from a Client Health task and a follow-up from an alert. engagement-service reads the source with the agent's own token, through the internal ALB, and keeps only its client field; the task's evidence and the alert's content never reach the model. These drafts have an `{{agent_details}}` slot the agent must fill before sending, and what the agent adds is sent as written ([ADR-0068](../adr/ADR-0068-follow-ups-from-client-health-and-alerts-and-draft-retention.md)).
- A follow-up goes to the client with the agent in Bcc. Sending or discarding a draft clears its text, and open drafts unchanged for 14 days are discarded.
- Not in scope: two-way calendar sync, inviting clients, recurring events, bulk e-mail and any automatic sending.

| Decision | Chosen | Not chosen, and why |
|---|---|---|
| Where it runs | A new Java 25 engagement-service with its own Terraform stack | Extending dashboard-service: the stateless dashboard would gain a database, writes and secrets, and rolling it back would stop reminders. Several services: about three times the fixed cost for data that belongs together. |
| Data | Own RDS instance, engagement-db (`db.t4g.micro`, single-AZ) | A shared x-factor instance: weaker isolation, kept as a cost fallback. DynamoDB: due-time queries, locking and the outbox would all be new. |
| Sending queued mail | A dispatcher inside the service, claiming rows with a lease | A worker-mode service: another task for a few e-mails a day. One EventBridge schedule per reminder calling a Lambda: a second runtime, and every event change must update schedules. A task every minute: a cold start every minute. |
| Calendar | E-mailed iCalendar invites (iTIP `REQUEST` and `CANCEL`) | A subscribed webcal feed: refreshed only every few hours, and needs a route with no login. Google Calendar API or Microsoft Graph: per-agent OAuth, stored refresh tokens, one provider per integration. CalDAV: poorly supported by Gmail and Outlook.com. |
| E-mail | Amazon SES, identity in the `mail-identity` stack | A third-party e-mail API: an outside account and API key, data processed outside AWS. SNS e-mail: fixed formatting and no calendar parts. `mailto:` links: no record of what was sent. |
| Agent's address | Cognito ListUsers by `sub` | A new user-service endpoint: a Feature 1 change that does not help the dispatcher. An address sent by the frontend: cannot be trusted. |
| Drafts | Bedrock from placeholders and public facts, with a template fallback | Templates only: no reason to use AI. Full client context or the agent's free text: personal data would reach the model. |
| Model and region | Amazon Nova Lite through the Asia-Pacific profile ([ADR-0067](../adr/ADR-0067-nova-lite-asia-pacific-for-client-derived-wording.md)) | Larger in-region models: blocked by their Marketplace subscription in the project account. A global profile: requests could be processed in any Region worldwide. |
| Follow-ups from tasks and alerts | Linked by ID, checked with the agent's token, only the client field read | Copying the task's or alert's text into the prompt: client finances would reach the model. No link: the audit trail could not show why the e-mail was sent. |
| Draft retention | Text kept only while the draft is open; 14-day expiry | Keeping every sent body: client e-mail text outside the client-erasure path. |
| Holidays | A versioned table seeded by migrations; new years from data.gov.sg ([ADR-0057](../adr/ADR-0057-holiday-sync-from-data-gov-sg.md)) | A public holiday API at run time: a third-party dependency for data that changes once a year. |

## X-factor 3: Client Relationship Health Monitor

Recorded in [ADR-0034](../adr/ADR-0034-xf3-health-monitor-rules-and-bedrock-boundary.md) (Accepted; its Bedrock region is superseded by [ADR-0067](../adr/ADR-0067-nova-lite-asia-pacific-for-client-derived-wording.md), Accepted).

Detects explainable changes in client banking activity and turns them into prioritised, auditable follow-up tasks for agents. The MVP has three deterministic signals: **recurring-credit interruption**, **transaction-activity decline** and **sustained net-outflow trend**; more will follow.

- The Java rules engine calculates every signal, determines severity, supplies exact evidence, and handles suppression and deduplication. One active task per client collects all qualifying evidence.
- Bedrock only turns verified facts into readable wording, with Amazon Nova Lite through its Asia-Pacific inference profile, called from Singapore ([ADR-0067](../adr/ADR-0067-nova-lite-asia-pacific-for-client-derived-wording.md)). AWS may process a request in Sydney, Tokyo, Seoul, Osaka or Mumbai as well as Singapore, never outside Asia-Pacific. Only the daily analysis task calls it. It receives signal type, percentage or banded changes, transaction counts, time-window lengths, expected cadence and days since an expected credit.
- Bedrock **never** receives names, client IDs, account numbers, raw transactions, descriptions, exact balances, agent IDs or contact information.
- If Bedrock fails or returns unsupported content, a deterministic template is used, so AI failure never stops task creation.
- The analysis task reads matched activity from transaction-service (`/internal/v1/transactions`, scope `crm-transactions/read`; [ADR-0065](../adr/ADR-0065-client-health-matched-transaction-history.md)) and current assignments from client-service (`/internal/v1/clients/{clientId}/assignment`, scope `crm-clients/assignment.read`) through the internal ALB, with a client-credentials token from one Cognito machine client, because a standalone scheduled task cannot use Service Connect. The task API checks current assignments the same way. API Gateway forwards only `/api/*`, so both routes stay private. The contract-faithful synthetic provider supports local work.
- The analysis task is the service's image started with `CLIENT_HEALTH_MODE=ANALYSIS`. It analyses one Singapore date without a web server, under a 60-minute lease, and exits 0 when the run completes and 1 otherwise. It commits tasks, run state and audit rows to health-db; the task API relays the outbox to the audit queue.
- **Health probes:** `/actuator/health/liveness` checks only that the process is alive; `/actuator/health/readiness` adds health-db connectivity. Both are anonymous for ALB and ECS checks and return no details. Bedrock, SQS, transaction replay and assignment outages do not make the task API unready, because each has a template, outbox or fail-closed path.
- **Metrics and alarms:** identifier-free Micrometer metrics cover analysis runs and the age of the last successful watermark, replay, rule signals, task outcomes and authorization denials, assignment lookups, AI calls, fallbacks, tokens and estimated cost, and the audit backlog ([monitoring and alerting](../observability/monitoring-alerting.md)). Tags are closed enums; client, account, task, transaction and agent identifiers are never labels. Both tasks publish them to CloudWatch under `Scrooge/ClientHealth`, the only namespace their role may write. SNS alarms fire on no successful watermark for 27 hours, any failed run, more than 100 undelivered audit events or one older than 15 minutes, 3 or more assignment outages in 15 minutes, more than 10 authorization denials in 5 minutes, and any AI validation or provider failure; an EventBridge rule also alerts when the analysis task exits non-zero.
- An agent can open a check-in e-mail draft from a task in Agent Engagement; only the task's client field is read for it ([ADR-0068](../adr/ADR-0068-follow-ups-from-client-health-and-alerts-and-draft-retention.md)).

| Decision | Chosen | Not chosen, and why |
|---|---|---|
| Detection | Deterministic Java rules engine | LLM detection or prioritisation: not explainable, not reproducible, hard to audit. |
| AI use | Bedrock for wording only, with a template fallback | No AI: tasks are harder to read, and at least one x-factor must use AI. AI in the critical path: an outage would stop task creation. |
| Trigger | EventBridge-scheduled analysis task, same image, once a day at 08:00 SGT | A timer inside the always-on service: runs in every copy and restarts with each deployment. Hourly or more often: the 30-day and 90-day comparison windows barely change within a day, so extra runs repeat work. Overnight: outside the demo week the databases are stopped outside working hours, and agents see new tasks at the start of their day either way. |
| Bedrock region | Nova Lite through its Asia-Pacific profile, called from Singapore | Singapore only: no model the project account can invoke runs in-Region there. A global profile: the facts come from client activity, so they stay in Asia-Pacific. |
| Storage | Own RDS instance, health-db | A shared database: weaker isolation. |

## Additional feature: business dashboard (Feature 7)

Recorded in [ADR-0033](../adr/ADR-0033-xf2-dashboard-topology-cache-audit.md) (Accepted). What each figure means is in [ADR-0038](../adr/ADR-0038-dashboard-metric-definitions.md) (Accepted), and the owning services' endpoints are in [ADR-0039](../adr/ADR-0039-dashboard-aggregate-endpoints.md) (Accepted). Plan: [business-dashboard_plan.md](../feature-plans/business-dashboard_plan.md).

Admins see bank-wide and per-agent figures; agents see their own book and work queue. The pages ship inside the existing frontend; the figures come from `dashboard-service`.

- The owning services serve the figures on private `/aggregates` routes, outside `/api`, scoped by the caller's token and audited once by the dashboard.
- Transaction figures come from transaction-service, which scopes an agent's request to the accounts account-service says they own ([ADR-0064](../adr/ADR-0064-transaction-service-authenticates-its-own-reads.md)).

| Decision | Chosen | Not chosen, and why |
|---|---|---|
| Topology | Stateless dashboard-service; each owning service computes its own aggregates (pushdown), fetched with the caller's JWT | Its own RDS read model: a costly extra instance and a second copy of client data to secure and erase. Event-sourced projection over a broker: infrastructure for its own sake. Composing in the browser: cross-service joins and failure handling in the frontend, untestable payloads, more round trips. Reading other services' databases: breaks data ownership. |
| Access control | Forward the caller's JWT; each service scopes its own answer | A service credential for the dashboard: it could read more than the caller, and the ownership rule would be duplicated. |
| Cache | In-process Caffeine per task, 3 minutes, keyed by caller | ElastiCache: a billable cluster for a small dataset. No cache: every refresh fans out to four services. |
| Audit | `DASHBOARD_VIEW` events sent straight to SQS, best-effort; cache hits still audited | An outbox: there is no database to hold one. Failing the request when auditing fails: auditing a read must not take the read down. |
