# Active ZAP scans, through the edge and directly at the services, 2026-11-02

The penetration test the project report asks for: "test remotely against your AWS resources and directly at your application resources. Your application can be vulnerable but protected by your AWS resources (e.g., WAF)." OWASP ZAP 2.17.0 attacked the same API twice:
- **through the edge**, from a laptop: CloudFront, the WAF, API Gateway's JWT check;
- **directly at the services**, from a ZAP task inside the VPC, at the internal ALB ([ADR-0071](../adr/ADR-0071-test-only-in-vpc-zap-scanner.md)).

This is evidence for NFR 8. The passive scans and their fixes are in the small-scale trials ([trial 1](load-and-zap-test-2026-11-02.md), [trial 2](load-and-zap-retest-2026-11-02.md)). The load test on the same deployment is [load-test-full-scale-2026-11-02.md](load-test-full-scale-2026-11-02.md).

Run by Neo Jia Wen's session (Claude Code) at `4ddcaad`, in account 727816232662, prod configuration, 2026-11-02 09:49–10:54 UTC. Synthetic data only.

## Result

| Scan | Path | Signed in | Result |
|---|---|---|---|
| Edge, active | Laptop → CloudFront → WAF → API Gateway → ALB | ZAP test agent | **No High or Medium.** 9 specs, 0 failures. The WAF blocked cross-site scripting, restricted extensions, Java deserialisation and oversized requests. Of the 17,282 requests that reached API Gateway, 17,083 were answered 4xx and none 5xx. |
| Direct, anonymous | In-VPC task → internal ALB | No | **Every `/api/*` and `/internal/*` route answered 401.** Internal-only `/aggregates/*` and `/ownership/*` have no ALB route (404). |
| Direct, active | In-VPC task → internal ALB | ZAP test agent | **No High or Medium**, with no WAF in front: no SQL injection, cross-site scripting, path traversal, command injection or deserialisation finding. 19,000 requests; services answered 18,797 with 4xx, 0 with 5xx. Only Low header alerts, which CloudFront adds at the edge. |

The application is protected in layers, and each layer holds on its own:
- **The WAF** stops known attack patterns before they cost API Gateway or a service anything.
- **API Gateway** refuses requests without a valid JWT.
- **The services check every request again** (ADR-0019, ADR-0025): token, role and ownership. They use parameterised queries and JSON-only APIs. With the edge removed, the same attacks found nothing.

## Method

- **Scope:** `active-scope.py` copied the public OpenAPI specs, writes included, and left out three operations:
  - `POST /api/engagement/drafts` (calls Bedrock);
  - `POST /api/engagement/drafts/{id}/send` (sends e-mail);
  - `POST /api/users/me/logout` (would sign the scanner out).

  The direct scan added `dashboard-aggregates.yaml`, whose routes API Gateway does not publish.
- **Identity:** a dedicated agent, `zap.agent@example.com`, with its own 5 synthetic clients, so no load-test data was touched. Each spec got a fresh token.
- **Edge:**
  - one ZAP container per spec, with 2 threads and a 20 ms delay to stay under the API's 200 requests a second;
  - 2 minutes per rule, 8 minutes per spec.
- **Direct:**
  - `run-in-vpc.sh` ran one Fargate task per spec (anonymous: one task for all specs), with 4 threads;
  - the token was an input file in the test bucket, deleted after the run, never a task environment variable;
  - the reports came back through the bucket.
- **WAF counts:** the WAF's CloudWatch metrics live in us-east-1, which the account's SCP blocks, so blocks were counted from the WAF's sampled requests (`wafv2 get-sampled-requests`).

## Through the edge

| Alert | Risk | Count | Why |
|---|---|---|---|
| Unexpected Content-Type (100001) | Low | 210 | Paths outside `/api/*` (the internal routes in the specs, and ZAP's random paths) get the app's page from CloudFront, as in the trials |
| Cross-Origin-Embedder-Policy missing (90004) | Low | 2 | Accepted, ADR-0069 decision 3 |
| Client error responses, non-storable content, cache-control, retrieved from cache, modern web app | Informational | — | Expected |

**What the WAF blocked**, from its sampled requests during the scan, weighted to estimate counts:

| Rule group: rule | Blocked (est.) |
|---|---|
| Common rule set: RestrictedExtensions_QUERYARGUMENTS | 233 |
| Common rule set: CrossSiteScripting_BODY | 119 |
| Common rule set: CrossSiteScripting_QUERYARGUMENTS | 104 |
| Known bad inputs: JavaDeserializationRCE_BODY | 99 |
| Known bad inputs: JavaDeserializationRCE_QUERYSTRING | 53 |
| Common rule set: SizeRestrictions_QUERYSTRING | 49 |

The WAF has **no SQL-injection rule group**, so ZAP's SQL-injection payloads went through to the services. The direct scan shows the services were not injectable (below). Adding `AWSManagedRulesSQLiRuleSet` would block them at the edge too (open item 1).

## Directly at the services

Without a token, the services refused everything themselves:

| Alert | Risk | Where | Why |
|---|---|---|---|
| Client error response (100000) | Info | Every `/api/*` and `/internal/v1/*` route: **401** | The services check the JWT themselves, so bypassing API Gateway's check gains nothing |
| Server leaks version (10036), `awselb/2.0` | Low | `/aggregates/*`, `/ownership/*`: 404 from the ALB | These internal routes have no ALB listener rule; the ALB answers |
| Strict-Transport-Security not set (10035) | Low | accounts, clients, transactions, aggregates | HSTS is added by CloudFront's header policy (ADR-0069), not by each service |

Signed in as the test agent, the active scan's alerts were:

| Alert | Risk | Count | Why |
|---|---|---|---|
| Strict-Transport-Security not set (10035) | Low | 18 | Set at CloudFront |
| Cross-Origin-Resource-Policy missing (90004) | Low | 9 | Set at CloudFront |
| Server leaks version (10036) | Low | 6 | The ALB's own 404 for unrouted paths |
| X-Content-Type-Options missing (10021) | Low | 1 | Set at CloudFront |
| Client error responses, non-storable content, cache-control | Info | — | Expected (made-up IDs, an agent's token) |

No injection or code-execution rule fired against any route; that includes the routes whose SQL-injection payloads the WAF does not block.

## Defects

None found. The Low alerts on the direct path are headers the edge sets; the services sit behind it in every deployment.

## Open items

1. **The WAF has no SQL-injection rule group.** The services are not injectable, but `AWSManagedRulesSQLiRuleSet` would stop those payloads at the edge, before they cost a service anything (about USD 1 a month). It is a WAF design change for its owner.
2. **The WAF's metrics cannot be read** in this account: the SCP denies CloudWatch in us-east-1, where CloudFront-scope WAF metrics live. Sampled requests still work.
3. **`scan.sh` ran specs back to back in one container**, and every other spec failed while the previous ZAP was still shutting down. Fixed during the run (a port per spec, waiting for the previous ZAP to exit), and the 4 affected anonymous specs were re-run.

## Evidence

[`evidence/2026-11-02-full-scale/zap/`](evidence/2026-11-02-full-scale/zap/):
- the ZAP reports for each spec (`edge/`, `direct-anonymous/`, `direct-active/`);
- `waf-sampled-blocks.csv`;
- `edge-and-direct-counts.txt` (API Gateway and ALB response counts).

Screen recordings of the WAF's sampled requests are kept outside the repo with the report.
