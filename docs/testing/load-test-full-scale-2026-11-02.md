# Full-scale load test on AWS, 2026-11-02

The load test the project report specifies: **5 concurrent admins and 100 concurrent agents, a 1-second ramp-up and a loop count of 3**, over **10,000 clients, 30,000 accounts and 1,500,000 transactions**. A stress test beyond that load followed. This is the evidence for NFR 1 (performance and scalability) in the [test strategy](test-strategy.md). It follows the two small-scale trial runs ([trial 1](load-and-zap-test-2026-11-02.md), [trial 2](load-and-zap-retest-2026-11-02.md)), which used 3 agents, 1 admin and 75 clients and fixed four defects. The ZAP scans on the same deployment are in [zap-active-and-direct-2026-11-02.md](zap-active-and-direct-2026-11-02.md), and the availability drills in [availability-drills-2026-11-02.md](availability-drills-2026-11-02.md).

Run by Neo Jia Wen's session (Claude Code) on `task/infrastructure/load-and-zap-tests` at `4ddcaad`, in AWS account 727816232662, `ap-southeast-1`, on 2026-11-02 from 08:10 UTC. It used the prod configuration with `high_availability = false` and `enable_test_tooling = true` ([ADR-0071](../adr/ADR-0071-test-only-in-vpc-zap-scanner.md)). Synthetic data only.

## Result

| Test | Result |
|---|---|
| Data volume | **Seeded.** 105 load-test users (100 agents, 5 admins). 10,000 clients and 30,000 accounts were created through the API: 40,000 writes, 0 failed, p95 123 ms. 1,500,000 transactions were inserted with SQL. |
| Specified scenario, API throttle 50/s (before) | **Throttled.** 158 of 1,054 agent requests (15%) were refused with 429, and agent p95 was 4.1 s. Admins had no errors, with p95 264 ms. |
| Specified scenario, throttle 200/s ([ADR-0070](../adr/ADR-0070-api-throttle-sized-for-100-concurrent-agents.md)), 3 runs | **Served.** The agents had 0 to 2.2% errors, all 429s from the first, cold burst, and none in runs 2 and 3. The admin ASR's p95 was 120–203 ms. The agent ASR's p95 was 2.10–2.20 s, just over the 2,000 ms working limit, because of one route (`GET /api/clients`). |
| Transactions at 1.5 million rows | **Fast.** `GET /api/accounts/{id}/transactions` p95 was 56 ms (run 3), against 40 ms at 1,500 rows in trial 2. The keyset paging and the `(account_id, transaction_date)` index hold at a thousand times the data. |
| Stress, 100 to 400 virtual agents | **No errors, latency rises.** 126,376 requests, 6 failed (0.005%). Throughput grew from 38 to 239 requests per second as client-, account- and transaction-service scaled from 1 to 6 tasks. p95 was 5.7–10.5 s at each step. |
| Bottleneck found | **The client list unwraps each row's data key in turn.** The key cache holds 1,000 keys per task, and the 100 agents' first pages need 2,000. Open item 1. |

## Data and users

- **Users:**
  - 100 agents (`loadtest.agent000…099@example.com`) and 5 admins were created directly in Cognito with `--message-action SUPPRESS`. Cognito's own e-mail sender allows 50 messages a day, so 105 invitations through `POST /api/users` would have failed.
  - Each user was added to its group, given a permanent password and enrolled in TOTP. Their rows were inserted into user-service's database with SQL. user-service then lists 101 agents and 6 admins (the root admin included).
  - A separate `zap.agent@example.com` with 5 clients served the ZAP scans.
  - Signing in used the test-only `ALLOW_ADMIN_USER_PASSWORD_AUTH`, as in the trials; it was never committed.
- **Clients and accounts:** `seed-scale.js` created 100 clients per agent and 3 accounts per client (savings, checking, investment) through the API, between 08:39:20 and 08:45:37:
  - 40,000 requests at 110 per second;
  - 0 failed;
  - p95 123 ms, p99 260 ms.
- **Transactions:** `seed-transactions.py` read the 30,000 account and client IDs from account-service's database and inserted 50 transactions each into transaction-service's database. That is 1,500,000 rows, dated over the past year, 90% Completed. The task ran from 08:50:00 to 09:06:27; the file reached it through a 15-minute presigned URL from the test bucket.

**Asynchronous audit writes during seeding** (Table 10, row 2 of the report):
- The services' outbox relays sent about 3,000 audit events a minute to SQS while the requests kept their 123 ms p95.
- After the burst the queue held up to 22,219 messages, and the oldest was at most 3 minutes old.
- The Auditor scaled from 1 to 6 tasks on its queue-depth policy (08:50–08:51) and chained about 8,000 events a minute until the queue was empty.
- No request waited for an audit write.

## The specified scenario

`asr-load.js` runs two scenarios at once, each virtual user with its own user's token:

| Setting | Agents | Admins |
|---|---|---|
| Concurrent users | 100 | 5 |
| Loops | 3 | 3 |
| Ramp-up | 1 s (each user starts `index/users` seconds in) | 1 s |
| Journey (ASR) | `GET /api/clients` (page of 20), `GET /api/clients/{id}`, `GET /api/accounts?clientId=`, `GET /api/accounts/{id}/transactions` | `GET /api/users`, `GET /api/activities` |
| Think time | none | none |

The two thresholds, p95 under 2,000 ms and errors under 1%, are the API latency alarm's working limits; no NFR sets a number.

| Run (UTC) | Throttle | ASR | Requests | Avg ms | Median | p95 | p99 | Errors | Req/s |
|---|---|---|---|---|---|---|---|---|---|
| 09:08:27 | 50 / 100 | Agent | 1,024 | 710 | 40 | 4,078 | 6,466 | 15.4% (429) | 96 |
| | | Admin | 30 | 164 | 191 | 264 | 300 | 0 | 2.8 |
| 09:09:27 (cold burst) | 200 / 400 | Agent | 1,144 | 387 | 36 | 2,195 | 4,652 | 2.2% (25 × 429) | 150 |
| | | Admin | 30 | 131 | 108 | 203 | 205 | 0 | 3.9 |
| 09:09:59 | 200 / 400 | Agent | 1,200 | 454 | 80 | 2,147 | 3,764 | 0 | 160 |
| | | Admin | 30 | 66 | 63 | 120 | 133 | 0 | 4.0 |
| 09:10:31 | 200 / 400 | Agent | 1,200 | 436 | 39 | 2,102 | 2,775 | 0.08% (1 × 429) | 172 |
| | | Admin | 30 | 97 | 95 | 189 | 314 | 0 | 4.3 |

At 50 per second, journeys stopped early when the first call got 429, so the run made fewer requests. Each run takes 7 to 11 seconds, too short for autoscaling, so it ran on the tasks already there. client-service and account-service still had 3–4 tasks each, scaling in after the seeding (task counts in the evidence).

**By route, run 3 (200 per second):**

| Route | Requests | Median ms | p95 | p99 |
|---|---|---|---|---|
| `GET /api/clients` | 300 | 1,357 | 2,681 | 3,096 |
| `GET /api/clients/{clientId}` | 300 | 291 | 693 | 878 |
| `GET /api/accounts` | 300 | 22 | 29 | 34 |
| `GET /api/accounts/{accountId}/transactions` | 300 | 32 | 56 | 120 |
| `GET /api/users` | 15 | 89 | 105 | 105 |
| `GET /api/activities` | 15 | 108 | 188 | 188 |

Only the two client routes are slow; every account, transaction and admin route answers within 200 ms at p95. In the 50-per-second run, the transactions route's p95 was 6.5 s. That was the first read of the newly inserted 1.5 million rows from a cold database cache; from the next run it was 56–182 ms.

## The bottleneck: client data keys

Every client profile is encrypted with its own data key (ADR-0030). `ListClients` decrypts its page one row after another, and for each key not in the task's cache it makes a consistent DynamoDB read and a KMS `Decrypt`, one after the other. The cache holds 1,000 keys for 5 minutes and evicts an arbitrary entry when full. The 100 agents' first pages alone hold 2,000 clients.

The evidence:
- During the runs, the client-keys table served 3,600 to 5,900 consistent reads a minute, with no throttling. Most list requests were therefore missing the cache, not waiting on a limit.
- Client-service's CPU stayed low at 1-minute resolution (30% at most).
- With 40 agents, whose 800 keys fit in the cache, a second run's client-list p95 fell to 763 ms.
- Raising client-service to 3 tasks before a run made it worse: client-list p95 9.0 s. One of the three tasks had just started with an empty cache, and each task has its own cache.

This is the bottleneck along the agent ASR. Fixing it changes ADR-0030's key-handling trade-off: more plaintext keys in memory, for longer or across a whole page. So it is open item 1 for client-service's owners, not changed here.

## Stress test

`stress.js` ran 09:31:33–09:46:05. The virtual agents stepped 100 → 200 → 300 → 400, 30 s ramp and 3 minutes at each level, with 1 s of think time between journeys and 4 per real agent at the top. The stage throttle was raised to 1,000/2,000 for this run only and set back to 200/400 afterwards.

| Virtual agents | Requests in the 3-minute hold | Req/s | Median ms | p95 | p99 | Failed |
|---|---|---|---|---|---|---|
| 100 | 6,805 | 38 | 256 | 8,266 | 10,748 | 0 |
| 200 | 17,329 | 96 | 32 | 10,491 | 15,896 | 0 |
| 300 | 37,542 | 209 | 38 | 7,504 | 14,353 | 6 |
| 400 | 42,941 | 239 | 203 | 5,723 | 8,753 | 0 |

Overall: 126,376 requests, 6 failed (0.005%), no 429, p95 7.08 s, at most 29 s.

| Time (UTC) | client-service | account-service | transaction-service | auditor |
|---|---|---|---|---|
| 09:31 start (100) | 1 | 1 | 1 | 1 |
| 09:36:45 (≈200) | → 4 | → 2 | → 2 | 1 |
| 09:39:19–09:41:22 (300) | → 6 | → 4, 5, 6 | → 4, 5, 6 | 1 |
| 09:43:25 (400) | 6 | 6 | 6 | → 6 (audit backlog) |
| 10:03–10:16 scale-in | 6 → 5 | 6 → 2 | 6 → 1 | 6 → 4 |

What it shows:
- **Scaling multiplies throughput.** At 100 virtual agents on one client-service task, the client list's median was 6.3 s. With 4–6 warm tasks it fell to 0.3–0.4 s, while the request rate grew six times. The request-count policy did the scaling, as in the trials.
- **The next limit.** At 400, the transactions route slowed (median 2.2 s, p95 8.1 s) with transaction-service and account-service at their maximum of 6 tasks. Each transaction read checks ownership with account-service (ADR-0064), and both use `db.t4g.micro` databases. That is the next bottleneck after the client keys.
- **Nothing fell over.** 6 failures in 126,376 requests; no task crashed.

Scale-in began about 15 minutes after the load ended and took one task off every 3 minutes, as in the trials.

## Deviations

- **Prod configuration**, not staging, as in the trials.
- **The 105 users were seeded directly** in Cognito and the users database, not through `POST /api/users`, because of Cognito's 50-e-mails-a-day limit.
- **The transactions were inserted with SQL**, not imported through SFTP: no SFTP server ran.
- **k6, not JMeter.** The thread-group settings were reproduced: concurrent users, ramp-up and loop count.

## Open items

1. **Client data keys on the list path** (client-service, ADR-0030). Possible changes, for the owners to weigh against key exposure:
   - a cache sized to the clients a task serves (10,000 keys is about 1 MB);
   - least-recently-used eviction;
   - unwrapping a page's keys concurrently, or with one DynamoDB `BatchGetItem`.

   Then re-run `asr-load.js`.
2. **Transaction reads at 400 virtual agents**: the ownership call to account-service, and the micro databases. Re-measure after item 1.
3. **A cold burst can still draw a few 429s** at 200/400 (25 in run 1). Raise the burst if the agents' first page loads matter more than flood protection (ADR-0070).

## Evidence

[`evidence/2026-11-02-full-scale/load/`](evidence/2026-11-02-full-scale/load/):
- k6 summaries for every run, without setup data;
- per-request CSVs of the ASR runs;
- the task counts every 30 s, the scaling activities, and CPU and requests per target.

The k6 dashboard reports (HTML) and screen recordings are kept outside the repo with the report.

## Cost

See the teardown section of [availability-drills-2026-11-02.md](availability-drills-2026-11-02.md); the whole deployment's cost is recorded there.
