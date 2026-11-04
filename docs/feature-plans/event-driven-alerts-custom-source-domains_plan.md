# Agent-added search domains per company

Owner: Hayyun. Reviewer: Azeez (reply pending). Status: Planned. Last updated: 2026-11-04.
Parent: [event-driven alerts plan](event-driven-alerts_plan.md). Decision: [ADR-0079](../adr/ADR-0079-agent-added-search-domains-per-company.md) (Proposed).
Branch: `task/event-driven-alerts/custom-source-domains`, from `dev` at `0c0c7d2`. Merges into `feat/event-driven-alerts` only when Hayyun says so.

## Goal and scope

Let agents see, add and deactivate extra search domains for the companies their clients hold, from the Event Alerts page. Live at once, with guardrails and an audit trail. Not in scope: admin approval, global domains, checking publisher terms, a domain-quality score, and any change to the shared Reuters/CNBC/FT list.

## Decisions needed

| Decision | ADR | Status |
|---|---|---|
| Per-company, agent-added, live at once, guardrails | ADR-0079 | Proposed |

## Design

**Data.** Migration `V11__instrument_source_domain.sql`: `instrument_source_domain(id uuid pk, instrument_id fk, domain, added_by_subject, added_at, active, deactivated_by_subject, deactivated_at)`. A partial unique index on `(instrument_id, domain)` where `active` blocks duplicates.

**API** (`/api/alerts/search-sources`, already routed):
| Method and path | Who | Result |
|---|---|---|
| `GET /api/alerts/search-sources` | agent: companies they hold; admin: all active companies | companies with `official`, `shared`, `custom` lists and `canAdd` |
| `POST /api/alerts/search-sources/{instrumentId}/domains` body `{domain}` + `Idempotency-Key` | agent who holds the company | 201 with the entry; 400 bad format or blocked; 403 not held; 409 duplicate or cap |
| `DELETE /api/alerts/search-sources/{instrumentId}/domains/{id}` | agent who holds it, or admin | 204; 404 if not found or not allowed |

**Validation** in one `SourceDomainPolicy` class: normalise, hostname rules, blocklist, cap of 10, duplicate check against official, shared and custom (including subdomains).

**Worker.** `IngestionJob` passes active custom domains to `InstrumentSearchTerms`; `IssuerQueryBuilder` adds them to the approved list but not to the issuer-domain list, so the strict gate applies.

**Audit.** `alert.search-domain-added` and `alert.search-domain-deactivated` through the outbox in the same transaction, using the existing capacity check.

**UI.** A "Search sources" section on `/agent/alerts` and `/admin/alerts` (admin: list and deactivate only): one card per company showing official, shared and custom domains, an add field with inline errors, and a remove button with confirmation.

## Tasks

| ID | Task | Owner | Files | Depends on | Status |
|---|---|---|---|---|---|
| SD-01 | Migration, entity, repository | Hayyun | `db/migration/V11`, `domain/`, `repository/` | none | Planned |
| SD-02 | `SourceDomainPolicy` and service with audit | Hayyun | `service/`, `config/` | SD-01 | Planned |
| SD-03 | Controller, DTOs, OpenAPI | Hayyun | `api/`, `docs/api/openapi.yaml`, `docs/api/event-driven-alerts-contracts.md` | SD-02 | Planned |
| SD-04 | Worker uses custom domains | Hayyun | `ingestion/` | SD-01 | Planned |
| SD-05 | Frontend API client and Search sources section | Hayyun | `frontend/src/api`, `components`, `pages/EventAlerts.jsx` | SD-03 | Planned |
| SD-06 | Docs: source register, ADR index, AI usage log | Hayyun | `docs/` | all | Planned |

No other team's files are expected to change. Shared contract touched: the alerts section of `docs/api/openapi.yaml`.

## Tests

Policy unit tests (formats, blocklist, cap, duplicates, subdomains); Testcontainers tests for the service and repository (ownership, idempotency, audit event, re-add after deactivate); controller tests for roles and status codes; query-builder and ingestion-job tests for custom domains and the strict gate; OpenAPI route test; frontend component and API-client tests; lint and build.

## Gates

None new. Ingestion and AI stay controlled by `ingestion_enabled` and `ai_enabled`.

## Open items and risks

- Agents' identities are not shown on shared entries (only "you" or "another agent").
- Custom-domain search quality is unmeasured; terms of use are the agent's responsibility.
- Azeez's review of ADR-0079 is pending.
