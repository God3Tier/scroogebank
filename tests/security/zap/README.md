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

An active scan sends attack payloads (SQL injection, cross-site scripting, path traversal and more) to every operation, writes included. Run it as a dedicated test agent with its own synthetic clients, never as a user whose data matters. `active-scope.py` copies the specs without the three operations that would reach outside the CRM or sign the agent out; `--internal` adds the routes API Gateway does not publish, for the direct scan.

```sh
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W):/repo:ro" -v "$(pwd -W)/out/zap:/out" python:3.12-alpine   sh -c "pip install -q pyyaml==6.0.2 && python /repo/tests/security/zap/active-scope.py /repo/docs/api /out/active-specs          && python /repo/tests/security/zap/active-scope.py /repo/docs/api /out/direct-specs --internal"
```

**Through the edge** (CloudFront, the WAF, API Gateway), from a laptop with `scan.sh`. Keep ZAP under the API's throttle, and cap each spec's time so one token (60 minutes) covers it:

```sh
mkdir -p out/zap/edge && cp -r out/zap/active-specs out/zap/edge/specs && cp tests/security/zap/scan.sh out/zap/edge/
printf '%s' "<agent access token>" > out/zap/edge/token
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W)/out/zap/edge:/zap/wrk:rw" -e TARGET=https://sg.itsag3t6.com   -e ZAP_OPTIONS="-config scanner.threadPerHost=2 -config scanner.delayInMs=20 -config scanner.maxRuleDurationInMins=2 -config scanner.maxScanDurationInMins=10"   zaproxy/zap-stable:2.17.0 sh /zap/wrk/scan.sh
rm out/zap/edge/token
```

**Directly at the services**, past the edge, with the in-VPC scanner task (ADR-0071). Apply the crm stack with `enable_test_tooling = true` first. Without a token file the scan is anonymous, which shows each service refusing requests without a valid JWT:

```sh
SCAN=passive tests/security/zap/run-in-vpc.sh direct-anonymous out/zap/direct-specs
ZAP_OPTIONS="-config scanner.threadPerHost=4 -config scanner.maxRuleDurationInMins=2 -config scanner.maxScanDurationInMins=10"   tests/security/zap/run-in-vpc.sh direct-active out/zap/direct-specs <file holding the agent's token>
```

While the edge scan runs, the WAF's `BlockedRequests` metric (namespace `AWS/WAFV2`, region us-east-1, dimension `Rule`) counts what it stopped, per rule.
