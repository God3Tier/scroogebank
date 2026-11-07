# ADR-0070: API throttle sized for 100 concurrent agents

- Status: Accepted (2026-11-02; accepted by Neo Jia Wen)
- Decision date/time: 2026-11-02 16:20 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/infrastructure/load-and-zap-tests`
- Related issue/PR: TBD
- Supersedes: N/A
- Superseded by: N/A

## Context

API Gateway's `$default` stage throttles the whole public API (ADR-0019). Its limits, 50 requests per second with a burst of 100, were labelled "starting values, not derived from measured load" in `stacks/crm/api.tf`.

The requirement is about 100 concurrent agents. The project requirements Q&A says that for "100 concurrent agents … can assume 100 req/s". The report's load test runs 100 agents and 5 admins at once, with a 1-second ramp-up and no think time.

At 50 per second, API Gateway would refuse half of the required rate with 429 before any service saw it. In the trial run, a burst at 80 per second was served at about 52 per second and the rest got 429 ([trial run 2](../testing/load-and-zap-retest-2026-11-02.md)). The trial's 40 per second used one task per service at under 20% CPU, so the services have room.

## Decision Drivers

- Serve the required load: 100 concurrent agents, about 100 requests per second.
- Keep a limit, so that a runaway client or a flood cannot exhaust the services, the `db.t4g.micro` databases or the budget.
- Make the limit something a test can change without editing code.

## Options Considered

### Option A: 200 per second, burst 400, as Terraform variables (chosen)

- Pros: twice the required rate, so page loads that make several calls at once still fit. Each service runs up to 6 tasks with at most 10 database connections each, so the databases stay inside their limits. A test can raise the limit for one apply.
- Cons: a flood can reach the services at up to 200 per second before throttling. Autoscaling and the bounded pools absorb that, as the stress test shows.

### Option B: keep 50 per second

- Pros: the strongest protection.
- Cons: below the requirement. The specified load test would measure mostly 429s.

### Option C: no stage throttle, rely on the WAF

- Pros: nothing to size.
- Cons: the WAF has no rate rule (ADR-0019), and AWS's account-level limit of 10,000 per second is far above what the databases can take.

## Decision

1. The stage's default route settings take `api_throttle_rate_limit` (default 200) and `api_throttle_burst_limit` (default 400), in `stacks/crm/variables.tf`.
2. Tests may override them for one apply, for example the stress test, and set them back afterwards. The test report records the override.

## Rationale

The Q&A's 100 requests per second is a planning figure, not a ceiling. Twice that keeps ordinary bursts, such as a dashboard firing several calls together, out of the 429s. The trial runs showed one task per service serving 40 per second at under 20% CPU, with autoscaling to 6 tasks above that, so 200 per second is within what the stack can scale to.

## Consequences

### Positive

- The required load is served, not throttled.
- The limit is visible in one place and can be tuned from measurements.

### Negative / Trade-offs

- An abusive client gets four times as many requests through before 429 as before.
- The value is still a judgement from one measured load test. It should be revisited if the agent count or the per-page call pattern changes.

## Security & Privacy Impact

The throttle still caps abuse and keeps the services' throughput within what autoscaling and the bounded database pools handle. No change to authentication or data.

## Cost Impact

None at rest. Under load, more requests reach the services, so autoscaling may add tasks sooner. Each Fargate task is about USD 0.012 an hour.

## Operational Impact

To change the limit for a test: `terraform apply -var api_throttle_rate_limit=<n> -var api_throttle_burst_limit=<n>`. Apply again without them afterwards.

## Validation

The full-scale load test ([load-test-full-scale-2026-11-02.md](../testing/load-test-full-scale-2026-11-02.md)) runs the specified scenario at 50 per second and at 200 per second, and compares the 429s and latency. The plan test asserts the defaults.

## References

- [ADR-0019](ADR-0019-edge-entry-chain.md)
- `references/references/Project Requirements Q&A-2627-t1.xlsx`: "100 concurrent agents … can assume 100 req/s"
