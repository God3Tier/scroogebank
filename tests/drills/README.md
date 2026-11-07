# Availability drills

The drills behind the availability and resilience evidence for NFR 2 and 5 ([test strategy](../../docs/testing/test-strategy.md)). Each one breaks part of a running deployment on purpose and measures what users saw. They were first run by hand on 2026-11-02 ([report](../../docs/testing/availability-drills-2026-11-02.md)), and the task failure again on 2026-11-03 with two tasks per service ([report](../../docs/testing/retest-2026-11-03.md)).

| Script | What it breaks | What it shows |
|---|---|---|
| `task-failure.sh` | Stops one running task of a service (default client-service) | What users see while ECS replaces it: with two tasks per service (ADR-0075), nothing |
| `auditor-outage.sh` | Stops the Auditor and suspends its autoscaling, makes 20 changes, then starts it again | Users are not blocked, and every audit event is chained once when the Auditor is back, with none in the dead-letter queue |
| `db-failover.sh` | Forces a Multi-AZ failover of a database (default clients) | The seconds of errors while connections move, and that a client committed before the failover reads back intact |
| `chain-verify.sh` | Nothing: runs the daily audit chain check now | That the whole chain is intact after a drill |

`lib.sh` holds what they share, `probe-report.py` turns the probe's output into the outage and the evidence CSV, and `api.py` makes the drills' changes and marker client.

Run them only against your own deployment, and never while someone else is using it or recording: each asks you to type the cluster's name before it breaks anything, and refuses any cluster but `crm-sg-prod` unless `EXPECTED_CLUSTER` says otherwise.

## Before a drill

From the repository root in Git Bash, with Docker Desktop running and credentials for the CRM account:

1. The test agent: `zap.agent@example.com`, made by [`tests/test-users.py`](../test-users.py), with the temporary sign-in override that [the ZAP README](../security/zap/README.md#1-prepare-once-per-deployment) describes. The drills act as it, and the probe lists its clients.

   ```sh
   SECRETS=<folder outside the repository>
   python tests/test-users.py create --store "$SECRETS/users.json" --agents 0 --admins 0
   ```

2. For `db-failover.sh`, a stack with high availability, so the databases are Multi-AZ.

## Running

```sh
tests/drills/task-failure.sh task-failure "$SECRETS/users.json"            # client-service
tests/drills/auditor-outage.sh auditor-outage "$SECRETS/users.json"
tests/drills/db-failover.sh db-failover "$SECRETS/users.json"               # clients-db
tests/drills/chain-verify.sh after-drills
```

Each writes to `out/drills/<run-id>/`:
- `report.txt`, the outage and the drill's timeline;
- `probe.csv`, one row per probe request (`unix_time,status,route`), the evidence to copy into `docs/testing/evidence/`.

To drill another service or database, set `PROBE_PATH` to a route it serves, for example `PROBE_PATH='/api/accounts?clientId=<id>' tests/drills/task-failure.sh tf-account "$SECRETS/users.json" account-service`.

The probe is [`tests/load/poller.js`](../load/poller.js), one request a second. The outage is the longest run of failed requests with no success between them, and status 0 means no answer at all.

## If a drill stops halfway

- `auditor-outage.sh` restores the Auditor's task count and resumes its autoscaling on the way out, even after an error or Ctrl+C. If the shell itself was killed, do both by hand:

  ```sh
  aws application-autoscaling register-scalable-target --service-namespace ecs --scalable-dimension ecs:service:DesiredCount \
    --resource-id service/crm-sg-prod/auditor \
    --suspended-state DynamicScalingInSuspended=false,DynamicScalingOutSuspended=false,ScheduledScalingSuspended=false
  aws ecs update-service --cluster crm-sg-prod --service auditor --desired-count 2   # the minimum, ADR-0075
  ```

- The other drills leave nothing to restore: ECS replaces a stopped task, and the database stays on the new primary, which is as good as the old one.
- Each drill stops its probe container on the way out. `docker rm -f drill-probe-<run-id>` removes one left behind.
