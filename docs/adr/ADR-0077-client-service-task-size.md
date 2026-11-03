# ADR-0077: client-service tasks of 0.5 vCPU and 1 GB, and reused AWS connections

- Status: Accepted (2026-11-03; decided by Neo Jia Wen)
- Decision date/time: 2026-11-03 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen (Feature 2 and transaction-service owners to review the code)
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/infrastructure/evaluation-accounts-and-min-tasks`
- Related issue/PR: TBD
- Supersedes: none (client-service used the ecs-service module's default of 0.25 vCPU and 0.5 GB)
- Superseded by: N/A

## Context

On 2026-11-03 the specified load test (100 agents and 5 admins, 1 s ramp-up, 3 loops, 10,000 clients) ran against client-service at its minimum of 2 tasks (ADR-0075), each of 0.25 vCPU, with a cold data-key cache. The agent ASR's p95 was 9.1 s: listing clients 12.2 s, reading one client 6.9 s, and listing an account's transactions 6.4 s (its owner check calls client-service). Every other route stayed under 340 ms. client-service's CPU peaked at 82%.

The burst lasts about 25 s. Auto scaling needs at least a minute of CloudWatch data, a 60 s cooldown and 1.5–2 minutes to start a task, so no new task serves before the burst ends. client-service's maximum of 6 tasks was never reached, so raising it would not help. Only capacity that is already running does.

## Decision Drivers

- The agent ASR's p95 under the specified load, from a cold start.
- Cost while the stack runs; nothing while it is torn down (ADR-0058).

## Options Considered

### Option A: a higher minimum for client-service (for example 4 tasks)

- Pros: more parallelism, more key caches.
- Cons: each task still has 0.25 vCPU, and each one starts with an empty cache; it costs about as much as option C for the same CPU.

### Option B: raise the maximum

- Cons: no effect on a burst shorter than scale-out.

### Option C: 0.5 vCPU and 1 GB per client-service task (chosen)

- Pros: twice the CPU at the burst for the work that is CPU-bound (decrypting each page of clients, ADR-0072); same scaling model.
- Cons: about USD 0.015 an hour more per task.

## Decision

1. client-service's task definition sets `cpu = 512` and `memory = 1024`. Its minimum stays at 2 (ADR-0075) and its maximum at 6. Other services keep their sizes until a measurement shows a need.
2. client-service's AWS SDK clients keep up to 128 idle connections per host (256 in all), not the SDK's default of 10. A burst of key-cache misses sends hundreds of KMS and DynamoDB calls at once; with 10, most of them opened a new TLS connection, which cost more CPU than the call.

## Rationale

The slow routes are those that decrypt client rows; the others, on the same requests and databases, stayed fast. CPU, not the database or KMS, limits client-service at the burst, so more CPU per task is the direct fix.

## Consequences

### Positive

- Higher capacity for client reads from the first second of a burst.

### Negative / Trade-offs

- About USD 0.03 an hour more at 2 tasks, USD 0.09 at 6.

## Security & Privacy Impact

None.

## Cost Impact

0.25 vCPU and 0.5 GB more per task at ap-southeast-1 Fargate prices: about USD 0.015 an hour per task.

## Operational Impact

None beyond the task definition's new revision.

## Results (2026-11-03, cold, the specified scenario)

| Change | Agent ASR p95 | List clients p95 | Failed requests | Audit events lost |
|---|---|---|---|---|
| Before (0.25 vCPU, 10 connections per host) | 9.1 s | 12.2 s | 0 | 37 |
| 0.5 vCPU, plus ADR-0078 | 6.05 s | 8.6 s | 0 | 0 (9 sent straight to the queue) |
| 0.5 vCPU and 128 connections per host | 2.4 s | 4.05 s | 0 | 0 |

CPU during the first burst was about 100% at 0.5 vCPU before the connection change.

## Validation

- crm `terraform test` asserts the size (`client_service_capacity_and_audit_alarm`).
- Re-run `tests/load/asr-load.js` cold; compare with the 9.1 s p95 above.

## References

- [Full-scale load test, 2026-11-02](../testing/load-test-full-scale-2026-11-02.md), [ADR-0072](ADR-0072-client-key-cache-sized-for-the-working-set.md), [ADR-0075](ADR-0075-two-tasks-minimum-per-service.md)
