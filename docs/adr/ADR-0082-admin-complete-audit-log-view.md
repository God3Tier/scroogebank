# ADR-0082: Administrators view the complete audit trail through user-service

- Status: Proposed
- Decision date/time: 2026-11-07 (Asia/Singapore)
- Decision owner(s): Joseph (confirmation required before merge)
- Drafted/assisted by AI: Yes; OpenAI Codex
- Feature/branch: `task/frontend/adminfix`
- Related issue/PR: TBD
- Supersedes: ADR-0021 decision 2 only for the dedicated administrator audit-log page; the activity feed remains changes-only

## Context

`GET /api/activities` intentionally returns successful changes only. Transaction-service records public transaction reads as `TRANSACTION_SERVICE` `READ` events, while transaction ingestion and authentication events are also outside that feed. The administrator audit-log page was using `/api/activities`, so it could not display those records even though its table already supported arbitrary source-service labels.

The auditor must remain internal-only, and agents must not gain access to the complete trail.

## Decision

1. Add an additive `ListAuditLogs` method to the internal audit gRPC contract. It returns every audit row newest first using the existing sequence-number cursor, without the activity-feed action or success filters.
2. Both the auditor and user-service independently require the caller to be an administrator. User-service forwards the caller's access token to the auditor as it does for activity reads.
3. User-service exposes the paged method as `GET /api/audit-logs`. The response includes event type, source service, action, actor, timestamp and payload, but not chain hashes or parent hashes.
4. The administrator audit-log page calls `/api/audit-logs`. Dashboards and agent activity pages continue calling `/api/activities` and remain changes-only.
5. The existing payload privacy rule remains: audit events contain identifiers and operational details, not client personal data.

## Consequences

- Administrators can see transaction-service reads and ingestion events, failed requests, authentication events and other background audit records.
- The auditor stays off the public routing path and independently checks authorization.
- The complete view can be substantially noisier than the activity feed, but cursor paging bounds each request.
- This adds an API and gRPC contract that must remain backward compatible.

## Validation

- Repository tests prove the complete query returns entries excluded from the activity feed, including `TRANSACTION_SERVICE`.
- Auditor controller tests reject agents and unauthenticated callers.
- User-service controller and gRPC-client tests cover the admin route, token forwarding and response mapping.
- Frontend API and page tests show transaction-service entries.

## References

- [ADR-0021](ADR-0021-audit-read-path-grpc-service-connect.md)
- [ADR-0022](ADR-0022-audit-tamper-resistance-and-crypto-shredding.md)
- [ADR-0064](ADR-0064-transaction-service-authenticates-its-own-reads.md)
- [ADR-0063](ADR-0063-openapi-contract-tests.md)
