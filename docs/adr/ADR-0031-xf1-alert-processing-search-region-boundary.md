# ADR-0031: X-factor 1 alert processing: 4-hourly worker, Tokyo web search with public terms only, Singapore data boundary, own alerts-db

- Status: Proposed
- Decision date/time: 2026-10-01 21:50 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/target-architecture`
- Related issue/PR: TBD (pull request from `feat/target-architecture` to `dev`)
- Supersedes: N/A
- Superseded by: N/A

## Context

X-factor 1 (event-driven market-news alerts) tells agents when public market news may matter to instruments their clients hold. It needs a source of public news, and at least one of Features 4–6 must use AI. The AWS-native web-search capability, Bedrock AgentCore web search, is not available in Singapore (ap-southeast-1) but is available in Tokyo (ap-northeast-1). Client and portfolio data must stay in Singapore.

The feature's owners have Proposed ADRs on `feat/event-driven-alerts` (owner Azeez). This ADR records the target-architecture position and where it differs, for reconciliation ([open item 4](../architecture/open-items.md#4-reconcile-the-x-factor-branch-adrs-with-the-target-architecture)):
- **branch ADR-0009** (AgentCore search region and fallback): agrees on Tokyo, public terms only, approved domains, and no automatic fallback provider;
- **branch ADR-0006** (alert processing architecture): runs every 4 hours; this ADR first proposed every 6 hours and was changed to match on 2026-10-09;
- **branch ADR-0008** (database topology): a shared database cluster, against its own RDS instance here;
- **branch ADR-0010** (AI provider): a global cross-region inference model, against Bedrock in Singapore here. `event-alert-service` on `dev` defaults to the global model and sends it only the public event report, so this is a choice about residency and cost, not about client privacy.

## Decision Drivers

- Privacy: no client, account, holding or agent data leaves Singapore (NFR 8)
- Course requirement for AI in Features 4–6
- Cost of searches and model calls
- Isolation and database-per-service ([ADR-0023](ADR-0023-rds-per-service-backup-no-cache.md))
- Freshness of alerts

## Options Considered

### Search source

- **Bedrock AgentCore web search in Tokyo, public terms only (chosen):** AWS-native, IAM-authorized, domain-restricted.
- **Singapore:** not available today.
- **A third-party news API:** needs a terms review and benchmark evidence (a later ADR).
- **No search:** the feature would have no news source.

### Schedule

- **A worker every 4 hours, started by EventBridge (chosen, as in branch ADR-0006):** 6 runs a day.
- **Every 6 hours:** a third fewer searches and model calls, but news up to about 6 hours old.
- **Daily:** news could be almost a day old.
- **Per trade:** many more searches, and the privacy thresholds need a whole-population view ([ADR-0032](ADR-0032-xf1-instrument-master-deterministic-matching.md)).

### Summaries

- **Bedrock in Singapore (chosen):** every model call stays in the stack's region; the model is still to be chosen.
- **Global cross-region inference or another provider (branch ADR-0010, and the `dev` default):** the prompt holds public data only, so no client data would leave Singapore, but public event text would be processed outside it.

### Storage

- **Own RDS instance, alerts-db (chosen).**
- **A shared database cluster (branch ADR-0008):** weaker isolation; inconsistent with one database per service.

## Decision

1. **event-alert-service** runs as one image with two modes:
   - an `api` ECS service serving `/api/alerts`: list and detail; decisions `INFORM_CLIENT`, `MONITOR`, `DISMISS`; feedback;
   - a `worker` ECS task started **every 4 hours** by EventBridge Scheduler.

   Admins can read alerts but not decide.
2. **Data boundary:**
   - only public terms go to **Bedrock AgentCore web search in Tokyo**, out through the NAT gateway: company names, tickers, sectors, event keywords, approved domains and date ranges;
   - Tokyo never receives client or account IDs, agent assignments, holdings, quantities or values, portfolio percentages, the number or share of clients holding an instrument, or client-specific prompts;
   - Tokyo returns a public event report.
3. **In Singapore**, the worker:
   1. validates and de-duplicates the report;
   2. matches holdings deterministically ([ADR-0032](ADR-0032-xf1-instrument-master-deterministic-matching.md));
   3. scores materiality;
   4. writes one shared summary per event with **Bedrock in Singapore**, from the public event report only; each client's exposure and relevance come from steps 2 and 3, without the model;
   5. routes each alert to the assigned agent (`assignedAgentSubject`).

   Agent decisions and feedback are audited through the outbox.
4. **Storage:** its own RDS instance, **alerts-db**:
   - holdings (synthetic for the MVP) and the instrument master;
   - account sector exposures and public events;
   - alerts, decisions and the outbox.
5. **Controls:**
   - a search cap (the branch cap of 4 grouped searches per run, about 720 searches a month, within the 1,000-query monthly quota);
   - kill switches for search and model calls, whose state, the monthly counts and the estimated cost are kept in alerts-db so that every worker run and the API share them (see the amendment in ADR-0014);
   - one ingestion run at a time, enforced by a PostgreSQL advisory lock;
   - a separate Bedrock budget alert;
   - a failed Tokyo search ends that run without inventing results.
6. **Tokyo is an availability choice, not a requirement.** The search moves to Singapore if the capability becomes available there. Availability must be confirmed in the live AWS spike ([open item 14](../architecture/open-items.md#14-tokyo-web-search-availability)).
7. Dissemination to clients is outside the system: the agent decides and contacts the client.

## Rationale

- Only public, non-client data crosses regions, which keeps the privacy argument simple.
- Every 4 hours matches the feature owner's ADR-0006 and plan, and keeps news fresher; the extra searches still fit the 1,000-query monthly quota.
- The model never receives client data, so the privacy boundary does not depend on where it runs. Bedrock in Singapore also keeps every model call in the stack's region; whether the branch's global-inference model is acceptable instead is [open item 4](../architecture/open-items.md#4-reconcile-the-x-factor-branch-adrs-with-the-target-architecture).
- Its own database follows database-per-service.

## Consequences

### Positive

- Clear, explainable data boundary.
- Bounded AI and search spend.
- The search region can change without redesigning the pipeline.

### Negative / Trade-offs

- News can be up to about 4 hours old.
- 50% more searches and model calls than running every 6 hours.
- Depends on AgentCore availability in Tokyo.
- One more RDS instance (about USD 13–15/month single-AZ).
- Conflicts with two of the feature owners' Proposed ADRs (ADR-0008, ADR-0010) until reconciled.

## Security & Privacy Impact

- Cross-region traffic carries public terms only.
- Domain allow-list enforced at the AgentCore target.
- IAM-authorized invocation.
- No CRM identifiers in prompts sent to Tokyo.

## Cost Impact

Bedrock and AgentCore usage is roughly USD 5–15/month with caps and kill switches. Searches cost about USD 5.04/month at 720 searches. alerts-db adds about USD 13–15/month single-AZ.

## Operational Impact

- Worker run metrics.
- An alarm on failed runs.
- A Bedrock budget alert.
- Kill-switch configuration in SSM Parameter Store.

## Validation

- A test that inspects every outbound search request and finds no client, account, holding, agent or count data.
- A test that inspects every model request and finds only public event data.
- A run with Tokyo unavailable ends cleanly with no fabricated alerts.
- An agent sees only alerts for their own clients.

## References

- [X-factor 1: event-driven market-news alerts](../architecture/x-factors.md#x-factor-1-event-driven-market-news-alerts)
- [Numbered flows 11–11b](../architecture/data-flow.md#numbered-flows)
- [Open items 4](../architecture/open-items.md#4-reconcile-the-x-factor-branch-adrs-with-the-target-architecture) and [14](../architecture/open-items.md#14-tokyo-web-search-availability)
- `feat/event-driven-alerts`: ADR-0006, ADR-0008, ADR-0009, ADR-0010 (Proposed, owner Azeez). Those files use numbers already taken on `dev` and will need renumbering when merged.

## Revision history

- 2026-10-01 21:50 +08:00: proposed, with the worker every 6 hours.
- 2026-10-09: before acceptance, the schedule changed to every 4 hours to match branch ADR-0006, as decided by the decision owner. Searches rise from about 480 to about 720 a month (USD 3.36 to 5.04). The rest of the decision is unchanged.
- 2026-10-12: before acceptance, decision 3 corrected to match `event-alert-service`, as decided by the decision owner: the model writes one shared summary from public data only, and each client's private context is computed without it. The proposed region is unchanged; open item 4 settles it.
- 2026-10-20: before acceptance, decision 5 clarified to match `event-alert-service`, proposed by Hayyun for the decision owner's review: the kill-switch state and monthly counts live in alerts-db, not in process memory, and an advisory lock allows one ingestion run at a time. The region, schedule and data boundary are unchanged.
