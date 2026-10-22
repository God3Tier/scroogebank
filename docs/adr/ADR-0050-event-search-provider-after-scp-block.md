# ADR-0050: Event search provider, after the Tokyo Gateway and Bedrock Web Search are both blocked

- Status: Proposed
- Decision date/time: 2026-10-22 (Asia/Singapore)
- Decision owner(s): Azeez (owner of EDA-D1-02; this ADR hands the open decision to him)
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
- Prefer a reversible choice: if the SCP is ever relaxed, swapping back to the Tokyo Gateway should cost one class, not a redesign.
- Do not block the already-proven classification → scoring → alert → audit chain on this decision; it does not depend on which search provider feeds it.

## Options Considered

- **Escalate to whoever controls the AWS Organization's management account** to exempt this account from the relevant SCP statements. The only option that keeps the architecture exactly as ADR-0009/ADR-0031/ADR-0048 describe it. Available as a parallel, secondary path — pursue it, but do not block on it, since its timeline and outcome are outside the team's control.
- **Amazon Bedrock's native Web Search (Mantle).** Ruled out: confirmed blocked by the same SCP as the Gateway.
- **A different AWS region for AgentCore.** Ruled out: the web search connector only exists in Tokyo.
- **Call a search/news API directly from the worker, bypassing Bedrock entirely (chosen).** No `bedrock-*` action is involved, so the SCP cannot apply. Implemented behind the existing `PublicSearchProvider` interface ([`PublicSearchProvider.java`](../../services/event-alert-service/src/main/java/bank/alert/ingestion/PublicSearchProvider.java)), the same seam `AgentCoreSearchProvider` already implements, so nothing downstream of ingestion changes.
- **Leave Tokyo wired up and document the gap, do nothing else.** Rejected as the only action: it is a safe fallback, not a path to a working feature, and the team has over three weeks left to ship this.

## Decision

1. **Add a new `PublicSearchProvider` implementation** (suggested: `TavilySearchProvider`, provider name TBD by Azeez — Tavily, Bing Search, or a comparable news/web search API all fit the same seam) that calls that provider's HTTPS API directly from the worker process, inside the existing VPC egress path (NAT gateway), with no AgentCore or Bedrock Mantle involved.
2. **The request stays bounded exactly as ADR-0009 already requires:** public issuer/ticker/sector/event terms, an inclusive UTC date range, a capped result count, and (if the provider supports it) a domain allowlist mirroring `mas.gov.sg`, `sgx.com`, and reviewed issuer investor-relations domains. No CRM identifier, holding, exposure, note, agent, or audit data is ever sent.
3. **Classification keeps using Bedrock, on Amazon's own Nova family instead of the Marketplace-gated OpenAI model**, until the Marketplace subscription issue is separately resolved (that is a different blocker from the search question and can be escalated or re-tried independently). `BedrockAiModelGateway`'s tool-use rewrite (this session) is required for this and should not be reverted.
4. **The provider's API key is a new secret** (AWS Secrets Manager, following the same pattern as the alerts-db credential), never an environment variable literal. Terraform for `stacks/alert-worker` drops the `agentcore_gateway_arn` variable and its IAM policy (`InvokeTheSearchGateway`) and adds the new provider's secret ARN to the task's `secret_arns`.
5. **Escalating the SCP stays open as a parallel, lower-priority track.** If it succeeds, swapping back to `AgentCoreSearchProvider` is a one-line change at the `PublicSearchProvider` wiring point — this decision does not have to be revisited, only re-made, if that happens.
6. **Azeez owns the provider choice, the new ADR revision (or a follow-up ADR) naming it, the Terraform change, and the benchmark** ADR-0009 already asked for ("a financial-news API requires benchmark evidence"): result quality, latency, and actual cost against real queries before this goes into the budget in [the event-driven-alerts budget doc](../cost/event-driven-alerts-budget.md).

## Rationale

This keeps every already-proven part of the pipeline untouched — matching, classification, scoring, alert generation, and the audit trail all already work, with a real model call, against a real database. The only change is at the one seam the codebase already isolated for exactly this kind of swap (`PublicSearchProvider`), which is why `AgentCoreSearchProvider` and a new HTTP-based provider can coexist without touching `WorkerRunner`, `SourcePipeline`, or anything downstream of ingestion.

## Consequences

### Positive

- Unblocks the feature without waiting on an Organization-level policy change.
- Touches one interface implementation and one Terraform stack; no change to the proven classification/scoring/alert/audit code.
- Reversible: escalation can still happen later and swap the provider back with no further redesign.

### Negative / Trade-offs

- A new third-party vendor and a new secret to manage, cost, and monitor — outside what ADR-0009 originally scoped.
- Loses whatever curation AgentCore's Tokyo connector would have enforced (the administrator domain allowlist mechanism); the new provider must enforce (or be configured to enforce) the same bound in its own request, not assumed from AgentCore's behavior.
- The AI classification model (Nova, not the originally pinned Luna) is also a deviation from ADR-0010 until the Marketplace issue is separately resolved; this ADR does not resolve that, it only unblocks testing around it.

## Security & Privacy Impact

- No change to what leaves the service: still public search terms only, per ADR-0009/ADR-0031's existing boundary. The new provider's request payload must be reviewed against that boundary before launch.
- A new secret (the provider's API key) in Secrets Manager, scoped to the worker task role only.
- Removes the `bedrock-agentcore:InvokeGateway` IAM statement entirely from the worker's task role (dead permission, since the Gateway is not being created).

## Cost Impact

Depends on the provider Azeez chooses and its pricing (typically a per-query or monthly-tier model for search APIs). To be benchmarked against ADR-0009's 1,000 searches/month and four grouped queries/run bound, and checked against [the event-driven-alerts budget](../cost/event-driven-alerts-budget.md) and the `crm-bedrock`/Bedrock-adjacent budgets in `bootstrap` before this goes live.

## Operational Impact

- Deploy: one new Secrets Manager secret, one Terraform change to `stacks/alert-worker` (drop `agentcore_gateway_arn`/its IAM policy, add the new secret), no change to `stacks/crm`.
- Runbook: the event-alert operations runbook's ingestion section needs a short addition once the provider is named.
- Nothing about teardown changes: the worker stack still owns no data and destroys independently of `stacks/crm`.

## Validation

- Unit/contract test the new `PublicSearchProvider` implementation the same way `AgentCoreSearchProvider` is tested: bounded request, response size cap, provider-error mapping to `SearchProviderException`.
- Re-run the exact mock-data path this session used (insert a `source_result` row, reset `processed_at`, run the worker once) once with a *real* search result from the new provider, to confirm the same classification → scoring → alert → audit chain holds end to end with real search content, not just a hand-written fixture.
- Benchmark result quality, latency, and billed cost against real queries before raising `ingestion_enabled` in production, per ADR-0009's original ask.

## References

- [ADR-0009](ADR-0009-agentcore-search-region-and-fallback.md), [ADR-0010](ADR-0010-alert-ai-provider-and-safety.md), [ADR-0031](ADR-0031-xf1-alert-processing-search-region-boundary.md), [ADR-0032](ADR-0032-xf1-instrument-master-deterministic-matching.md), [ADR-0048](ADR-0048-alert-worker-terraform-stack.md), [ADR-0049](ADR-0049-alert-source-to-alert-pipeline.md)
- [`PublicSearchProvider.java`](../../services/event-alert-service/src/main/java/bank/alert/ingestion/PublicSearchProvider.java), [`AgentCoreSearchProvider.java`](../../services/event-alert-service/src/main/java/bank/alert/ingestion/AgentCoreSearchProvider.java)
- [Event-driven-alerts deployment status](../feature-plans/event-driven-alerts-deployment-status.md) — this session's full test log and handover notes

## Revision history

- 2026-10-22: proposed, handed to Azeez for the provider choice and implementation.
