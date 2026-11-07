# ADR-0071: Test-only tooling: an in-VPC ZAP scanner and a test bucket

- Status: Accepted (2026-11-02; accepted by Neo Jia Wen)
- Decision date/time: 2026-11-02 16:20 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/infrastructure/load-and-zap-tests`
- Related issue/PR: TBD
- Supersedes: N/A
- Superseded by: N/A

## Context

The project brief asks for penetration tests "remotely against your AWS resources and directly at your application resources. Your application can be vulnerable but protected by your AWS resources (e.g., WAF)."

The first ZAP scans could only test through the edge: CloudFront, the WAF, API Gateway's JWT check and the VPC Link. The services sit behind an internal ALB that nothing outside the VPC can reach. Its security group admits only the VPC Link, the ingest Lambda, event alerts and Client Health (ADR-0019 decision 4). There is no bastion, and ECS Exec is off.

The full-scale load test also needs 1.5 million transactions in transaction-service's database, built from the account IDs in account-service's. The one-off psql tasks take their SQL through a run-task override of at most 8,000 characters, far too small for that.

## Decision Drivers

- Scan the services as an attacker who got past the edge would reach them, without opening anything to the internet.
- Add nothing that exists outside a test, and nothing a teardown could miss.
- Keep tokens and reports out of task definitions and the repository.

## Options Considered

### Option A: a one-off ZAP task in the app subnets and a test bucket, behind a toggle (chosen)

- Pros: reaches the ALB the way the VPC Link does; the only new route into the ALB is the scanner's own security group; off by default and destroyed with the stack.
- Cons: three more containers and a bucket to maintain.

### Option B: ECS Exec and SSM port forwarding from a laptop

- Pros: ZAP runs locally.
- Cons: turns on a shell into every task, a larger and longer-lived change than a scanner that exists only during a test.

### Option C: scan the services locally with Docker Compose

- Pros: no AWS change.
- Cons: it is not the deployed application, which the brief asks for.

## Decision

`enable_test_tooling` (default false) in the crm stack creates, in `stacks/crm/test_tooling.tf`:

1. **A bucket**, `<stack>-test-runs-<account>`. It is private and encrypted, empties itself after 7 days, and is `force_destroy`. It holds a scan's inputs (`in/<run>/`), its reports (`reports/<run>/`) and the load test's transaction seed file (`seed/`).
2. **A security group, `zap_scanner`**, with egress to the internet through the NAT gateway (to pull images) and one ingress rule on the ALB from it. No other group changes.
3. **A Fargate task definition**, `<stack>-zap-scanner`, with three containers sharing `/zap/wrk`:
   - `fetch` (aws-cli) copies the run's inputs;
   - `zap` (`zaproxy/zap-stable:2.17.0`) runs `tests/security/zap/scan.sh` against `https://alb.<app host>`;
   - `upload` copies the reports back.
   - The task role may read `in/*` and write `reports/*` only.
4. **The access token for a signed-in scan is an input file**, not an environment variable, so it never appears in the task's description. `run-in-vpc.sh` deletes the run's inputs when the task stops.
5. **The transaction seed file** is fetched by the transactions database's psql task through a 15-minute presigned URL, then deleted (`tests/load/seed-transactions.py`).

## Rationale

The scanner reaches exactly what the VPC Link reaches, which is what a direct test needs. The services still have to check every request's JWT and its ownership themselves. Keeping it behind one toggle that defaults to off means a normal deployment is unchanged. Every test deployment ends in a full teardown (ADR-0058), so nothing stays behind.

## Consequences

### Positive

- Defence in depth can be measured: the same attacks are run with and without the edge in front.
- Large seed files can be loaded without new database access paths.

### Negative / Trade-offs

- While the toggle is on, one more security group may reach the ALB. Anyone who can run ECS tasks in the account can use it, which is the same group who can already run the db-console tasks.
- An active scan writes junk rows through the API, so it runs as a dedicated test agent on synthetic data.

## Security & Privacy Impact

The toggle adds one internal route to the ALB, and only during tests. Nothing is exposed to the internet. Reports can contain request and response samples of synthetic data. They stay in the private bucket for at most 7 days and in the repo's evidence folder only after review.

## Cost Impact

About USD 0.05 an hour per running scan task (1 vCPU, 4 GB), plus pennies of S3. Nothing while the toggle is off.

## Operational Impact

Set `enable_test_tooling = true` in `env/prod.tfvars` before a test and remove it after; the teardown destroys it either way. Usage is in `tests/security/zap/README.md` and `tests/load/README.md`.

## Validation

- `tests/plan_modes.tftest.hcl`: with the toggle off, no scanner, bucket or ALB rule; with it on, all three.
- [zap-active-and-direct-2026-11-02.md](../testing/zap-active-and-direct-2026-11-02.md) records the scans run with it.

## References

- [ADR-0019](ADR-0019-edge-entry-chain.md), [ADR-0058](ADR-0058-every-deployment-ends-with-full-teardown.md), [ADR-0069](ADR-0069-frontend-security-headers-and-self-hosted-fonts.md)
