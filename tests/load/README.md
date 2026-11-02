# Load tests (k6)

The k6 scripts behind the performance evidence for NFR 1 ([test strategy](../../docs/testing/test-strategy.md)). They run against a deployed stack, never against a stack someone else is using, and only read data, apart from `seed.js`. Results of each run go in a dated report under [`docs/testing/`](../../docs/testing/), the first being [load-and-zap-test-2026-11-02.md](../../docs/testing/load-and-zap-test-2026-11-02.md).

| Script | What it shows |
|---|---|
| `seed.js` | Creates synthetic clients (25 per agent by default, `CLIENTS_PER_AGENT`), one account each, through the API. Run once per deployment. |
| `smoke.js` | Every route in both journeys answers 200 once. Run before any load. |
| `dashboard-cache.js` | A repeat dashboard refresh within the 3-minute cache returns the cached answer (same `generatedAt`), and a call after it does not. Run while dashboard-service has one task. |
| `scale-out.js` | Read load ramping to `PEAK_RPS` (default 40) over `RAMP` (3m), held for `HOLD` (12m). Fails if more than 1% of requests fail or p95 is 2000 ms or more. |
| `throttle.js` | A burst at `RATE` (default 80) requests per second for 60 s, above API Gateway's 50 per second: 429 is expected, a 5xx fails. |

`lib/journeys.js` holds the request mixes: about 80% agent reads (dashboard, clients, accounts, transactions, alerts, calendar) and 20% admin reads (users, activity feed, all transactions, the agents dashboard), one request per iteration.

## Running

k6 runs from its image, so nothing is installed. Use Docker Desktop and Git Bash, from the repository root:

```sh
export BASE_URL=https://sg.itsag3t6.com          # terraform -chdir=infrastructure/terraform/stacks/crm output -raw app_url
export AGENT_TOKENS=<token>,<token>,<token>       # agents' access tokens, comma-separated
export ADMIN_TOKEN=<token>                        # an admin's access token
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

## Limits to know

- API Gateway's stage limit (50 per second, burst 100, `stacks/crm/api.tf`) caps every test. Keep `PEAK_RPS` below it unless you mean to test the limit.
- The latency threshold is the API latency alarm's 2000 ms (`stacks/crm/observability.tf`), not an agreed target: no NFR sets a number.
- A load test may trip the 5xx and latency alarms, which e-mail `alarm_email`.
- The databases are `db.t4g.micro`, which run on CPU credits, so a long run can slow down once the credits are spent.
