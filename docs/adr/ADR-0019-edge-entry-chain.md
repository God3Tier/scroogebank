# ADR-0019: Edge entry: CloudFront → API Gateway HTTP API → VPC Link → internal ALB

- Status: Accepted (2026-11-01; accepted for the team by Neo Jia Wen)
- Decision date/time: 2026-10-01 21:50 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/target-architecture`
- Related issue/PR: TBD (pull request from `feat/target-architecture` to `dev`)
- Supersedes: N/A
- Superseded by: N/A

## Context

The browser needs the static frontend and the `/api/*` endpoints of several services. The spec asks for HTTPS everywhere, firewalls (NFR 8), load balancers (NFR 1), a separate presentation layer (NFR 11) and Zero Trust. The services run in private subnets with no public IPs ([ADR-0018](ADR-0018-ecs-fargate-compute.md)).

[`nfr-notes.md`](../architecture/nfr-notes.md) records an earlier design that used an API Gateway private integration resolving services through Cloud Map, after a self-hosted Spring Cloud Gateway was dropped, and states that the system has no WAF. This ADR records the target edge design, which differs.

## Decision Drivers

- Security: nothing in the VPC reachable from the internet; JWT checked before traffic enters the VPC; a web application firewall
- Load balancing and health checks across two AZs (NFR 1, NFR 2)
- One HTTPS origin for the frontend and the API (no CORS)
- Cost

## Options Considered

### Option A: public ALB in front of the services

- Pros: simplest; one fewer hop.
- Cons: exposes the services' load balancer to the internet; no JWT check or throttling at the edge.

### Option B: API Gateway HTTP API with a private integration through Cloud Map (earlier design in `nfr-notes.md`)

- Pros: no ALB to pay for (about USD 20/month); AWS manages the connection handling.
- Cons: no ALB health checks or path routing across tasks; the spec names load balancers; each service needs its own Cloud Map integration.

### Option C: API Gateway REST API

- Pros: usage plans, API keys and request validation.
- Cons: its VPC links reach only a Network Load Balancer, so an NLB is added; higher request price.

### Option D: CloudFront → API Gateway HTTP API → VPC Link → internal ALB (chosen)

- Pros: the ALB is private; API Gateway validates the Cognito JWT and throttles before the VPC; the ALB load-balances and health-checks tasks in both AZs and routes by path; CloudFront serves the frontend and the API from one origin; WAF sits on CloudFront.
- Cons: more hops and components; the ALB costs about USD 20/month.

## Decision

Option D:

1. **CloudFront** serves the frontend from a private S3 bucket through Origin Access Control and forwards `/api/*` to API Gateway. **AWS WAF** (AWS managed rule groups) is attached to CloudFront.
2. **API Gateway HTTP API** is the single public API entry point. Its JWT authorizer validates the Cognito token, and stage throttling limits traffic.
3. A **VPC Link** with network interfaces in both AZs' private app subnets forwards requests to the **internal ALB** over HTTPS.
4. The **internal ALB** routes by path to each ECS service. It accepts traffic only from the VPC Link's security group and from the security groups of the callers that cannot use ECS Service Connect: the health analysis task ([ADR-0034](ADR-0034-xf3-health-monitor-rules-and-bedrock-boundary.md)) and the transaction-ingest Lambda ([ADR-0024](ADR-0024-transaction-ingestion-sftp-lambda.md)), which call with service tokens. API Gateway has no route for `/internal/*`, so those paths are reachable only from inside the VPC. The auditor has no ALB route.
5. ACM provides the certificates (one in us-east-1 for CloudFront); Route 53 serves the custom domain if one is bought ([open item 2](../architecture/open-items.md#2-domain-name)).

## Rationale

Option D is the only option that keeps every service private, checks the JWT at the edge and again in each service, and still gives real load balancing with health checks across two AZs. The extra cost is small (ALB about USD 20/month; WAF about USD 10/month; VPC Link free).

## Consequences

### Positive

- Defense in depth: WAF, edge JWT validation and throttling, private ALB, and service-side JWT validation.
- One origin for the browser, so no CORS configuration.
- Static files cached at the edge (NFR 1).

### Negative / Trade-offs

- More components to configure and monitor than a public ALB.
- HTTP APIs have no usage plans or API keys; throttling is per stage.
- The internal ALB's HTTPS needs an ACM certificate, which needs a domain (open item 2).

## Security & Privacy Impact

No public IP in the application or database tiers. The security group chain is VPC Link → ALB → each service; the health analysis task and the ingest Lambda also reach the ALB. WAF blocks common exploits, bad inputs and known bad IPs. Tokens are validated twice (API Gateway and each service, as [ADR-0005](ADR-0005-cognito-security-across-features-1-and-2.md) already requires).

## Cost Impact

ALB about USD 20/month; WAF about USD 10/month (can be left out of staging); API Gateway and CloudFront are usage-based and small at this volume; VPC Link has no extra charge.

## Operational Impact

ALB target groups and health checks per service; API Gateway access logs and WAF logs in CloudWatch; 5xx and latency alarms ([ADR-0027](ADR-0027-observability-baseline.md)).

## Validation

- An OWASP ZAP baseline scan against staging.
- Requests without a valid JWT are rejected at API Gateway and, when sent directly, by the service.
- No service is reachable from the internet except through CloudFront and API Gateway.

## References

- [Numbered flows 1–5](../architecture/data-flow.md#numbered-flows)
- [Why each service is included: edge and identity](../architecture/system-overview.md#edge-global)
- [Network layout](../architecture/system-overview.md#network-layout)
- [`nfr-notes.md`](../architecture/nfr-notes.md) edge, rate-limiting and WAF sections (earlier design; conflicts with this ADR)
- [ADR-0005](ADR-0005-cognito-security-across-features-1-and-2.md) (extended, not superseded)

## Revision history

- 2026-10-01 21:50 +08:00: proposed.
- 2026-10-13: before acceptance, decision 4 also lets the health analysis task and the transaction-ingest Lambda reach the ALB. Neither can use ECS Service Connect, and ADR-0034 and ADR-0024 already route their service calls through the internal ALB, which the earlier wording ("only from the VPC Link's security group") did not allow.
