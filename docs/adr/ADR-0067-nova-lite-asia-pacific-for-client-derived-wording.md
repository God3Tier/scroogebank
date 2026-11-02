# ADR-0067: Nova Lite through its Asia-Pacific profile for Client Health explanations and follow-up drafts

- Status: Accepted
- Decision date/time: 2026-11-01 16:57 +08:00 (Asia/Singapore)
- Decision owner(s): Garvit (Client Health, ADR-0034); Neo Jia Wen (agent engagement, ADR-0054)
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/client-health-monitor/nova-lite-model`
- Related issue/PR: TBD
- Supersedes: [ADR-0034](ADR-0034-xf3-health-monitor-rules-and-bedrock-boundary.md) in part (the Bedrock region choice and the "in Singapore" part of decision 4); [ADR-0054](ADR-0054-ai-follow-up-drafts-and-holiday-data.md) in part (decision 3's model, and the guidance to keep the kill switch off outside demo runs)
- Superseded by: N/A

## Context

Two features send wording requests built from client records to Bedrock:

- **Client Health (F-06)** words verified relationship-health facts ([ADR-0034](ADR-0034-xf3-health-monitor-rules-and-bedrock-boundary.md) decision 4). It has never had a model: `CLIENT_HEALTH_AI_MODEL_ID` is unset and both tasks run with AI off, so every explanation is a template. ADR-0034 chose Bedrock in Singapore and rejected cross-region inference.
- **Agent engagement (F-05)** drafts follow-up emails from placeholders and public facts with Amazon Nova Micro through its Asia-Pacific profile ([ADR-0054](ADR-0054-ai-follow-up-drafts-and-holiday-data.md) decision 3).

The alerts feature uses `global.amazon.nova-2-lite-v1:0`. That is allowed only because its model sees public data ([ADR-0050](ADR-0050-event-search-provider-after-scp-block.md) decision 3).

The project has budget for a larger model than Nova Micro, and one model for both client-derived features is simpler to explain, secure and cost. Checks in the project account on 2026-11-01, with the `crm` profile from ap-southeast-1:

- **Third-party models are not usable.** `global.openai.gpt-5.6-luna` fails with `AccessDeniedException`: the role is "not authorized to perform the required AWS Marketplace actions (aws-marketplace:ViewSubscriptions, aws-marketplace:Subscribe)… Your AWS Marketplace subscription for this model cannot be completed at this time." Claude Sonnet 5 (in-region, `anthropic.claude-sonnet-5`) and Claude Haiku 5.5 (`global.anthropic.claude-haiku-5-5`) each answered the first call. Every later call, four in a row a minute later, failed with the same Marketplace error. A single successful call therefore does not prove a third-party model is usable here.
- **No current Nova model runs in-region.** In Singapore, Nova is offered only through inference profiles. Nova 2 Lite has only a global profile.
- **Nova Lite has an Asia-Pacific profile.** `amazon.nova-lite-v1:0` is `ACTIVE` with no end-of-life date. `apac.amazon.nova-lite-v1:0` is an active, system-defined profile that routes to the same six regions as Nova Micro: ap-southeast-1, ap-southeast-2, ap-northeast-1, ap-northeast-2, ap-northeast-3 and ap-south-1.
- **Nova does not support Converse structured output.** A call with `outputConfig` returns `ValidationException: This model doesn't support the outputConfig field`. Client Health's provider used `outputConfig`, so with any Nova model every call would have fallen back to a template.
- **Tool names cannot contain hyphens.** With Client Health's schema forced as a tool, Nova Lite returned `ModelErrorException: Model produced invalid sequence as part of ToolUse` whenever the tool name contained hyphens (`relationship-health-explanation`). With an underscore name (`relationship_health_explanation`), three of three calls returned schema-shaped tool input with no numbers, and `const` in the schema was accepted.
- **Plain-text drafts work.** With F-05's real system prompt, Nova Lite returned the `{"subject","body"}` object wrapped in a Markdown fence, which `DraftSafetyScreen` already removes.

## Decision Drivers

- No client personal data reaches the model, unchanged from ADR-0034 decision 4 and ADR-0054 decision 2.
- The model must be invokable in this account, and still supported at the demo.
- Requests stay in Asia-Pacific; a global profile is never used for client-derived input.
- One model and one IAM pattern for both client-derived features.
- A template fallback and a kill switch keep each feature working without the model.

## Options Considered

- **Nova Lite through its Asia-Pacific profile for both features (chosen).**
  - Pros: invokable today, active, and stays in Asia-Pacific. More capable than Micro, and the same six regions, so the IAM pattern is unchanged.
  - Cons: processing may happen outside Singapore, which reverses ADR-0034's region choice. Older than Nova 2 Lite.
- **Claude Sonnet 5 in-region.**
  - Pros: nothing leaves Singapore, so ADR-0034 would stand unchanged.
  - Cons: blocked by the Marketplace subscription after the first call.
- **Nova 2 Lite through its global profile, matching alerts.**
  - Pros: one model across all three features.
  - Cons: requests may be processed in any commercial region, which is against ADR-0034 and ADR-0054 for client-derived input. engagement-service refuses `global.` IDs at startup.
- **Keep Nova Micro for F-05 and add it to F-06.**
  - Pros: cheapest.
  - Cons: the budget allows a stronger writer, and the model choice would not change the region question.

## Decision

1. **Model.** Client Health explanations and follow-up drafts both use **Amazon Nova Lite** through `apac.amazon.nova-lite-v1:0`. The Bedrock Runtime endpoint is always ap-southeast-1. Bedrock may run a request in another region of the profile, never outside Asia-Pacific. Global profiles stay forbidden, and both Terraform stacks refuse any ID that does not start with `apac.`. Any other model needs a new decision.
2. **Client Health region.** This supersedes ADR-0034's "Bedrock region: Singapore" choice and the words "in Singapore" in its decision 4. What may be sent is unchanged: signal types, percentage or banded changes, counts, window lengths, cadence and days since an expected credit. What is never sent is also unchanged.
3. **Client Health structured output.** `BedrockExplanationProvider` asks for its JSON as one forced tool, `relationship_health_explanation`, whose input schema is the existing output schema. It no longer uses `outputConfig`. The tool input goes through `ExplanationSafetyValidator` as before. A reply without the tool is invalid output and falls back to a template without a retry.
4. **Client Health scope.** Only the daily ANALYSIS task calls Bedrock; the API service never does. The analysis task role gets `bedrock:InvokeModel` on the profile and on `amazon.nova-lite-v1:0` in each of the six regions, listed rather than a wildcard. The API service gets no Bedrock permission and keeps `CLIENT_HEALTH_AI_ENABLED=false`.
5. **On in prod.** Each stack's kill-switch variable defaults to off: `client_health_ai_enabled` in the CRM stack and `ai_enabled` in agent-engagement. Prod sets both on. Staging and plan tests keep them off. This replaces ADR-0054's guidance that the switch stays off outside demo runs. The template fallback, caps and validators are unchanged.
6. **Engagement.** ADR-0054 decision 3's model becomes Nova Lite. Its "Nova Lite replaces Micro if Micro fails the evaluation" clause no longer applies. The rest of decision 3 is unchanged: temperature, token limit, timeout and the versioned prompt.

## Rationale

Nova Lite is the strongest model this account can invoke reliably without leaving Asia-Pacific. Using it for both client-derived features gives one model, one region rule and one IAM pattern. The alerts feature stays on Nova 2 Lite global, because its model sees only public data and the global profile is the only way to reach Nova 2 Lite here. Its benchmark and live evidence were taken on that model.

**Why the Asia-Pacific profile is now acceptable for Client Health:** ADR-0034 rejected cross-region inference because it offered "no benefit". It now has one: no model this account can invoke runs in-region. What travels is still only banded facts with no identifiers, so the exposure is the same as the one ADR-0054 already accepted for drafts.

## Consequences

### Positive

- Client Health can produce AI wording for the first time.
- Both client-derived features share one model, one region rule and one IAM pattern.
- Drafts come from a stronger model than Nova Micro.

### Negative / Trade-offs

- Client Health facts may be processed in Sydney, Tokyo, Seoul, Osaka or Mumbai as well as Singapore. This must be stated wherever ADR-0034's in-region boundary was described.
- Nova Lite costs more per token than Nova Micro.
- Prod spends on Bedrock whenever a stack runs, not only during demo runs.
- A different model from the alerts feature, so the project uses two Nova generations.

## Security & Privacy Impact

- Data rules are unchanged: ADR-0034 decision 4 for Client Health and ADR-0054 decision 2 for drafts. The existing tests that check captured requests still apply.
- No request uses a global profile. Both stacks validate the ID, and engagement-service also refuses `global.` at startup.
- Each task role may invoke only the configured profile and its model in the six listed regions. A region AWS adds later is refused until reviewed.
- Client Health's Bedrock permission is on the analysis task only.

## Cost Impact

Nova Lite is priced above Nova Micro. AWS charges cross-region inference at the source region's on-demand price. The pricing API is denied by the organization's service control policy, so check the Bedrock pricing page for Singapore.

- A Client Health explanation uses about 660 input and under 100 output tokens (measured 2026-11-01). The daily run creates at most 20 new tasks per agent.
- A draft uses about 700 input and at most 600 output tokens, capped by the daily draft cap and the monthly token quota (ADR-0054 decision 7).
- At project volume both stay in cents to a few dollars a month. The existing Bedrock budget alert covers them ([ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md)).

## Operational Impact

- **Configuration:**
  - CRM stack: `client_health_ai_enabled`, `client_health_bedrock_model_id`, `client_health_bedrock_foundation_model_id` and `client_health_bedrock_profile_regions`, which set `CLIENT_HEALTH_AI_ENABLED` and `CLIENT_HEALTH_AI_MODEL_ID` on the analysis task.
  - Agent-engagement stack: `ai_enabled`, `bedrock_model_id`, `bedrock_foundation_model_id` and `bedrock_profile_regions`.
- **Turning it off:** set the kill switch to `false` in `env/prod.tfvars` and apply. Every explanation or draft is then a template.
- **Before the demo:** re-run `aws bedrock get-inference-profile --region ap-southeast-1 --inference-profile-identifier apac.amazon.nova-lite-v1:0`. Update this record and both IAM region lists if the destinations have changed.
- **Metrics:** unchanged. Client Health's `client_health_ai_validation_rejected_total` and `client_health_ai_provider_failures_total` feed the AI-safety alarm. Engagement records drafts by source and reason.

## Validation

- Unit tests: the Client Health provider sends a forced tool and no `outputConfig`. Tool input that fails the validator, or a reply without the tool, falls back to a template without a retry.
- Terraform tests in both stacks: the model ID, the exact `bedrock:InvokeModel` resources, that a `global.` ID is refused, and, for the CRM stack, that the switch affects only the analysis task.
- Live checks (2026-11-01, recorded in Context): the profile, lifecycle, tool-use and plain-text draft calls.
- Still to run before the demo: ADR-0054's evaluation, at least 24 cases with a 90% pass gate, against Nova Lite. Also the Client Health labelled synthetic scenarios with AI on, checking `client_health_ai_validation_rejected_total`.

## References

- Amazon Bedrock User Guide: the Converse API, tool use, cross-region inference and inference profiles
- [ADR-0010](ADR-0010-alert-ai-provider-and-safety.md), [ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md), [ADR-0034](ADR-0034-xf3-health-monitor-rules-and-bedrock-boundary.md), [ADR-0050](ADR-0050-event-search-provider-after-scp-block.md), [ADR-0054](ADR-0054-ai-follow-up-drafts-and-holiday-data.md)
- [Client Health plan](../feature-plans/client-health-monitor_plan.md), [agent engagement plan](../feature-plans/agent-engagement_plan.md)

## Revision history

- 2026-11-01 16:57 +08:00: proposed and accepted. Garvit approved setting the Client Health model, as relayed by Neo Jia Wen. Neo Jia Wen decided the engagement change and that both features are on in prod. Garvit should confirm the Client Health "on in prod" part (decision 5) in review.
