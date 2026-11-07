# ADR-0003: Maintain a one-line ADR index

- Status: Accepted
- Decision date/time: 2026-09-24 04:05:20 +08:00 (Asia/Singapore)
- Human owner/decision-maker: Azeez
- AI-assisted draft: Yes — Codex
- Related branch: `feat/git-workflow-policy`
- Related issue/PR: Not yet assigned
- Supersedes: ADR-0002 decision item 2 only to add `docs/adr/README.md` to the direct-governance allowlist

## Context

Reading every full ADR at the start of every task consumes time and AI context even when most decisions are unrelated. Agents still need a reliable way to discover which decisions apply without treating abbreviated text as the authoritative decision.

## Decision

1. `docs/adr/README.md` is the mandatory ADR index.
2. Every ADR must have exactly one linked, single-sentence summary entry containing enough information to determine likely relevance.
3. Humans and agents scan the index first, then read every applicable ADR in full. The summary never replaces the full ADR.
4. When an ADR is added, superseded, deprecated, or changes status, its index entry must be added or updated in the same change.
5. Historical entries remain in the index and identify supersession rather than being deleted.
6. The ADR index is included in ADR-0002's allowlist for qualifying direct-to-`dev` Git/agent-governance updates.

## Consequences

### Positive

- Agents can route themselves to relevant decisions with less context usage.
- The team gains a concise decision catalogue.
- Full decision reasoning remains authoritative and available.

### Trade-offs and risks

- Each ADR change requires maintaining one additional line.
- An unclear or stale summary could hide relevance, so the index must change alongside the ADR.
- Agents must not rely on the summary alone when an ADR may apply.

## Alternatives considered

### Read every ADR in full for every task

Rejected because it scales poorly and spends human attention and AI context on unrelated decisions.

### Use summaries as a substitute for full ADRs

Rejected because a one-line entry cannot preserve complete rationale, constraints, consequences, and supersession details.
