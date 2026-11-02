# ADR-0074: X-factor classification: Agent Engagement is x-factor 2; the business dashboard is the additional feature

- Status: Accepted (2026-11-02; decided by Neo Jia Wen)
- Decision date/time: 2026-11-02 22:00 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/infrastructure/load-and-zap-tests`
- Related issue/PR: TBD
- Supersedes: the x-factor numbering in ADR-0017, ADR-0033 and the architecture docs (wording only)
- Superseded by: N/A

## Context

The project requires three core features and three x-factor (innovation) features. Until now the docs counted:
- **x-factors:** market-news alerts (x-factor 1, Feature 4), the business dashboard (x-factor 2, Feature 5) and the Client Relationship Health Monitor (x-factor 3, Feature 6);
- **additional innovation:** Agent Engagement (Feature 7).

Agent Engagement brings meetings, reminders and reviewed AI follow-up e-mails into the CRM, and links to the other two x-factors (a check-in from a health task, a follow-up from an alert). The business dashboard summarises data the core features already hold.

## Decision Drivers

- The x-factors should be the features that add the most new value for agents and the bank.
- One numbering everywhere: report, architecture docs, diagrams and ADRs.

## Options Considered

### Option A: keep the dashboard as x-factor 2

- Pros: no document changes.
- Cons: counts a summary view as an innovation ahead of a feature that changes how agents work with clients.

### Option B: Agent Engagement as x-factor 2, the dashboard as the additional feature (chosen)

- Pros: the three x-factors are the three features that create new work for agents (alerts, follow-ups, health tasks) and connect to each other.
- Cons: every document's numbering changes once.

## Decision

1. The x-factors are:
   - **x-factor 1 (Feature 4):** event-driven market-news alerts;
   - **x-factor 2 (Feature 5):** Agent Engagement;
   - **x-factor 3 (Feature 6):** the Client Relationship Health Monitor.
2. **The business dashboard is the additional feature (Feature 7).**
3. Use-case IDs follow the feature numbers: F-05 is Agent Engagement and F-07 the dashboard.
4. File names keep their old tags (for example `ADR-0033-xf2-dashboard-...`), so links do not break; their text uses the new numbering.

## Rationale

The classification follows each feature's contribution. Nothing in the design, code or infrastructure changes.

## Consequences

### Positive

- The report, the architecture docs and the diagrams agree.

### Negative / Trade-offs

- Older branches, commits and file names still say x-factor 2 for the dashboard.

## Security & Privacy Impact

None.

## Cost Impact

None.

## Operational Impact

None.

## Validation

`grep -ri "x-factor 2" docs` finds only Agent Engagement, or this ADR's history.

## References

- [x-factor features](../architecture/x-factors.md), [ADR-0017](ADR-0017-microservices-per-business-area.md), [ADR-0033](ADR-0033-xf2-dashboard-topology-cache-audit.md), [ADR-0051](ADR-0051-engagement-service-and-mail-dispatcher.md)
