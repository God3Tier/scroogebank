# Event-driven alerts: deployment status and handover to Azeez

Written 2026-10-22 by Hayyun (with Claude Code). This is the practical "what to
do next" companion to [ADR-0050](../adr/ADR-0050-event-search-provider-after-scp-block.md).
Read the ADR for the reasoning; read this for the concrete steps.

## The one-sentence summary

Everything in the pipeline works — matching, real AI classification, scoring,
alert creation, audit trail, all proven with a real Bedrock model call against
real database rows. The only thing not working is the Tokyo search step, and
it is blocked by something outside this AWS account (an Organization-level
policy), not a bug in our code. [ADR-0050](../adr/ADR-0050-event-search-provider-after-scp-block.md)
proposes a direct search-API call as the way around it, with escalation as a
parallel backup. That provider choice and implementation is now yours.

## What's proven working (today, on the real AWS account)

Tested by inserting a fake article directly into the `source_result` table
(standing in for what a real Tokyo search result would look like), resetting
its `processed_at` to `NULL`, and running the real worker task against it.

| Step | Result |
|---|---|
| Deterministic matching (ADR-0032) | Found the fake article's instrument correctly |
| AI classification | **Real Bedrock call succeeded** — `REGULATORY`/`NEGATIVE`, confidence 0.90, model `global.amazon.nova-2-lite-v1:0` |
| `public_event` + classification audit | Persisted; `alert.classification-recorded` audit event delivered |
| Materiality scoring | Both synthetic clients scored 92.00 (`materiality-v1`), above the 60.00 threshold |
| Alert creation | **2 real `Alert` rows created**, one per client holding the instrument |
| Alert audit trail | `alert.generated` audit events delivered for both |

Query to reproduce (against the `alerts` database):

```sql
SELECT id, client_ref, instrument_id, score, rule_version, created_at FROM alert ORDER BY created_at DESC LIMIT 5;
SELECT event_type, status, created_at FROM audit_outbox WHERE event_type LIKE 'alert.%' ORDER BY created_at DESC LIMIT 5;
```

## What's NOT working, and why

**Tokyo AgentCore Gateway (`bedrock-agentcore:InvokeGateway`, `ap-northeast-1`)**
— explicit deny from an AWS Organization Service Control Policy
(`arn:aws:organizations::654654470350:policy/o-msh4r6icu6/service_control_policy/p-88ezx8iz`).
Account `654654470350` is the Organization's management account, not this
project's account (`727816232662`). This cannot be fixed from inside this
account; it needs someone with access to `654654470350` to change the policy.

**Checked for a workaround that stays inside AWS, found none:**
- A different region for AgentCore — not possible, the web search connector
  (`agentcore-web-search-1.2.0`) only exists in Tokyo.
- Amazon Bedrock's own native "Web Search" feature (launched August 2026,
  works for the already-pinned `openai.gpt-5.6-luna` model, via a separate
  `bedrock-mantle` endpoint) — tested directly, denied by **the same SCP**,
  this time on `bedrock-mantle:CreateInference`.

That SCP has now confirmed-blocked three unrelated things (the Gateway, this
Web Search feature, and even reading CloudWatch Logs via
`logs:DescribeLogGroups`/`FilterLogEvents`). It reads like a deliberate,
broad guardrail on newer Bedrock-adjacent services account-wide — not
something a different region or a different AWS feature will dodge.

**Separately: the AI model originally pinned in ADR-0010 (`openai.gpt-5.6-luna`)
also cannot be invoked** — Bedrock returns an `AccessDeniedException` about an
incomplete AWS Marketplace subscription. Confirmed not model-specific:
Anthropic's Claude Haiku 5.5 hit the identical error. Amazon's own Nova models
need no such subscription and work immediately. This is why the proven test
above used Nova instead of Luna — see "Code changes already made" below.

## Your decision: pick a search provider (ADR-0050)

ADR-0050 proposes calling a search/news API directly from the worker,
bypassing Bedrock entirely for search, as the primary path — with escalating
the SCP to whoever runs the AWS Organization as a secondary, parallel track
(pursue it, but don't block on it).

To implement the direct-API option:

1. **Pick a provider** (Tavily is the one discussed; Bing Search or a
   comparable news/web API would fit the same seam). Check its terms allow
   the use case and its pricing against ADR-0009's original bound (1,000
   searches/month, 4 grouped queries/run, 10 results/query).
2. **Implement `PublicSearchProvider`** — this is the exact interface
   [`AgentCoreSearchProvider`](../../services/event-alert-service/src/main/java/bank/alert/ingestion/AgentCoreSearchProvider.java)
   already implements, at
   [`PublicSearchProvider.java`](../../services/event-alert-service/src/main/java/bank/alert/ingestion/PublicSearchProvider.java).
   A new class (e.g. `TavilySearchProvider`) implementing the same
   `search(PublicSearchRequest): List<PublicSearchResult>` method, calling
   the provider's HTTPS API directly, is the whole change — nothing in
   `WorkerRunner`, `SourcePipeline`, or anything downstream needs to change.
3. **Keep the request bounded** exactly as ADR-0009 requires: public
   issuer/ticker/sector/event terms, inclusive UTC date range, capped result
   count, no CRM identifier/holding/exposure/note/agent/audit data.
4. **New secret**: the provider's API key goes in Secrets Manager, the same
   pattern as the alerts-db credential — never a plain environment variable.
5. **Terraform** (`infrastructure/terraform/stacks/alert-worker/main.tf`):
   drop `agentcore_gateway_arn` and the `InvokeTheSearchGateway` IAM
   statement (dead permission, since the Gateway won't be created), add the
   new secret to the task's `secret_arns`.
6. **Benchmark before going live**: real queries' result quality, latency,
   and actual billed cost, per ADR-0009's original ask — then check it
   against [the event-driven-alerts budget](../cost/event-driven-alerts-budget.md).
7. **Re-run the same proof above with a real search result** from the new
   provider (not a hand-written fixture) to confirm the full chain still
   holds with real content.

If escalation succeeds later, swapping back to `AgentCoreSearchProvider` is a
one-line change at the `PublicSearchProvider` wiring point — this isn't a
one-way door.

## Code changes already made this session (keep these)

- **`BedrockAiGatewayConfiguration.java`**: added `@Primary` on
  `bedrockAiModelGateway`. Fixes a real bug — `@ConditionalOnMissingBean` was
  unreliable here and crashed the app with a `NoUniqueBeanDefinitionException`
  the first time `ai_enabled=true` was ever tried.
- **`BedrockAiModelGateway.java`**: rewritten to request structured output
  via Bedrock's tool-use/function-calling mechanism instead of Converse's
  `outputConfig.textFormat=JSON_SCHEMA`. Needed because no model actually
  invokable in this account supports that output feature (only certain
  Marketplace-gated third-party models do) — tool-use works on Nova and
  produces the same JSON the classifier already validates. Keep this even
  after a real search provider and/or the Marketplace issue are resolved;
  it's a strictly more compatible way to talk to Bedrock.
- **`V6__seed_synthetic_portfolio.sql`** (new Flyway migration): the
  `synthetic_portfolio` table was never seeded for any client, so
  `holding_snapshot` inserts failed a foreign-key check the first time any
  run ever reached real alert creation (every earlier attempt failed before
  reaching that line, which is why this was never caught before). Seeds the
  two synthetic clients the holdings fixture already uses.
- **`V5__seed_real_test_instrument.sql`** (new Flyway migration): adds a real
  instrument (DBS Group Holdings, ticker D05, `SGX-D05`) to the instrument
  master, so there's a real, searchable name/ticker to test against instead
  of only the placeholder `SYN-SGX-001`.
- **`holdings-snapshot.v1.json`**: added `SGX-D05` positions for both
  synthetic clients, with `valuedAt` kept close to "now" — this fixture's
  freshness is recomputed live against the clock
  (`ValuationFreshnessPolicy`, FRESH ≤24h, STALE ≤72h, else EXPIRED), so a
  stale timestamp here silently makes every position `EXPIRED` and silently
  kills every alert without any error. If you ever get a `NO_ALERTS` outcome
  with no exceptions in the logs, check this first.
- **Terraform (`stacks/alert-worker/main.tf`)**: `local.bedrock_profile_id`/
  `bedrock_model_id` are **temporarily** pointed at
  `global.amazon.nova-2-lite-v1:0` instead of the real pinned
  `global.openai.gpt-5.6-luna`, with a matching `ALERT_AI_MODEL` env var
  override, both marked `TEMPORARY (2026-10-22, Hayyun)` in the code.
  **Revert these once a real decision is made** about the Marketplace issue
  (escalate it, or accept Nova as the real production model — that's a
  separate decision from the search provider one, see ADR-0010).
- Image tags used today: `8b15e1e-fix2` → `8b15e1e-fix3` (Nova tool-use
  swap) → `8b15e1e-fix4` (fresh fixture timestamps). `prod.tfvars` in both
  `stacks/crm` and `stacks/alert-worker` point at `8b15e1e-fix4`.

## Mistakes made this session (so you don't repeat them)

- PowerShell mangles `-flag=value` style arguments unless the whole thing is
  quoted as one string: `terraform apply "-var-file=env/prod.tfvars" "-var=running=true"`.
  Space-separated `-var running=true` and unquoted `-var-file=...` both broke
  with "Too many command line arguments."
- `aws ecs run-task`/`describe-tasks` without an explicit region picked up
  the wrong default region when only raw credential env vars were exported
  (no profile). Always `export AWS_DEFAULT_REGION=ap-southeast-1` alongside
  `eval "$(aws configure export-credentials --profile crm --format env)"`.
- The CRM stack's `prod.tfvars` has `running = false` as its committed
  default (so it costs nothing when nobody's testing) — the live environment
  was actually running because of a `-var running=true` override from an
  earlier session that never got saved to the file. Applying without that
  override shuts the whole environment down. Always pass it explicitly when
  you mean to keep testing.
- ECR image tags are immutable — plan the tag name once per rebuild, don't
  reuse one you've already pushed with different content.

## Cost this session

Small. A handful of Fargate task starts (a few seconds to a couple of
minutes each), a handful of very cheap Nova Lite tool-use calls (sub-cent
each), normal RDS/NAT/ALB/CloudFront run time for the hours the stack was up.
Nothing from the blocked calls (AgentCore, Mantle) — AWS doesn't bill for
calls rejected by a permission/policy check before they reach a model.

## How to pick this back up

1. Re-export AWS credentials: `aws configure export-credentials --profile crm --format powershell | Invoke-Expression` (MFA), then `export AWS_DEFAULT_REGION=ap-southeast-1`.
2. If the environment was torn down (see teardown note below), redeploy
   bootstrap → `stacks/crm` (`-var running=true`) → `stacks/alert-worker`, in
   that order, using image tag `8b15e1e-fix4` (already pushed to ECR, no
   rebuild needed unless you change the code).
3. Read [ADR-0050](../adr/ADR-0050-event-search-provider-after-scp-block.md), pick a provider, implement `PublicSearchProvider`.
4. Re-run the proof: insert a fake `source_result` row (or, once your
   provider works, just let ingestion run for real), reset `processed_at` to
   `NULL` if reusing a row, run the worker, check `alert` and `audit_outbox`.

## Teardown

The environment was torn down at the end of this session (bootstrap, crm,
and alert-worker stacks all destroyed) to stop ongoing cost. See git history
or ask Hayyun for the exact commands used; the state bucket name
(`crm-tfstate-727816232662`) is deterministic, so bootstrap can always be
recreated identically.
