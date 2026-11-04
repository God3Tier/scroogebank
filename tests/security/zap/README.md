# OWASP ZAP scans

The OWASP ZAP scans behind the security evidence for NFR 8 ([test strategy](../../../docs/testing/test-strategy.md)). The baseline and safe-mode API scans are passive: ZAP only reads the responses to the requests it makes. The active scans below send attack payloads. Run any of them only against your own deployment. Results of each run go in a dated report under [`docs/testing/`](../../../docs/testing/), the first being [load-and-zap-test-2026-11-02.md](../../../docs/testing/load-and-zap-test-2026-11-02.md).

| Scan | Target | What it covers |
|---|---|---|
| Baseline | The app's URL, without signing in | The frontend and edge: CloudFront's and S3's headers, cookies, TLS redirects, what the spider can reach |
| API (safe mode) | Every GET operation in the public specs, with an agent's token | Each service's responses through API Gateway: headers, content types, error bodies, information leaks. The internal routes are included to show they are not reachable from outside |

ZAP's OpenAPI import sends a request for every operation, POST and DELETE included, so the API scan uses GET-only copies of the specs, made by `get-only.py`.

## Running

From the repository root in Git Bash, with Docker Desktop running:

```sh
APP=https://sg.itsag3t6.com     # terraform -chdir=infrastructure/terraform/stacks/crm output -raw app_url
mkdir -p out/zap/specs
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W):/repo:ro" -v "$(pwd -W)/out/zap/specs:/out" python:3.12-alpine \
  sh -c "pip install -q pyyaml==6.0.2 && python /repo/tests/security/zap/get-only.py /repo/docs/api /out"
zap() { MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W)/out/zap:/zap/wrk:rw" "$@"; }

# Baseline
zap zaproxy/zap-stable:2.17.0 zap-baseline.py -t "$APP" -I -r baseline.html -w baseline.md -J baseline.json

# API, one spec at a time, signed in as an agent
export ZAP_AUTH_HEADER_VALUE="Bearer <agent access token>"
for s in out/zap/specs/*.yaml; do
  n=$(basename "$s" .yaml)
  zap -e ZAP_AUTH_HEADER_VALUE -e ZAP_AUTH_HEADER_SITE="${APP#https://}" zaproxy/zap-stable:2.17.0 \
    zap-api-scan.py -S -I -f openapi -t "/zap/wrk/specs/$n.yaml" -O "$APP" -r "api-$n.html" -w "api-$n.md" -J "api-$n.json"
done
```

`-S` is safe mode: no active scan. `-I` reports warnings without failing, since the report decides what each one means. The token lasts 60 minutes; see [the load tests' README](../../load/README.md#running) for how to get one.

Each warning in a report gets an action in the dated report: fixed, accepted with a reason, or a false positive with the evidence.

## Active scans, through the edge and directly

An active scan sends attack payloads (SQL injection, cross-site scripting, path traversal and more) to every operation, writes included. These scripts reproduce the scans of 2026-11-02 ([report](../../../docs/testing/zap-active-and-direct-2026-11-02.md)) and the edge re-scan of 2026-11-03 ([report](../../../docs/testing/retest-2026-11-03.md)). Run them only against your own deployment.

| Script | What it does |
|---|---|
| `active-scope.py` | Copies the specs without the three operations that would reach outside the CRM or sign the agent out. `--internal` adds the routes API Gateway does not publish, for the direct scan |
| `edge.sh` | Active scan through CloudFront, the WAF and API Gateway: one ZAP container per spec, each with a fresh token, kept under the API's throttle |
| `direct.sh` | From the in-VPC scanner task (`run-in-vpc.sh`, ADR-0071), past the edge: an anonymous passive scan of every spec, then an active scan as the agent, one task per spec |
| `waf-blocks.py` | What the WAF blocked during an edge scan, per rule |

The test agent, `zap.agent@example.com`, comes from [`tests/test-users.py`](../../test-users.py), which also makes the load tests' users and is shared with the [availability drills](../../drills/README.md).

### 1. Prepare, once per deployment

The scans run as a dedicated agent with its own synthetic clients, never as a user whose data matters. `tests/test-users.py` signs it in with a password and a TOTP code, which needs the `ALLOW_ADMIN_USER_PASSWORD_AUTH` flow on the web client. The stack leaves that off, since people sign in only through managed login (ADR-0025), so add it for the test only, in a file that is never committed:

```sh
cat > infrastructure/terraform/stacks/crm/zz_test_auth_override.tf <<'TF'
# Test-only: lets tests/test-users.py sign in. Never commit it; delete it before teardown.
resource "aws_cognito_user_pool_client" "web" {
  explicit_auth_flows = ["ALLOW_REFRESH_TOKEN_AUTH", "ALLOW_ADMIN_USER_PASSWORD_AUTH"]
}
TF
echo infrastructure/terraform/stacks/crm/zz_test_auth_override.tf >> .git/info/exclude
```

Then plan and apply the crm stack, with `enable_test_tooling = true` in `env/prod.tfvars` if you will run `direct.sh`. Keep the agent's password, TOTP secret and tokens in a folder outside the repository (`SECRETS` below). For the scans alone, the agent is enough (`--agents 0 --admins 0`); without those options the load tests' 105 users are made too:

```sh
SECRETS=<folder outside the repository>
python tests/test-users.py create --store "$SECRETS/users.json" --agents 0 --admins 0   # with its user-service row

# Its 5 synthetic clients, one account each. seed.js names clients differently
# from seed-scale.js, so they do not clash with the load-test agents' clients.
python tests/test-users.py token --store "$SECRETS/users.json" --out "$SECRETS/zap.token"
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W)/tests/load:/scripts:ro" \
  -e BASE_URL="$(terraform -chdir=infrastructure/terraform/stacks/crm output -raw app_url)" \
  -e AGENT_TOKENS="$(cat "$SECRETS/zap.token")" -e CLIENTS_PER_AGENT=5 grafana/k6:2.3.0 run /scripts/seed.js

# The specs to attack
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W):/repo:ro" -v "$(pwd -W)/out/zap:/out" python:3.12-alpine \
  sh -c "pip install -q pyyaml==6.0.2 && python /repo/tests/security/zap/active-scope.py /repo/docs/api /out/active-specs \
         && python /repo/tests/security/zap/active-scope.py /repo/docs/api /out/direct-specs --internal"
```

### 2. Scan

Each script gets a fresh token as it goes, because a token lasts 60 minutes and a full scan takes longer.

```sh
# Through the edge, every public spec (as on 2026-11-02), up to 8 minutes a spec
tests/security/zap/edge.sh edge "$SECRETS/users.json"
# or only some specs (the 2026-11-03 re-scan)
tests/security/zap/edge.sh edge-rescan "$SECRETS/users.json" \
  out/zap/active-specs/clients-api.yaml out/zap/active-specs/accounts-api.yaml

# What the WAF blocked during an edge scan: within 3 hours, before AWS drops its samples
python tests/security/zap/waf-blocks.py $(cut -d' ' -f2 out/zap/edge/window.txt) out/zap/edge

# Directly at the services, anonymous then active (needs enable_test_tooling)
tests/security/zap/direct.sh direct "$SECRETS/users.json"
```

Each spec's report comes out as HTML, Markdown and JSON:
- `edge.sh` writes to `out/zap/<run-id>/`;
- `direct.sh` writes to `out/zap/<run-id>-anonymous/` and `out/zap/<run-id>-active-<spec>/`.

Copy the JSON and Markdown reports and the WAF CSVs into the run's evidence folder under `docs/testing/evidence/`. Each alert gets an action in the dated report.

### 3. Clean up

Delete `zz_test_auth_override.tf` and its line in `.git/info/exclude`, set `enable_test_tooling` back to false, and apply. Then delete the `SECRETS` folder. The agent and its clients go with the deployment.

### What to expect

- The WAF's CloudWatch metrics live in us-east-1. If the account cannot read CloudWatch there, `waf-counts.csv` is empty, and the sampled blocks are the evidence.
- In an edge scan, ZAP reports SQL Injection on some write operations. These are false positives: the WAF's SQL-injection rule (ADR-0073) answers 403 to one of ZAP's two boolean probes, and ZAP reads the difference as an injection. The direct scan, with no WAF, finds none. See the 2026-11-03 report.
