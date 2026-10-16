# Event alert dashboard

## Purpose

An assigned relationship agent scans active alerts, opens one investigation,
verifies why it matters, and records a human decision. An administrator may use
the same read surface without decision controls.

## Structure

```text
┌ product rail ──────────────────────────────────────────────────────────────┐
│ Event alerts          role / synthetic-data marker             profile   │
├ queue controls ────────┬ investigation workspace ─────────────────────────┤
│ status  score  refresh │ headline · state · score version                 │
│                        │ exposure / valuation freshness                    │
│ ○ time + alert row     │ explanation ledger                               │
│ │ time + alert row     │ AI briefing + uncertainty                        │
│ ● selected alert       │ evidence links                                   │
│ │ time + alert row     │ decision bar + feedback                          │
│ load more              │                                                   │
└────────────────────────┴───────────────────────────────────────────────────┘
```

## Required states

- Initial loading and detail loading.
- No visible alerts, and filters with no matches.
- Recoverable list/detail failure.
- Other-agent/not-found detail without enumeration clues.
- Stale expected version conflict with refresh action.
- Audit persistence unavailable with retry guidance.
- Successful decision and idempotent replay.
- Read-only administrator state.

## Direction contract

Build the operations-ledger visual system in `DESIGN.md`. The signal trace is the
single expressive device. Preserve dense scanability, public evidence visibility,
plain-language action consequences, keyboard navigation, and responsive reflow.
Do not use a metric-card dashboard, decorative finance art, or autonomous-advice
language.
