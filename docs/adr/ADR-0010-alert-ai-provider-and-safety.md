# ADR-0010: Alert AI provider, structured output, and safety

- Status: Accepted (2026-11-01; accepted for the team by Neo Jia Wen)
- Decision date/time: 2026-09-27 01:54:06 +08:00 (Asia/Singapore)
- Human owner/decision-maker: Azeez
- Required reviewer: Hayyun
- AI-assisted draft: Yes — Codex
- Related branch: `task/event-driven-alerts/contracts`
- Supersedes: None
- Superseded by: [ADR-0050](ADR-0050-event-search-provider-after-scp-block.md) in part (decision 3: the model is `global.amazon.nova-2-lite-v1:0` from the Singapore source Region, because Marketplace blocks Luna in the project account, and structured output comes through a forced tool; the public-data-only adapter, abstention and safety rules are unchanged)

## Context

Feature 4 requires AI for public-event interpretation and briefing, while all
identity, authorization, matching, exposure, scoring, and workflow decisions must
remain deterministic.

## Decision

Use GPT-5.6 Luna through a provider-independent adapter and global cross-region
inference. Prompts contain only bounded public result metadata/snippets and a
versioned instruction/schema. They never contain client, account, holding,
exposure, internal-note, agent, or audit fields.

Classification output must match a closed JSON schema containing event type,
affected public instruments, direction, confidence, evidence references, and
abstention reason. Brief output must cite supplied sources, distinguish facts from
uncertainty, avoid advice, and carry an `AI-generated` label. Inputs are treated
as untrusted quoted evidence; instructions found in source content are ignored.

The adapter caps input, output, retries, concurrency, and calls per article. A
timeout, refusal, low confidence, missing citation, or invalid schema produces an
abstention. Deterministic rules may retain the event for review but cannot invent
an AI result. Model invocation has an independent kill switch.

## Consequences

Global inference may process public content outside Singapore. Private CRM data
never enters the model, and users retain final judgment. Model changes require a
fixture benchmark and recorded prompt/model version.

## Validation

Tests cover prompt injection, oversized/malformed content, invalid JSON, missing
citations, timeouts, provider errors, abstention, token caps, and private-field
rejection. Benchmark thresholds must be recorded before enabling Luna.
