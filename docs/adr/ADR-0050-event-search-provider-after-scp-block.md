# ADR-0050: Event search provider, after the Tokyo Gateway and Bedrock Web Search are both blocked

- Status: Accepted
- Decision date/time: 2026-10-22 (Asia/Singapore)
- Decision owner(s): Azeez (owner of EDA-D1-02; selected Tavily on 2026-10-23)
- Required reviewer: Hayyun
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/event-driven-alerts`
- Related issue/PR: TBD
- Supersedes: Amends [ADR-0009](ADR-0009-agentcore-search-region-and-fallback.md)'s "no automatic fallback provider in the MVP" clause, which already anticipated this: "a financial-news API requires benchmark evidence and a later ADR." This is that ADR.
- Superseded by: N/A

## Context

[ADR-0009](ADR-0009-agentcore-search-region-and-fallback.md) and [ADR-0048](ADR-0048-alert-worker-terraform-stack.md) committed the worker to searching through one IAM-authorized AgentCore Gateway in Tokyo (`ap-northeast-1`). That gateway was never created, because the live spike it depended on hit an AWS Organization-level Service Control Policy (SCP `p-88ezx8iz`, managed from the Organization's management account `654654470350`, not this project's account `727816232662`) that explicitly denies `bedrock-agentcore:InvokeGateway`.

This session tried every alternative that stays inside AWS and does not require escalating to whoever controls that Organization:

1. **A different region for AgentCore.** Not available: the AgentCore web search connector (`agentcore-web-search-1.2.0`, [ADR-0031](ADR-0031-xf1-alert-processing-search-region-boundary.md)) only exists in Tokyo.
2. **Amazon Bedrock's own native "Web Search" tool** (generally available since August 2026, for OpenAI GPT models including the already-pinned `openai.gpt-5.6-luna`, via a separate `bedrock-mantle` endpoint, not Converse). Tested directly against this account: denied by the **same SCP**, this time on `bedrock-mantle:CreateInference`.
3. Between #1 and #2, the SCP has now been confirmed blocking three unrelated things: `bedrock-agentcore:InvokeGateway` (Tokyo), `logs:DescribeLogGroups`/`logs:FilterLogEvents` (`us-east-1`), and `bedrock-mantle:CreateInference` (`us-east-1`). This reads as a deliberate, broad guardrail on the newer/advanced Bedrock-adjacent services account-wide, not a narrow, Tokyo-specific or AgentCore-specific restriction. No further AWS-native search feature is likely to clear it without someone with access to the management account changing the policy.

Separately, and independent of the search question: the AI classification model itself ([ADR-0010](ADR-0010-alert-ai-provider-and-safety.md)'s pinned `global.openai.gpt-5.6-luna`) cannot be invoked in this account either — Bedrock returned an `AccessDeniedException` about an AWS Marketplace subscription for that model that "cannot be completed at this time" from this member account. This was confirmed not to be specific to that one model: Anthropic's Claude Haiku 5.5 hit the identical Marketplace error. Amazon's own models (Nova family) need no such subscription and worked immediately.

**What this session proved works, end to end, with a real model call:** a fake article was inserted directly into `source_result` (standing in for a Tokyo search result) and run through the real worker, with the classifier temporarily pointed at `global.amazon.nova-2-lite-v1:0` (a model-contract-compatible swap — see Decision 3 below) instead of the blocked `global.openai.gpt-5.6-luna`:

- Deterministic matching found the article against the instrument master ([ADR-0032](ADR-0032-xf1-instrument-master-deterministic-matching.md)).
- Nova classified it as `REGULATORY`/`NEGATIVE` at 0.90 confidence — a real model response, not the rule-based fallback.
- A `public_event` row and an `alert.classification-recorded` audit entry were persisted and delivered.
- Two real `Alert` rows were created (`materiality-v1`, score 92.00) for both synthetic clients holding the affected instrument, each with its own `alert.generated` audit entry, delivered.

The only piece that could not be exercised is the Tokyo search call itself (and, by extension, real news content instead of a hand-written fixture article).

Two bugs were found and fixed along the way, independent of the SCP/Marketplace blockers, and should stay fixed regardless of what this ADR decides:

- A Spring bean conflict (`NoUniqueBeanDefinitionException` for `AiModelGateway`) silently prevented `ai_enabled=true` from ever starting. Fixed with `@Primary` on `BedrockAiModelGateway`.
- `synthetic_portfolio` was never seeded for any client, so `holding_snapshot` inserts failed a foreign key check the first time any run ever reached alert creation. Fixed with `V6__seed_synthetic_portfolio.sql`.
- `BedrockAiModelGateway` was rewritten to ask models for structured output via Bedrock's tool-use/function-calling mechanism instead of the Converse `outputConfig.textFormat=JSON_SCHEMA` feature, because no model actually invokable in this account (Nova) supports that feature — only certain third-party models do, and those are the ones blocked by Marketplace. Tool-use is supported by Nova and produces the same structured JSON the classifier already validates.

## Decision Drivers

- Ship a search step that actually runs in this AWS account, without waiting on an Organization-level policy change outside the team's control.
- Keep the private/public data boundary ADR-0009 and ADR-0031 already established: only public issuer/ticker/sector/event terms and a bounded date range ever leave the service, never CRM identifiers, holdings, or audit data.
- Keep the provider interface reversible: if the SCP is ever relaxed, a reviewed AgentCore adapter can be reintroduced without redesigning ingestion.
- Do not block the already-proven classification → scoring → alert → audit chain on this decision; it does not depend on which search provider feeds it.

## Options Considered

- **Escalate to whoever controls the AWS Organization's management account** to exempt this account from the relevant SCP statements. The only option that keeps the architecture exactly as ADR-0009/ADR-0031/ADR-0048 describe it. Available as a parallel, secondary path — pursue it, but do not block on it, since its timeline and outcome are outside the team's control.
- **Amazon Bedrock's native Web Search (Mantle).** Ruled out: confirmed blocked by the same SCP as the Gateway.
- **A different AWS region for AgentCore.** Ruled out: the web search connector only exists in Tokyo.
- **Call a search/news API directly from the worker, bypassing Bedrock entirely (chosen).** No `bedrock-*` action is involved, so the SCP cannot apply. Implemented behind the existing `PublicSearchProvider` interface ([`PublicSearchProvider.java`](../../services/event-alert-service/src/main/java/bank/alert/ingestion/PublicSearchProvider.java)), so nothing downstream of ingestion changes.
- **Leave Tokyo wired up and document the gap, do nothing else.** Rejected as the only action: it is a safe fallback, not a path to a working feature, and the team has over three weeks left to ship this.

## Decision

1. **Use Tavily through `TavilySearchProvider`,** which calls `https://api.tavily.com/search` directly from the worker process inside the existing VPC egress path (NAT gateway), with no AgentCore or Bedrock Mantle involved. The endpoint is pinned rather than operator-configurable so a configuration change cannot redirect the API key to another host.
2. **The request stays bounded exactly as ADR-0009 already requires:** public issuer/ticker/sector/event terms, an inclusive UTC date range, at most ten results, and Tavily's restrictive `include_domains` mode mirroring `mas.gov.sg`, `sgx.com`, and reviewed issuer investor-relations domains. It uses `topic=news`, basic search with one chunk per source, disables answers/raw content/images/automatic parameters, and enables published-date filtering. The adapter re-checks the exact instant window and approved HTTPS domain locally because Tavily's `start_date` and exclusive `end_date` have day precision. No CRM identifier, holding, exposure, note, agent, or audit data is ever sent.
3. **Classification uses Bedrock Runtime with `global.amazon.nova-2-lite-v1:0` from the Singapore (`ap-southeast-1`) source Region.** Nova is the production default rather than a temporary substitute for the Marketplace-gated OpenAI model. Global inference may route processing to another supported AWS Region. Tavily owns live-news retrieval, so neither the classifier nor the search path requires a Tokyo endpoint. `BedrockAiModelGateway`'s tool-use implementation is required for this contract and should not be reverted.
4. **The provider's API key is a new secret** (AWS Secrets Manager, following the same pattern as the alerts-db credential), never a Terraform variable or plain environment value. Terraform creates the secret container but not a secret version; an operator populates it out of band before enabling ingestion. ECS injects it through the task execution role, which is limited to that secret and the database secret. `stacks/alert-worker` drops the AgentCore URL/ARN variables and `InvokeTheSearchGateway` IAM policy.
5. **Escalating the SCP stays open as a parallel, lower-priority track.** If it succeeds, reintroducing an AgentCore adapter requires a new reviewed change at the `PublicSearchProvider` seam; inactive production code is not retained.
6. **Azeez owns the provider choice, the new ADR revision (or a follow-up ADR) naming it, the Terraform change, and the benchmark** ADR-0009 already asked for ("a financial-news API requires benchmark evidence"): result quality, latency, and actual cost against real queries before this goes into the budget in [the event-driven-alerts budget doc](../cost/event-driven-alerts-budget.md).

## Rationale

This keeps every already-proven part of the pipeline untouched — matching, classification, scoring, alert generation, and the audit trail all already work, with a real model call, against a real database. The only change is at the one seam the codebase already isolated for exactly this kind of swap (`PublicSearchProvider`), so the Tavily adapter does not touch `WorkerRunner`, `SourcePipeline`, or anything downstream of ingestion.

## Consequences

### Positive

- Unblocks the feature without waiting on an Organization-level policy change.
- Touches one interface implementation and one Terraform stack; no change to the proven classification/scoring/alert/audit code.
- Reversible: escalation can still happen later and swap the provider back with no further redesign.

### Negative / Trade-offs

- A new third-party vendor and a new secret to manage, cost, and monitor — outside what ADR-0009 originally scoped.
- Loses whatever curation AgentCore's Tokyo connector would have enforced (the administrator domain allowlist mechanism); the new provider must enforce (or be configured to enforce) the same bound in its own request, not assumed from AgentCore's behavior.
- Global inference does not provide Singapore-only processing. A later residency requirement would require a separately supported in-Region or geographic inference profile and a reviewed ADR.

## Security & Privacy Impact

- No change to what leaves the service: still public search terms only, per ADR-0009/ADR-0031's existing boundary. The new provider's request payload must be reviewed against that boundary before launch.
- A new secret (the provider's API key) in Secrets Manager, scoped to the worker task execution role only, as ECS secret injection requires.
- Removes the `bedrock-agentcore:InvokeGateway` IAM statement entirely from the worker's task role (dead permission, since the Gateway is not being created).

## Cost Impact

Depends on the provider Azeez chooses and its pricing (typically a per-query or monthly-tier model for search APIs). To be benchmarked against ADR-0009's 1,000 searches/month and four grouped queries/run bound, and checked against [the event-driven-alerts budget](../cost/event-driven-alerts-budget.md) and the `crm-bedrock`/Bedrock-adjacent budgets in `bootstrap` before this goes live.

## Operational Impact

- Deploy: one new Secrets Manager secret, one Terraform change to `stacks/alert-worker` (drop `agentcore_gateway_arn`/its IAM policy, add the new secret), no change to `stacks/crm`.
- Runbook: the event-alert operations runbook's ingestion section needs a short addition once the provider is named.
- Nothing about teardown changes: the worker stack still owns no data and destroys independently of `stacks/crm`.

## Validation

- Unit/contract test `TavilySearchProvider`: bounded request, response size cap, local domain/date validation, and provider-error mapping to `SearchProviderException`.
- Re-run the exact mock-data path this session used (insert a `source_result` row, reset `processed_at`, run the worker once) once with a *real* search result from the new provider, to confirm the same classification → scoring → alert → audit chain holds end to end with real search content, not just a hand-written fixture.
- Benchmark result quality, latency, and billed cost against real queries before raising `ingestion_enabled` in production, per ADR-0009's original ask.

## References

- [ADR-0009](ADR-0009-agentcore-search-region-and-fallback.md), [ADR-0010](ADR-0010-alert-ai-provider-and-safety.md), [ADR-0031](ADR-0031-xf1-alert-processing-search-region-boundary.md), [ADR-0032](ADR-0032-xf1-instrument-master-deterministic-matching.md), [ADR-0048](ADR-0048-alert-worker-terraform-stack.md), [ADR-0049](ADR-0049-alert-source-to-alert-pipeline.md)
- [`PublicSearchProvider.java`](../../services/event-alert-service/src/main/java/bank/alert/ingestion/PublicSearchProvider.java), [`TavilySearchProvider.java`](../../services/event-alert-service/src/main/java/bank/alert/ingestion/TavilySearchProvider.java)
- [Event-driven-alerts deployment status](../feature-plans/event-driven-alerts-deployment-status.md) — this session's full test log and handover notes

## Revision history

- 2026-10-22: proposed, handed to Azeez for the provider choice and implementation.
- 2026-10-23: accepted with Tavily as the named provider; request, response, endpoint and secret-management controls recorded.
- 2026-10-23: removed the inactive AgentCore runtime adapter and configuration; the historical spike remains as decision evidence.
- 2026-10-23: accepted Nova 2 Lite as the production classifier and Singapore as the Bedrock Runtime source Region; Tavily removes the former Tokyo search dependency.
