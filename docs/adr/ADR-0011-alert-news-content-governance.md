# ADR-0011: Alert news-source and content governance

- Status: Accepted (2026-11-01; accepted for the team by Neo Jia Wen)
- Decision date/time: 2026-09-27 01:54:06 +08:00 (Asia/Singapore)
- Human owner/decision-maker: Azeez
- Required reviewer: Hayyun
- AI-assisted draft: Yes — Codex
- Related branch: `task/event-driven-alerts/contracts`
- Supersedes: None

## Context

Search results may be copyrighted, corrected, removed, or malicious. The feature
must retain citations without bulk-copying third-party content.

## Decision

The initial allowlist contains regulators, exchanges, and reviewed official
issuer investor-relations sites. Each domain has an owner, review date, purpose,
and terms status. A commercial publisher cannot be added until its terms and
storage/display rights are recorded.

Persist only canonical URL, title, publication date, source domain, retrieval
time, connector result ID where supplied, content hash, and the minimum excerpt
needed for traceability. Never store full articles or build a competing index.
Every surfaced briefing retains clickable source citations. Untrusted markup and
active content are never rendered.

Metadata, excerpts, prompts, and briefs expire after 90 days. A takedown or source
correction marks affected evidence unavailable without deleting immutable audit
facts; derived alerts display the limitation and are not silently rewritten.

## Consequences

Historical detail is intentionally limited and some evidence may become
unavailable. Provenance and takedown behavior are explicit.

## Validation

Allowlist, canonicalization, duplicate URL, sanitization, citation, retention,
correction, and takedown tests are required. A source register review is required
before each staging/demo data refresh.
