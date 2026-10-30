# ADR-0053: Client Health run orchestration and task admission ownership

- Status: Accepted
- Decision date/time: 2026-10-21 19:36 +08:00 (Asia/Singapore)
- Decision owner(s): Garvit
- Drafted/assisted by AI: Yes; Cursor
- Feature/branch: `task/client-health-monitor/analysis-orchestration`
- Related issue/PR: TBD
- Supersedes: N/A
- Superseded by: N/A

## Context

RH-SF04 must run the complete replay-to-finding pipeline safely when EventBridge
retries or two tasks overlap. Cooldown, worsening, assignment and per-agent caps
need task history and assignment data owned by Swayam's task stream. Reading
that database or independently resolving assignment in Garvit's analysis stream
would duplicate policy and create races.

## Decision Drivers

- One logical daily outcome despite retries and overlap
- Deterministic findings compatible with `RelationshipTaskPort` idempotency
- Atomic cooldown/cap decisions where tasks and assignments are owned
- No watermark after a partial or ambiguous run
- Clear ownership between Garvit's analysis and Swayam's task streams

## Decision

### Analysis-stream ownership (RH-SF04)

1. The run ID is deterministic from analysis date and ruleset version.
2. A service-owned run repository acquires a renewable **60-minute lease**.
   Another live lease fails closed; an expired lease may be recovered.
3. Replay, projection, baselines and ADR-0052 rule evaluation run under that
   lease. The lease is renewed before task handoff and during a long handoff.
4. All signals for one client/run become one immutable `ClientHealthFinding`.
5. Finding ID, signal ID, correlation ID and signal fingerprint are deterministic
   SHA-256-derived identifiers:
   - finding/signal IDs include the run;
   - fingerprints exclude the run and include client, account, signal type,
     rule version and exact windows so overlapping runs de-duplicate evidence.
6. Findings are sent in stable client order through `RelationshipTaskPort`.
7. The run succeeds and advances its watermark only after every finding returns
   an explicit created, attached, duplicate or suppressed outcome.
8. Any replay, rule, handoff or repository failure marks the run failed and
   leaves the watermark unchanged. Partial task writes are safe because retries
   rebuild byte-for-byte-equivalent findings and the task stream is idempotent.

### Task-stream ownership (RH-SF05)

1. Task admission is atomic with task/history/outbox persistence and assignment
   resolution.
2. One `OPEN` or `IN_REVIEW` task per client receives new evidence.
3. After completion or dismissal, the client has a **30-day cooldown**.
4. Cooldown is bypassed only when the new finding's highest severity is
   strictly higher than the most recent closed task.
5. Each Agent may receive at most **20 newly created tasks per Singapore day**.
   Evidence attachment to an active task remains allowed and does not consume
   the creation cap.
6. Suppression returns an explicit `SUPPRESSED_COOLDOWN` or
   `SUPPRESSED_DAILY_CAP` result. It is not represented as a failure or silently
   dropped. SF08 will audit it.
7. Assignment unavailable or persistence failure remains retryable and returns
   no suppression outcome.

## Rationale

The task stream is the only place that can make race-free decisions using
assignment, active tasks, closed history and daily creation counts. SF04 remains
independent of persistence details and focuses on deterministic orchestration.
Strict success-only watermarking prevents incomplete source or task processing
from being mistaken for a completed analysis.

## Consequences

### Positive

- Retry and overlap safety is testable without production infrastructure.
- Cooldown/cap checks cannot race task creation.
- Suppression is observable and auditable.
- SF04 never reads task or assignment storage.

### Negative / Trade-offs

- `TaskUpsertResult` must support explicit suppression outcomes.
- SF05 still needs production admission persistence and Singapore-day counting.
- A failed run may have partially handled findings, but deterministic retry and
  task idempotency make that safe.

## Security & Privacy Impact

Run state and deterministic hashes contain opaque identifiers only. Assignment
stays inside the authorised task stream. Errors and metrics must not log client,
account, transaction or evidence values.

## Cost Impact

No new AWS service. Run state and leases use health-db; the daily task and
small state tables add negligible storage/compute.

## Operational Impact

- Alarm on lease contention, repeated run failure and stale watermark.
- Run records retain status, attempts, timestamps and redacted failure category.
- Lease duration and renewal interval are configurable; V1 defaults to 60
  minutes.
- Daily cap uses `Asia/Singapore` calendar boundaries.

## Validation

- Concurrent acquisition permits one live owner.
- Expired lease recovery repeats the same deterministic run/finding IDs.
- Repeating a successful date returns already-completed without task calls.
- Mid-handoff failure leaves no watermark; retry yields duplicates for prior
  findings and handles the remainder.
- Created, attached, duplicate and both suppression outcomes permit success.
- Unknown/ambiguous task outcomes fail the run.

## References

- [ADR-0034](ADR-0034-xf3-health-monitor-rules-and-bedrock-boundary.md)
- [ADR-0052](ADR-0052-client-health-versioned-signal-rules.md)
- [`client-health-monitor_plan.md`](../feature-plans/client-health-monitor_plan.md)
- [`client-health-monitor-contracts.md`](../api/client-health-monitor-contracts.md)
