# ADR-0021: Audit reads: internal-only auditor, gRPC through ECS Service Connect

- Status: Accepted
- Decision date/time: 2026-10-09 19:55 +08:00 (Asia/Singapore); first proposed 2026-10-01 21:50 +08:00
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/target-architecture` (proposed); `task/governance/resolve-adrs` (amended, accepted)
- Related issue/PR: TBD
- Supersedes: N/A
- Superseded by: N/A

## Context

The spec's audit requirement says audit logs must be tamper-resistant and "accessible only to authorized administrators". Its UI mock-up (Appendix 3) adds: "Agents can only see their own client activities. Admins can see the activities of all agents." Clients never use the CRM, so these activities are the actions CRM users take on records. The mock-up shows them as a feed: the agent dashboard's "My Recent Activities" ("Created profile for Client1", "Updated profile for Client2") and the admin dashboard's "Recent Activities" ("User1 created an account", "User2 updated profile details"). This is Feature 1 in the base spec, not an x-factor.

That data lives in the auditor's audit-db, the most sensitive data set in the system. Reads need an immediate, paged answer.

On `dev` (2026-10-09):
- no activity endpoint exists;
- the auditor's one gRPC method, `GetAuditLog(service)`, returns every entry for a source service, with no paging, no actor or time filter, and no actor in the response; nothing calls it;
- the auditor's REST and gRPC ports check no identity;
- tamper findings are served over REST on the auditor, including create and delete, and user-service passes those calls through (open item 13).

## Decision Drivers

- Security: minimise the audit store's network exposure (NFR 8)
- The spec's two audiences: administrators for the audit log; each agent for their own activities
- Request/response semantics for reads
- Load balancing of long-lived connections across tasks in two AZs (NFR 1, NFR 2)
- A typed, versioned contract between Go and Java (NFR 3)

## Options Considered

### Transport

- **Option A: REST on the auditor, routed through the ALB to the browser.** Simple, but it exposes the tamper-resistant store to the internet path, and the auditor would own presentation.
- **Option B: reads through SQS (request and reply queues).** Reuses the audit queue, but reads need an immediate answer; request/reply over queues adds latency and complexity.
- **Option C: internal gRPC through ECS Service Connect (chosen).** The auditor stays internal-only; a typed `.proto` contract; Service Connect's Envoy balances calls over healthy tasks. Costs an extra hop and TLS certificate handling.

### Where the activity feed is served

- **user-service (chosen):** the feed is Feature 1, and user-service owns the CRM users, so it can show agents' names. It is already the admin's service.
- **The business dashboard (dashboard-service):** an additional feature (ADR-0033, ADR-0039), scoped to figures rather than log entries, with no database to page through results. A base-spec requirement must not depend on an optional feature.
- **A per-client history in client-service (`GET /api/clients/{id}/activity`):** not in the spec's mock-up. Not built; a later ADR can add it.

### What the feed shows

- **Changes only (chosen):** the mock-up's entries are all changes, and reads would drown them, because every profile view is recorded.
- **Every entry, reads included:** complete, but mostly page views.

## Decision

1. **The auditor is internal-only**: no ALB route, no public endpoint. Only other services call it, over gRPC.
2. **One activity feed, `GET /api/activities` in user-service**, documented in its OpenAPI spec, for both audiences:
   - an **admin** sees the activities of all agents, and may narrow the feed to one agent and a time window;
   - an **agent** sees only their own activities: entries whose actor is their own Cognito `sub`. Because ownership checks stop agents acting on other agents' clients, these are their own client activities.
   - The raw audit log (hashes, payloads, entries from every service) is not shown to anyone through the feed.
3. **Changes only.** The feed shows entries that create, update, delete or disable a record, change a role, reset a password or verify a client. Reads (`READ`, `GET`), dashboard views, sign-in and sign-out, and events from transaction ingestion and chain-verify stay in the audit log and are not in the feed. Entries carry IDs, not client names, because audit events never hold client personal data ([ADR-0030](ADR-0030-client-data-envelope-encryption.md)); user-service adds the acting user's name.
4. **Tamper findings:** admins read them through user-service, never from the auditor directly. Which operations admins have on findings, and how they are served, are decided with chain-verify in [ADR-0022](ADR-0022-audit-tamper-resistance-and-crypto-shredding.md) and [open item 13](../architecture/open-items.md#13-tamper-findings-and-chain-verify-on-dev), not here. Until then, `dev`'s REST pass-through stays, and every call through it is audited.
5. **gRPC through ECS Service Connect** (alias `auditor:50051`, `appProtocol: grpc`). A paged method lists entries by actor and time window, changes only, and replaces the unused `GetAuditLog`.
6. **Zero Trust on every call:**
   - the auditor's security group admits only named callers;
   - callers forward the user's JWT in gRPC metadata, and the auditor validates it itself (signature, issuer, expiry);
   - the auditor enforces the role filter from the token alone: an admin may list any actor; an agent only entries whose actor is the token's `sub`, whatever the caller asks for. It needs no knowledge of client ownership;
   - background callers use a Cognito client-credentials token with an `audit/read` scope.
7. **TLS** end to end with a certificate from a project CA generated by Terraform; the server sets `keepalive.MaxConnectionAge` (about 5 minutes) so connections rebalance.
8. **Resiliency:** about 2-second deadlines, retry only on `UNAVAILABLE`, circuit breakers (Resilience4j in Java, gobreaker in Go); if the auditor is down, the feed shows "activity temporarily unavailable".
9. **Contract:** `.proto` in a shared `proto/` directory, package `audit.v1`, additive changes only, paged responses; CI runs `buf lint` and `buf breaking`.

## Rationale

Keeping the auditor internal removes it from every internet-facing path. One feed in user-service meets both lines of the spec's mock-up with one endpoint, keeps a Feature 1 requirement out of the x-factors, and lets the auditor enforce the agent rule from the verified token without knowing who owns which client. Showing only changes matches the mock-up and keeps the feed readable. gRPC suits request/response reads and gives one generated contract for Go and Java. Service Connect is needed because plain DNS or a layer-4 load balancer would pin each caller's long-lived HTTP/2 connection to one task. Tamper findings are a tamper-resistance question, so they are decided with chain-verify.

## Consequences

### Positive

- Minimal exposure of the audit store; the auditor checks every caller's identity.
- One endpoint serves both the admin and agent dashboards.
- The agent restriction holds even if user-service has a bug, because the auditor applies it from the token.
- Contract breaks are caught in CI.
- Business actions are unaffected by auditor outages (writes go through SQS, [ADR-0020](ADR-0020-audit-write-path-outbox-shared-sqs.md)).

### Negative / Trade-offs

- user-service carries the feed and a gRPC client.
- Reads are not in the feed; who viewed a record is only in the audit log, which has no viewer yet.
- An admin's change to an agent's client appears in the admin's activities, not the agent's.
- Only ECS services can use Service Connect; standalone scheduled tasks and Lambda cannot.
- A project CA and certificate rotation to manage.
- Tamper findings keep `dev`'s REST create and delete until ADR-0022 decides otherwise.

## Security & Privacy Impact

The auditor validates the JWT itself even though user-service already did, and applies the role filter independently. Feed entries carry IDs and action details only, never client personal data.

## Cost Impact

No extra charge for Service Connect beyond the Envoy sidecar's CPU and memory. No AWS Private CA (avoids its monthly fee).

## Operational Impact

Service Connect metrics per call; gRPC error and latency alarms; certificate generation and rotation in Terraform.

## Validation

- `buf breaking` fails a pull request that would break a caller.
- An agent asking for another agent's activities, through any path, gets only their own.
- Reads and dashboard views never appear in the feed.
- Stopping auditor tasks in one AZ keeps the feed working.

## Revision history

- 2026-10-01 21:50 +08:00: proposed.
- 2026-10-09 19:55 +08:00: amended before acceptance, then accepted by the decision owner. The context now quotes the spec's mock-up: clients never use the CRM, so "client activities" are agents' actions on their clients. Decisions 2 and 3 replace the separate client-history and admin views with one changes-only feed in user-service, where agents see their own activities and admins all agents' (open items 1 and 3). Decision 4 moves the design of tamper findings to ADR-0022 and open item 13. Decision 6 bases the agent filter on the token's `sub`, so the auditor needs no ownership data. Decisions on transport, TLS, resiliency and the contract are unchanged.

## References

- [Audit read path](../architecture/data-flow.md#audit-read-path-internal-grpc) and [decisions](../architecture/data-flow.md#audit-read-decisions)
- [Parts not on the diagram: ECS Service Connect](../architecture/system-overview.md#parts-of-the-architecture-not-shown-on-the-diagram)
- [Open items 1](../architecture/open-items.md#1-agent-visibility-of-activity), [3](../architecture/open-items.md#3-admin-wide-activity-view-in-user-service) and [13](../architecture/open-items.md#13-tamper-findings-and-chain-verify-on-dev)
- CS301 project specification v18.0, Appendix 3 (UI mock-up)
