# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Stack

Delegated: extend the active `origin/task/frontend-building` React 19 and Vite
JavaScript application. Preserve its JavaScript stack rather than introduce a
parallel TypeScript migration while feature branches are still converging.

## Users

- Relationship agents use the CRM throughout the workday to review assigned
  clients, investigate event-driven alerts, and record a human decision.
- Administrators oversee users and may read alerts globally, but cannot make an
  agent decision.

## Product Purpose

Scrooge Global Bank CRM brings client servicing and evidence-backed market-event
alerts into one operational workspace. The alert workflow succeeds when an agent
can identify why an assigned client is affected, verify the public evidence and
exposure, and record a deliberate next step without triggering client contact.

## Positioning

Alerts connect public market evidence to deterministic synthetic portfolio
exposure and a versioned score before any AI briefing is shown. The system is
decision support; the assigned agent remains accountable for the outcome.

## Operating Context

The primary surface is a desktop operations dashboard used repeatedly during the
working day, with responsive access for narrower screens. Agents scan a bounded
queue, open one alert at a time, verify citations and explanation factors, then
record `INFORM_CLIENT`, `MONITOR`, or `DISMISS` and optional quality feedback.

## Capabilities and Constraints

- Agents see only alerts for their assigned synthetic clients; inaccessible
  records return `404` to prevent enumeration.
- Admins have global read-only alert visibility.
- Alert list, detail, decision, and feedback contracts are defined in
  `docs/api/openapi.yaml`.
- The interface must expose loading, empty, failure, stale-version, and
  audit-unavailable states.
- `INFORM_CLIENT` records intent only and never contacts a client.
- Only synthetic data is used. Client/private fields must not enter public-search
  or model requests.
- Local frontend work does not require or contact AWS.

## Brand Commitments

The product name is Scrooge Global Bank CRM. Copy is precise, calm, and explicit
about AI-generated decision support, uncertainty, and the effect of actions.
No approved logo or mature visual identity is present in the active frontend.

## Evidence on Hand

- Approved MVP charter: `docs/adr/ADR-0004-event-driven-alerts-mvp-charter.md`.
- Proposed API and authorization contract: `docs/adr/ADR-0012-alert-api-event-and-authorization-contracts.md`.
- OpenAPI contract and synthetic examples: `docs/api/openapi.yaml` and
  `tests/fixtures/event-driven-alerts/`.
- No customer research, production screenshots, testimonials, or real client
  data are available and none should be fabricated.

## Product Principles

- Evidence before action.
- Human decisions remain explicit and reversible only through supported flows.
- Authorization boundaries are visible in behavior, not delegated to UI state.
- Dense information stays scannable through hierarchy and progressive detail.
- Failure states state what happened and the safe recovery action.

## Accessibility & Inclusion

Keyboard access, visible focus, semantic landmarks, non-color status cues,
readable contrast, reduced-motion support, and responsive reflow are required.
