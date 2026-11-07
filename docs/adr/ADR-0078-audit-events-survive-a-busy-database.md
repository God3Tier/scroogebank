# ADR-0078: Audit events survive a busy database: own pool, longer wait, queue fallback

- Status: Accepted (2026-11-03; decided by Neo Jia Wen)
- Decision date/time: 2026-11-03 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen (Feature 2 and transaction-service owners to review the code)
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/infrastructure/evaluation-accounts-and-min-tasks`
- Related issue/PR: TBD
- Supersedes: none; refines ADR-0020's middleware exception for client-service and account-service
- Superseded by: N/A

## Context

client-service and account-service audit every endpoint call, and transaction-service every transaction read (ADR-0064 decision 4), with middleware that writes an outbox row right after the request (the exception ADR-0020 decision 1 allows). The write already ignores the caller going away, but it had 2 s and used the same pool of 10 connections as the requests. In the specified load test on 2026-11-03, 37 writes in client-service waited behind the burst's queries for longer than 2 s and were dropped: `audit event not saved … context deadline exceeded`. Those calls were never audited. The stress test the same day found the same in transaction-service: 7,367 read events dropped while it scaled out (`could not save transaction read audit … context deadline exceeded`). Every user action must be audited (Feature 1 and 2 requirements), so dropping events under load is a defect, not a performance trade-off.

## Decision Drivers

- No audit event lost because the database is busy.
- No change to the response the user gets, or to its latency.
- Stay within the database's connection limit (db.t4g.micro: 79, 5 reserved).

## Options Considered

### Option A: a longer timeout only

- Cons: the write still queues behind the burst; a long enough burst still drops events.

### Option B: write the outbox row in the request's own transaction

- Cons: reads have no transaction; it changes every handler, which the ADR-0020 exception exists to avoid.

### Option C: own pool, longer wait, then the queue, then a logged and alarmed failure (chosen)

- Pros: the outbox write no longer competes with requests; if it still fails, the event takes a path that does not need the database; the last resort is visible and recoverable.
- Cons: two of each task's connections are reserved for audit writes; a second delivery path to test.

## Decision

In client-service, account-service and transaction-service:

1. **Own pool:** audit outbox writes use a second database pool of 2 connections (`main.go`), and the request pool goes from 10 to 8, so a task still uses at most 10 and six tasks at most 60. A db.t4g.micro allows 79 connections, 5 of them reserved for administrators. (A first version kept the request pool at 10: at six tasks, 72 connections plus the relay ran the clients database out of slots, and the stress test of 2026-11-03 got 316 HTTP 500s.)
2. **Longer wait:** the write may take 5 s, up from 2 s. The endpoint has already answered.
3. **Queue fallback:** if the write fails, `QueueFallback` (transaction-service: `audit.WithFallback` with the relay's `Send`) sends the same message, with its `event_id`, straight to the audit queue, with its own 3 s. The Auditor drops a repeat by `event_id`, so an event that reached both is chained once. ADR-0020 already allows producers to send directly (the Cognito triggers, dashboard-service).
4. **Logged and alarmed:** if both fail, the service logs `AUDIT_SEND_FAILED` with the whole event (which holds no personal data by design), and a CloudWatch metric filter raises the `service-audit-send-failed` alarm. An operator sends the logged event to the queue.

## Rationale

The loss came from contention, not from the database being down, so separating the audit writes from the request pool removes the cause; the fallback and the alarm cover the rest.

## Consequences

### Positive

- The 37 losses of 2026-11-03 cannot recur from pool contention.
- A remaining loss is visible within 5 minutes and recoverable from the log.

### Negative / Trade-offs

- Two fewer connections per task for requests (8, not 10).
- An event sent directly is not in the outbox, so it skips the relay's ordering; the Auditor orders by its own chain, so this does not matter.

## Security & Privacy Impact

The logged event carries what the queue would have carried: IDs, route, status and time, no client data. The tasks already have `sqs:SendMessage` on the audit queue for the relay.

## Cost Impact

None.

## Operational Impact

A new alarm, `<stack>-service-audit-send-failed`, owned by Feature 2.

## Validation

- Go tests in both services' `serverd/sqs`: a saved event is not sent directly; a failed save sends the same body to the queue; both failing logs `AUDIT_SEND_FAILED` with the event.
- crm `terraform test` asserts the metric filters.
- Re-run `tests/load/asr-load.js`: no `audit event not saved` without a fallback send, no `AUDIT_SEND_FAILED`, and audit-db holds one event per client-service request in the run.

## References

- [ADR-0020](ADR-0020-audit-write-path-outbox-shared-sqs.md), [ADR-0077](ADR-0077-client-service-task-size.md), the 2026-11-02 trial ([load-and-zap-retest-2026-11-02.md](../testing/load-and-zap-retest-2026-11-02.md)), which fixed the unbounded pool
