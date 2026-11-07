# ADR-0088: Amazon Nova Pro (Asia-Pacific profile) for alert reports and agent drafts

- Status: Accepted
- Decision date/time: 2026-11-07 (Asia/Singapore)
- Decision owner: Hayyun (Feature 3, event-driven alerts)
- Drafted/assisted by AI: Yes
- Feature/branch: `task/event-driven-alerts/aws-test-fixes`
- Related issue/PR: pending
- Supersedes: in part ADR-0050 decision 3 (alert model) and ADR-0067 (the engagement draft model); the rest of both stands

## Context

The AWS test showed the alert worker's report drafts being refused (`INVALID_MODEL_SCHEMA`) with Nova 2 Lite.

## Decision

1. The event-alert worker uses `apac.amazon.nova-pro-v1:0` (foundation model `amazon.nova-pro-v1:0`), called from Singapore.
2. The agent-engagement stack's `bedrock_model_id` and `bedrock_foundation_model_id` defaults become the same Nova Pro pair.
3. The alert worker's IAM changes from a Region-wildcarded model ARN (needed by a global profile) to one ARN per Region in the new `bedrock_profile_regions` variable, as the engagement stack already does.
4. Unchanged: temperature 0, forced tool call, the 12,000-character input limit, the report shape rules, the Client Health stack (`client_health_bedrock_*`) and the benchmark gates.

## Consequences

- Nova Pro costs more per call than Nova Lite or Nova 2 Lite. Check the price before a large run.
- Before applying, confirm the model is enabled for the account and which Regions the `apac.` profile uses (`aws bedrock get-inference-profile`), and adjust `bedrock_profile_regions` if they differ. This was not checked from the repo.
- Whether Nova Pro stops the refused drafts is not yet known; the worker log's refusal lines will show.
