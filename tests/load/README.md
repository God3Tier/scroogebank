# Load tests (k6)

The k6 scripts behind the performance evidence for NFR 1 ([test strategy](../../docs/testing/test-strategy.md)). They run against a deployed stack, never against a stack someone else is using, and only read data, apart from `seed.js`. Results of each run go in a dated report under [`docs/testing/`](../../docs/testing/), the small-scale trial being [load-and-zap-test-2026-11-02.md](../../docs/testing/load-and-zap-test-2026-11-02.md) and the full-scale run [load-test-full-scale-2026-11-02.md](../../docs/testing/load-test-full-scale-2026-11-02.md).

| Script | What it shows |
|---|---|
| `seed.js` | Creates synthetic clients (25 per agent by default, `CLIENTS_PER_AGENT`), one account each, through the API. Run once per deployment. |
| `smoke.js` | Every route in both journeys answers 200 once. Run before any load. |
| `dashboard-cache.js` | A repeat dashboard refresh within the 3-minute cache returns the cached answer (same `generatedAt`), and a call after it does not. Run while dashboard-service has one task. |
| `scale-out.js` | Read load ramping to `PEAK_RPS` (default 40) over `RAMP` (3m), held for `HOLD` (12m). Fails if more than 1% of requests fail or p95 is 2000 ms or more. |
| `seed-scale.js` | The report's data volume through the API: 100 clients per agent (`CLIENTS_PER_AGENT`) with 3 accounts each (`ACCOUNTS_PER_CLIENT`), at `RATE` (30) clients a second. With 100 agents: 10,000 clients and 30,000 accounts. |
| `seed-transactions.py` | 50 transactions per account (1.5 million for 30,000 accounts), inserted by a one-off psql task through the test bucket (`enable_test_tooling`, ADR-0071). Run after `seed-scale.js`. |
| `asr-load.js` | The report's load test: 100 agents and 5 admins at once, a 1-second ramp-up and 3 loops, each user with its own token. Agent ASR: clients, a client, its accounts, an account's transactions. Admin ASR: users, activity feed. Results per ASR and per route. |
| `stress.js` | The agent ASR stepping from 100 to 400 virtual agents (`STEPS`), `STEP_HOLD` (3m) each, with `THINK` (1 s) between journeys, to find where latency and errors rise. |
| `stress-by-step.py` | Cuts a `stress.js` run's CSV (`--out csv`) at its steps: requests, median, p95, p99 and failures for each step, overall and per route. k6's own report covers only the whole run. |
| `poller.js` | One request a second for `DURATION`, for the availability drills; with `--out csv`, the outage is the run of non-200 rows. |
| `throttle.js` | A burst at `RATE` (default 300) requests per second for 60 s, above API Gateway's 200 per second (burst 400): 429 is expected, a 5xx fails. |

`lib/journeys.js` holds the request mixes: about 80% agent reads (dashboard, clients, accounts, transactions, alerts, calendar) and 20% admin reads (users, activity feed, all transactions, the agents dashboard), one request per iteration.

## Running

k6 runs from its image, so nothing is installed. Use Docker Desktop and Git Bash, from the repository root:

```sh
export BASE_URL=https://sg.itsag3t6.com          # terraform -chdir=infrastructure/terraform/stacks/crm output -raw app_url
export AGENT_TOKENS=<token>,<token>,<token>       # agents' access tokens, comma-separated
export ADMIN_TOKEN=<token>                        # an admin's access token
# or, for many users: TOKENS_FILE=/secrets/tokens.json, a file {"agents": [...], "admins": [...]}
# mounted into the container (-v <folder outside the repo>:/secrets:ro -e TOKENS_FILE)
k6() { MSYS_NO_PATHCONV=1 docker run --rm -i -v "$(pwd -W)/tests/load:/scripts" -v "$(pwd -W)/out:/out" \
  -e BASE_URL -e AGENT_TOKENS -e ADMIN_TOKEN -e PEAK_RPS -e HOLD -u "$(id -u)" grafana/k6:2.3.0 "$@"; }
k6 run /scripts/seed.js
k6 run /scripts/smoke.js
k6 run --summary-export /out/scale-out.json /scripts/scale-out.js
```

While `scale-out.js` runs, record the task counts in a second terminal, and keep recording for about 20 minutes after it ends, since scale-in waits for 15 quiet minutes:

```sh
scripts/capture-scaling.sh out/scaling 40
```

Access tokens last 60 minutes, so take fresh ones before each run. Sign in to the app as the user, open the browser console and run:

```js
JSON.parse(sessionStorage.getItem(Object.keys(sessionStorage).find(k => k.startsWith('oidc.user:')))).access_token
```

Never commit a token or an `out/` folder holding one: the summaries hold none, but check before you copy them into `docs/testing/`.

### The specified load test and the stress test

These need a token for each of their 105 users, in a tokens file kept in a folder outside the repository. Each run saves, in `out/k6/`, k6's HTML report (its web dashboard, exported when the run ends), its summary, and a CSV with one row per request. The 2026-11-03 runs used:

```sh
k6run() {  # k6run <name> <script>; the live dashboard is at http://localhost:5665 while it runs
  MSYS_NO_PATHCONV=1 docker run --rm -p 5665:5665 -u 0 \
    -e BASE_URL -e TOKENS_FILE=/secrets/tokens.json -e STEPS -e STEP_HOLD -e THINK \
    -e K6_WEB_DASHBOARD=true -e K6_WEB_DASHBOARD_HOST=0.0.0.0 -e K6_WEB_DASHBOARD_PERIOD=2s \
    -e K6_WEB_DASHBOARD_EXPORT="/out/$1.html" \
    -v "<folder holding tokens.json>:/secrets:ro" -v "$(pwd -W)/tests/load:/scripts:ro" -v "$(pwd -W)/out/k6:/out" \
    grafana/k6:2.3.0 run --summary-export "/out/$1.json" --out "csv=/out/$1.csv.gz" "/scripts/$2.js"; }
k6run asr-load-cold asr-load   # client-service's key cache empty: 5 minutes after any earlier run, or new tasks
k6run asr-load-warm asr-load   # within 5 minutes of the cold run, while the keys are still cached
k6run stress stress            # with the throttle raised (see below) and each service back at 2 tasks
python tests/load/stress-by-step.py out/k6/stress.csv.gz out/k6/stress-by-step.csv
```

Copy the summaries and `stress-by-step.csv` into the run's evidence folder under `docs/testing/evidence/`. The raw k6 CSVs are too large to commit, and the HTML reports go with the team's recordings.

## Limits to know

- API Gateway's stage limit (200 per second, burst 400, `api_throttle_rate_limit` and `api_throttle_burst_limit` in the crm stack, ADR-0070) caps every test. Keep the load below it unless you mean to test the limit; `stress.js` needs it raised for its run (`terraform apply -var api_throttle_rate_limit=1000 -var api_throttle_burst_limit=2000`, then apply again without them).
- The specified test needs 105 users. Cognito's own e-mail sender allows 50 a day, so create test users with `aws cognito-idp admin-create-user --message-action SUPPRESS`, add them to their group, and insert their `users` rows for user-service, rather than through `POST /api/users`.
- The latency threshold is the API latency alarm's 2000 ms (`stacks/crm/observability.tf`), not an agreed target: no NFR sets a number. k6 exits with code 99 when a threshold is crossed; the results are still saved.
- A load test may trip the 5xx and latency alarms, which e-mail `alarm_email`.
- The databases are `db.t4g.micro`, which run on CPU credits, so a long run can slow down once the credits are spent.
