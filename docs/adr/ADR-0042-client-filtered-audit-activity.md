# ADR-0042: Filter the protected activity feed by client and time window

- Status: Proposed
- Decision date/time: 2026-10-22 22:57 +08:00 (Asia/Singapore)
- Decision owner(s): Joseph Yau (requester; acceptance pending)
- Drafted/assisted by AI: Yes; OpenAI Codex
- Feature/branch: `task/auditor/client-activity-query` (planned; user handles Git operations)
- Related issue/PR: TBD
- Supersedes: N/A
- Superseded by: N/A

## Context

The activity feed in ADR-0021 already supports an inclusive `from`, exclusive
`to`, actor filtering, and sequence-based pagination. It returns CRM user
changes only. Client-profile-management-service's audit middleware records the
client UUID in `payload.client_id`; for a successful client identity
verification it records an `UPDATE` event, the `/verify` endpoint, and either
the checked `document_ids` or `without_document: true` (ADR-0041 decision 6).
The existing feed cannot narrow its result to one client.

## Decision

1. Extend the existing `GET /api/activities` user-service endpoint with an
   optional UUID `clientId` filter. Keep the existing optional inclusive
   `from` and exclusive `to` bounds, page size, and opaque cursor.
2. Add `client_id` as an additive field to the auditor's `ListActivities`
   gRPC request. The SQLBoiler repository filters the stored JSON payload by
   exact `payload->>'client_id'` equality and retains the existing successful
   change-only rules and sequence ordering.
3. Continue forwarding the user's Cognito access token. The auditor verifies
   the JWT itself and derives an agent's actor filter from its verified
   subject, even if the caller requests another actor. Therefore an agent's
   client filter cannot widen the result to another agent's activity. The
   client ID is not treated as proof of ownership; client-service ownership
   authorization remains on the write route that generated each event.
4. Recognize client identity verification events from the verified CRM audit
   envelope: `source_service=CRM`, `action=UPDATE`, matching `client_id`, and
   the `/verify` endpoint plus `document_ids` or `without_document` payload
   evidence. The event remains in the hash chain and chain verification is
   unchanged.

## Consequences

- Agents can review their own client changes over an arbitrary chosen time
  window and identify successful identity verifications for a client.
- Admins retain the existing ability to filter by actor and may also filter by
  client.
- No new audit data is written, no client personal information is added to the
  payload, and the gRPC change is backward-compatible for existing callers.
- This does not provide every agent's history for a client: an agent still
  sees only events whose actor is their own Cognito subject.

## Security & Privacy Impact

The UUID filter is validated by user-service, and it only narrows the existing
result set. Auditor authentication and agent actor enforcement are unchanged.
Audit payloads remain limited to IDs, endpoint metadata, status, and document
IDs; they contain no client personal data.

## Cost Impact

No new infrastructure or billable service is introduced. The query uses the
existing audit database and activity-feed call path.

## Validation

- User-service rejects malformed `clientId` values, enforces the existing
  agent/admin policy, and forwards the client and time filters.
- Auditor controller tests confirm that the verified agent subject and client
  filter are both applied.
- PostgreSQL integration tests confirm that the payload filter composes with
  actor, time-window, pagination, and successful-change filters.

## References

- [ADR-0021](ADR-0021-audit-read-path-grpc-service-connect.md)
- [ADR-0041](ADR-0041-client-identity-documents-and-manual-verification.md)
