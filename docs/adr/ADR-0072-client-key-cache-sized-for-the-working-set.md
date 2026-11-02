# ADR-0072: Client data-key cache sized for the working set, and parallel decryption of a page

- Status: Accepted (2026-11-02; accepted by Neo Jia Wen)
- Decision date/time: 2026-11-02 21:30 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen; client-service's owners (Feature 2) to review
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/infrastructure/load-and-zap-tests`
- Related issue/PR: TBD
- Supersedes: N/A (refines ADR-0030 decision 3)
- Superseded by: N/A

## Context

Every client profile is encrypted with its own data key, which is kept wrapped by KMS in DynamoDB (ADR-0030). A read must unwrap the key unless it is cached: one consistent DynamoDB read and one KMS `Decrypt`. ADR-0030 decision 3 caches plaintext keys for up to 5 minutes.

The full-scale load test on 2026-11-02 ([report](../testing/load-test-full-scale-2026-11-02.md)) found this to be the bottleneck along the agent ASR:
- **The cache was too small.** It held at most 1,000 keys and evicted an arbitrary one when full. The 100 agents' first pages alone hold 2,000 clients, so most reads missed.
- **The list waited on each key in turn.** `GET /api/clients` decrypted its 20 rows one after another, waiting on DynamoDB and KMS for each missed key.
- **The result:** the client list's p95 was 2.7–4.4 s while every other agent route stayed under 200 ms, and the agent ASR's p95 just exceeded the 2,000 ms working limit. Client-service's CPU was low; DynamoDB served up to 5,900 key reads a minute without throttling.

## Decision Drivers

- The required load is 100 agents, each with about 100 clients: 10,000 keys per task in the worst case.
- Keep ADR-0030's protections: deleting a client's key erases the client, and a plaintext key is never held for longer than 5 minutes.
- No new infrastructure.

## Options Considered

### Option A: size the cache for the working set, evict the oldest key, and decrypt a page in parallel (chosen)

- Pros: small code changes in one service; repeated reads hit the cache; a cold page waits about as long as its slowest key, not the sum of all 20.
- Cons: up to 10,000 plaintext keys in a task's memory instead of 1,000. That is about 320 KB of key material, still at most 5 minutes old.

### Option B: one DynamoDB `BatchGetItem` per page

- Pros: one DynamoDB call instead of up to 20.
- Cons: KMS still needs one `Decrypt` per key, so it removes only part of the wait, and changes the protector's interface.

### Option C: a shared cache (ElastiCache)

- Pros: new tasks start warm.
- Cons: plaintext keys would leave the task's memory and cross the network, against ADR-0030's intent, and ElastiCache was ruled out on cost (ADR-0023).

## Decision

1. **The key cache holds up to 10,000 keys** (`keyCacheSize` in `encryption/cache.go`), with the same 5-minute lifetime.
2. **When it is full, it evicts the key fetched longest ago**, after dropping any expired ones, instead of an arbitrary key.
3. **`ListClients` decrypts a page's rows in parallel**, at most 8 at a time (`listDecryptWorkers`), and keeps the page's order. A row whose key was deleted is still skipped.

## Rationale

The load test showed the wait was on key unwrapping, not CPU, and that a warm cache made the list fast:
- with 40 agents, whose keys fit in the old cache, the client list's p95 fell to under 800 ms;
- in the stress test, the median fell from 6.3 s to 0.3 s once tasks were warm.

Sizing the cache to the working set keeps a task warm. Parallel decryption bounds the cost of a cold page, after a deployment or a scale-out.

## Consequences

### Positive

- Repeated client reads, the agent's most common action, no longer call DynamoDB and KMS.
- A new task's first pages cost the slowest key's wait, not the sum.
- Fewer KMS requests, which are billed per call.

### Negative / Trade-offs

- More plaintext keys in memory at once. A memory dump of a running task would expose up to 10,000 clients' keys, each fetched within the last 5 minutes, instead of 1,000.
- A page of 20 cold keys sends up to 8 KMS and DynamoDB calls at once. That is well inside both services' quotas at the required load.

## Security & Privacy Impact

The 5-minute limit, the encryption context and crypto-shredding (ADR-0030) are unchanged. A deleted key is removed from the deleting task's cache at once, and from any other task within 5 minutes, as before. The exposure from a compromised task's memory is larger in count but not in age.

## Cost Impact

None added. Fewer KMS calls lower the cost slightly.

## Operational Impact

None. The constants are in `encryption/cache.go` and `repository/client/list_clients.go`.

## Validation

- **Unit tests:** the cache never holds more than its size, and evicts the oldest key first (`TestKeyCacheExpiresAndEvicts`). The list's order and its skipping of erased clients are covered by the existing `TestListClients`. client-service's suite passes.
- **On AWS (to do):** re-run `tests/load/asr-load.js` on the next deployment. Expect the client list's p95 well under the 2,000 ms limit, and compare with the 2026-11-02 results.

## References

- [ADR-0030](ADR-0030-client-data-envelope-encryption.md), [ADR-0023](ADR-0023-rds-per-service-backup-no-cache.md)
- [Full-scale load test, 2026-11-02](../testing/load-test-full-scale-2026-11-02.md)
