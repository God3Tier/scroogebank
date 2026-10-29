# Event-Driven Alerts Operations Runbook

Status: offline operational baseline. Cloud commands, screenshots, alarm tests,
and deployment evidence remain pending approved AWS access.

## Safety invariants

- Both outbound capabilities default off.
- Quota or budget exhaustion fails closed; it does not silently downgrade a
  reservation.
- Re-enable requires a named human actor, correlation ID, and successful audit.
- A capability disabled by the current month's budget cannot be re-enabled until
  the UTC month rolls over or the approved budget configuration changes.
- Do not use real client data or credentials in tests, logs, metrics, or drills.

## Configuration

| Environment variable | Default | Meaning |
|---|---:|---|
| `ALERT_MODE` | `API` | `API` serves `/api/alerts`; `WORKER` runs one ingestion window and exits, with code 1 if it failed |
| `ALERT_INGESTION_ENABLED` | `false` | Allows provider ingestion reservations |
| `ALERT_SEARCH_PROVIDER` | `tavily` | Selected public-news adapter; the worker stack pins this value |
| `ALERT_TAVILY_API_KEY` | empty | Tavily key; injected from the worker-owned Secrets Manager secret, never set in a committed file |
| `ALERT_INGESTION_APPROVED_DOMAINS` | empty | Optional comma-separated reviewed commercial publishers; keep empty until ADR-0011 terms approval. Issuer official domains come from alerts-db |
| `ALERT_AI_ENABLED` | `false` | Allows model invocation and model-token reservations |
| `ALERT_AI_PROVIDER` | `none` | Set to `bedrock` together with `ALERT_AI_ENABLED=true`; the worker stack sets it automatically |
| `ALERT_AI_MODEL` | `global.amazon.nova-2-lite-v1:0` | Pinned Nova 2 Lite global inference profile |
| `ALERT_AI_BEDROCK_REGION` | `ap-southeast-1` | Singapore source Region for Bedrock Runtime; global inference may process elsewhere |
| `ALERT_MONTHLY_SEARCH_QUOTA` | `1000` | Search reservations per UTC month |
| `ALERT_MAX_SEARCHES_PER_RUN` | `4` | Search reservations per worker run |
| `ALERT_MINIMUM_SEARCH_CLIENT_SHARE` | `0.20` | Inclusive fraction of distinct positive-position clients for the common-holding rule |
| `ALERT_MINIMUM_SEARCH_CLIENT_COUNT` | `2` | Minimum distinct snapshot clients before an instrument's public terms are searchable |
| `ALERT_MINIMUM_ACCOUNT_CONCENTRATION` | `0.50` | Inclusive per-account share of positive SGD portfolio value for the concentration rule |
| `ALERT_MONTHLY_MODEL_TOKEN_QUOTA` | `200000` | Model-token reservations per UTC month |
| `ALERT_MAX_MODEL_TOKENS_PER_INVOCATION` | `2000` | Tokens per model reservation |
| `ALERT_MONTHLY_BUDGET_USD` | `25.00` | Estimated-cost stop threshold |
| `ALERT_AUDIT_PUBLISHER_ENABLED` | `false` | Enables leased delivery to the auditor SQS queue |
| `AUDIT_QUEUE_URL` | empty | Required SQS queue URL when audit publishing is enabled |
| `AWS_REGION` | `ap-southeast-1` | Region of the auditor SQS queue |
| `AWS_ENDPOINT_URL` | empty | LocalStack endpoint for local integration only; omit in AWS |
| `ALERT_AUDIT_PUBLISH_MAX_ATTEMPTS` | `10` | Maximum delivery claims before an event remains failed for investigation |
| `ALERT_RETENTION_ENABLED` | `true` | Enables bounded local content expiry |

There is intentionally no unauthenticated operator endpoint in this offline
slice. Production controls must be exposed only through an authenticated,
authorized adapter. Re-enable writes a redacted event to the PostgreSQL audit
outbox before changing the control state. An unavailable or full outbox
fails closed and leaves the capability disabled.

## Deploy the worker

The worker is the `stacks/alert-worker` Terraform stack ([ADR-0048](../adr/ADR-0048-alert-worker-terraform-stack.md)), deployed by hand as in [deploy-aws.md](deploy-aws.md): sign in as the `crm` role first ([AWS access](deploy-aws.md#aws-access)) and run these from the repository root in Git Bash. It reads the CRM stack's state, so apply the CRM stack first and push the event-alert-service image (its step 5). The worker uses the same image and tag as the API.

```sh
cd infrastructure/terraform/stacks/alert-worker
STATE_BUCKET=$(terraform -chdir=../../bootstrap output -raw state_bucket); echo "$STATE_BUCKET"
sed "s/^bucket *=.*/bucket       = \"${STATE_BUCKET:?no state_bucket output: finish step 1}\"/" env/prod.s3.tfbackend.example > env/prod.s3.tfbackend
[ -f env/prod.tfvars ] || cp env/prod.tfvars.example env/prod.tfvars   # keeps an existing file
sed -i "s/^state_bucket *=.*/state_bucket = \"$STATE_BUCKET\"/" env/prod.tfvars
terraform init -backend-config=env/prod.s3.tfbackend
terraform apply -var-file=env/prod.tfvars
cd -
```

With both switches at their default `false` this creates the task definition, a `DISABLED` schedule, the failed-run rule and two IAM roles, and nothing runs. The schedule follows the CRM stack's `running`: apply this stack again after a CRM apply changes it.

The first apply also creates an empty Tavily secret. Populate it without putting
the value in Terraform state, shell history, a `.tfvars` file or source control:

```sh
TAVILY_SECRET_ARN=$(terraform output -raw tavily_secret_arn)
read -rsp "Tavily API key: " TAVILY_API_KEY; echo
aws secretsmanager put-secret-value --secret-id "$TAVILY_SECRET_ARN" --secret-string "$TAVILY_API_KEY" >/dev/null
unset TAVILY_API_KEY
```

Keep `ingestion_enabled = false` until that command succeeds and a provider
benchmark passes. The initial 2026-10-29 global-portfolio benchmark failed; a
later single-issuer repeat met the numeric gates only on a small retrospective
reference set. It is not approval to enable the schedule until independent
labels, source terms and billed credits are reviewed. A task cannot
start if its referenced secret has no
current version.

### Smoke test the task and the alarm

Use an intentionally invalid synthetic Tavily key for this drill; never alter a
real key merely to test failure handling. With ingestion on, the run fails
cleanly with `PROVIDER_ACCESS_DENIED` and exits 1, which should raise the alarm.

1. Set `ingestion_enabled = true` in `env/prod.tfvars` and apply. The schedule becomes `ENABLED` while the stack is running.
2. Start one run now. The output holds the full command; run it, and note the task ARN it prints:

   ```sh
   eval "$(terraform -chdir=infrastructure/terraform/stacks/alert-worker output -raw run_once_command)"
   ```

3. Read the one summary line in the log group from `terraform output -raw log_group`: `worker run status=FAILED ... error_code=PROVIDER_ACCESS_DENIED`.
4. Check that the alarm e-mail arrived, and that alerts-db has one `FAILED` row in `search_run` (use `scripts/db-run-sql.sh alerts`).
5. **Set `ingestion_enabled = false` and apply again,** or the schedule keeps failing every four hours. It spends nothing else, since no search runs.

Do not turn `ai_enabled` on until the AI benchmark gates pass. Nova 2 Lite has
already completed one real-account pipeline call; repeat the bounded smoke test
after deployment because model access and quotas remain account-specific.

## Smoke test the whole path on AWS

Do this once the CRM stack is running, the worker stack is applied, and the
Tavily secret has a current version (see [Deploy the worker](#deploy-the-worker)).
Each step turns one more switch on, so a failure points at one cause.

1. **Search only.** In `env/prod.tfvars` set `ingestion_enabled = true`; apply; start one run (`run_once_command`). The log line should say `status=SUCCEEDED` with `accepted` above zero. `PROVIDER_ACCESS_DENIED` means the secret value is missing/invalid, `PROVIDER_QUOTA_EXCEEDED` means the Tavily account limit stopped the run, and `INVALID_PROVIDER_RESPONSE` means Tavily returned a response outside the bounded contract. Inspect the provider request ID in the Tavily dashboard rather than logging the response body or API key.
2. **Add the model.** Set `ai_enabled = true`, apply, and run again. The line `worker pipeline examined=… alerts_created=…` should show sources processed. Look in alerts-db (`scripts/db-run-sql.sh alerts`): `SELECT processing_outcome, count(*) FROM source_result GROUP BY 1;` and `SELECT assigned_agent_subject, count(*) FROM alert GROUP BY 1;`. `deferred=1` means the model call was refused: check the Bedrock permission and the model access in the account.
3. **Replay.** Use `replay_command` with a window inside the last four hours. The audit event `alert.replay-requested` appears in `audit_outbox`, and `search_run` has a row for that window while `processing_watermark` is unchanged.
4. **Metrics (optional).** Set `cloudwatch_metrics_enabled = true`, apply, run once, and look for the `CRM/EventAlerts` namespace in CloudWatch. Turn it off again unless you want the charts: each metric and tag combination bills.
5. **Switch back off.** Set `ingestion_enabled`, `ai_enabled` and `cloudwatch_metrics_enabled` to `false` and apply, so the schedule stops spending between sessions.

The fixture contains 20 opaque clients, four synthetic agents, and public
instrument references across SGX, NASDAQ, NYSE, LSE, and HKEX. It also retains
`SYN-SGX-001` for deterministic downstream tests. A live search can therefore
exercise real issuer matching without adding client data. Review the
[2026-10-29 benchmark](../testing/tavily-global-portfolio-benchmark-2026-10-29.md)
before changing the enable switch.

## Inspect health and consumption

Inspect application health and these low-cardinality Micrometer series:

- `alert.outbound.reservations`, tagged by `capability` and `result`
- `alert.outbound.disabled`, tagged by `capability` and `reason`
- `alert.outbound.reenabled`, tagged by `capability`
- `alert.outbound.monthly.searches`
- `alert.outbound.monthly.model.tokens`
- `alert.outbound.monthly.estimated.cost.usd`
- `alert.search.universe.eligible`, `alert.search.selection.common`, and `alert.search.selection.concentration`
- `alert.search.batch.selected` and `alert.search.batch.deferred`
- `alert.search.coverage.oldest.age.seconds`

Correlate application evidence with the provider bill before treating estimated
cost as authoritative.

## Disable and contain

1. Identify the incident, actor, affected capability, and correlation ID.
2. Disable ingestion and/or model operation through the approved operator
   adapter. If that adapter is unavailable, set the relevant enable flag to
   `false` and redeploy through the normal reviewed path.
3. Confirm new reservations are denied and the disabled counter increments.
4. Preserve redacted logs, metrics, provider usage, and billing evidence.
5. Do not re-enable while spend, malformed evidence, authorization, or provider
   availability remains uncertain.

## Provider outage or malformed response

1. Stop new reservations for the affected capability.
2. Confirm failures are visible without recording unverified evidence or
   generating alerts from incomplete data.
3. Record the provider status, first/last failure, affected run IDs, and retry
   count using synthetic or redacted identifiers.
4. Resume only after a bounded synthetic probe succeeds and the human owner
   approves an audited re-enable.

## Replay and recovery

Bounded historical ingestion replay is implemented behind the internal worker
boundary. It does not move the scheduler watermark. Before a production replay:

Scheduled runs sort eligible instruments, select at most four from the current
four-hour UTC slot, and resume from `instrument_search_coverage` with the normal
deduplication overlap. Replays use the same deterministic slot selection but
never advance coverage. A failed or blocked run also leaves coverage unchanged.

1. Select an explicit bounded run/event range and verify database idempotency.
2. Keep outbound capabilities off while checking the candidate set.
3. Estimate searches, tokens, and cost; stop if any remaining quota is
   insufficient.
4. Obtain owner approval and record its correlation ID.
5. Enable only the required capability, execute the bounded replay, and disable
   it again if the drill requires containment.
6. Confirm duplicate deliveries did not create duplicate alerts or audit events.

For local auditor recovery, inspect `audit_outbox` by status and attempt count.
`PENDING` and `FAILED` rows below the attempt cap are reclaimed in creation order;
an expired `LEASED` row is also safe to reclaim. Confirm SQS and the auditor are
healthy before enabling the publisher. Never edit payloads or mark rows delivered
manually; delivery state changes only after broker acknowledgement.

The hourly retention job processes a bounded batch. It removes expired briefing
text and redacts expired source metadata, but never deletes audit evidence. A
backlog is safe to drain over repeated runs; increase the batch only after checking
database load.

## Re-enable checklist

- Root cause is understood and corrected.
- Remaining monthly quota and budget are independently verified.
- A synthetic bounded probe passed.
- Required ADRs are accepted and the durable audit adapter is healthy.
- The owner, reason, and correlation ID are recorded before state changes.
- Dashboards and alarms are observed through at least one scheduled run.

## Current gaps

CloudWatch/IaC, billing reconciliation, authenticated operator controls, live
provider integration, staging replay execution, and cloud operational-drill
evidence are not implemented. Local auditor publication, bounded retry/recovery,
and retention are implemented and tested, but no production broker or cloud
control was exercised. This document must not be used as evidence that cloud
controls were tested.

The monthly search and token counts, the estimated cost and every disable are
kept in alerts-db (`outbound_usage` by UTC month, `outbound_control` by
capability), so the API and each worker run share them. Reservations are atomic
statements, so concurrent processes cannot exceed a quota, and a reservation is
kept even if the caller's own transaction later rolls back. An operator disable
or a quota or budget stop made in one process therefore applies to the next worker
run. Configuration still wins: a capability switched off by its enable flag stays
off, whatever is stored.

Each run holds a PostgreSQL session advisory lock for its whole duration. A
second run, scheduled, manual or a replay, ends as `ALREADY_RUNNING` before it
reserves quota or searches. The lock belongs to one database connection, so a
crashed process releases it when its connection closes.
