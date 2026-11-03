# NFR Notes and Deliberate Trade-offs

Design rationale that doesn't belong in any single service's README because it applies system-wide. See each service's own README for how it individually satisfies the mandatory NFRs (Scalability, Availability, Maintainability/Extensibility).

## Deployment model: AWS in production; local database profile with Cognito security

Production runs on AWS. The services use RDS, Cognito, and Cloud Map, and production configuration is supplied by task definitions and Secrets Manager rather than local defaults.

The user-service `local` profile uses local PostgreSQL and enables Swagger while retaining Cognito provisioning, JWT validation, and Admin-only method authorization. It is not an authentication bypass. Feature 2 independently validates Cognito access tokens and enforces agent ownership of client profiles and accounts; an API Gateway authorizer is an additional edge check, not a replacement for service-side validation.

Testcontainers-backed tests still run on a developer machine (they need Docker, not the application stack), which keeps the data layer verifiable locally.

## MFA

Feature 1 requires MFA. Since [ADR-0076](../adr/ADR-0076-evaluation-accounts-without-mfa.md) the pool's MFA is optional, so that the brief's two evaluation accounts can sign in without it, and a pre token generation trigger enforces it for everyone else: API Gateway requires the `crm/api` scope, which the trigger adds only for a user with TOTP enrolled or in `MFA_EXEMPT`. The app enrols TOTP for a user who has none. Earlier, Cognito enforced MFA in the user-pool configuration alone. The user-service validates access-token type, issuer, app-client ID, signature, and time claims, while MFA enrollment and challenge enforcement remain Cognito responsibilities. The infrastructure directory is absent in this checkout, so the deployed pool's MFA setting cannot be verified here.

## NFR 12 (Data Segregation): single-tenant assumption, not built

The spec's Data Segregation NFR calls for each country/legal entity to have its own data protection policy, with data never shared across legal entities, and each application instance hosted separately per legal entity.

This project assumes a **single legal entity, single deployment**. Building real multi-tenant data partitioning (per-tenant schemas or databases, tenant-aware row-level security, per-tenant IAM boundaries, separate deployed stacks per entity) is significant additional infrastructure that isn't exercised by anything else in this spec — Appendix 2's data model has no legal-entity/country-of-operation field at all. Building it out would be speculative engineering against a requirement with no concrete shape given.

If this became a real requirement, the natural extension point is exactly what the current schema-per-service Postgres layout was already chosen for: each legal entity would get its own set of per-service databases (or its own full stack — VPC, ECS cluster, RDS instance — for stronger isolation) behind a shared or per-entity Terraform workspace, selected by a `LEGAL_ENTITY` env var already-supported at the infrastructure layer (each ECS task definition and RDS instance is parameterized by `name_prefix` in Terraform).

## One RDS instance per service

Each service gets its **own RDS instance** — `users_db` and `clients_db` are separate instances, not two databases inside one. Each service owns its data outright: separate failure domain, separate maintenance window, independent scaling, and no path for one service to read another's tables even by accident.

An earlier revision used a single shared instance to save cost. What killed it was provisioning, not purity: `aws_db_instance` creates exactly one initial database from `db_name`, and the instance sits in a private subnet unreachable from wherever Terraform runs, so every other database had to be created by hand with `psql` after every fresh apply. Closing that gap without splitting the instance would have meant either a VPC-resident Lambda-backed custom resource or running Terraform from inside the VPC — both more moving parts than simply giving each service its own instance, which the engine provisions natively.

The cost is one database instance per service instead of one shared instance, which is the honest price of removing a manual deployment step and getting real per-service isolation. Every instance holds actual CRM records, so all of them stay Multi-AZ for the mandatory Availability NFR.

## No message broker (superseded for the auditor service)

Every service serves its own synchronous REST API, and no service consumes events from another *except the auditor*, which reads audit events via a queue. A broker earns its cost when there are multiple consumers, ordering/backpressure requirements, or a need to decouple producer/consumer lifecycles — none of which apply to the rest of the system today, so this remains the default for every other service. Introducing one elsewhere would still be infrastructure for its own sake.

The auditor's queue is Amazon SQS (previously Kafka before it) — see [ADR-0004](../adr/ADR-0004-auditor-kafka-to-sqs-migration.md) for the rationale and the earlier Kafka setup this replaced.

## Resiliency scope

No retry or circuit-breaker library is applied to the HTTP calls between services, such as user-service asking account-service whether the caller may read an account: each runs inside one user request, where a failure should surface at once rather than be retried silently.

There is one service-to-service call that does use both: the user service's activity feed (`GET /api/activities`) reads the audit log from the auditor over gRPC (`AuditService.ListActivities`), as [ADR-0021](../adr/ADR-0021-audit-read-path-grpc-service-connect.md) requires. The auditor is internal only. It is reached through ECS Service Connect and is never exposed through API Gateway. Each call has a 2-second deadline and is retried once, only on `UNAVAILABLE`, when the call never reached a server and Service Connect may route the retry to another task. A Resilience4j circuit breaker (the core library, no Spring Boot starter) opens after half of the last 10 calls failed on the auditor's side and then fails fast for 30 seconds, so a down auditor does not hold request threads; caller errors do not count. Any auditor failure returns `503`, and business actions are unaffected because audit writes go through SQS. Service Connect's sidecar proxy tracks the auditor's task addresses, so the long-lived gRPC channel does not hit the stale-IP problem described below, and the auditor recycles connections every 5 minutes so they rebalance.

The self-hosted gateway that used to sit at the edge had a resilience problem of its own: Spring Cloud Gateway's Netty HTTP client pooled connections to backend tasks, and since ECS replaces task IPs on every deploy and scale event, a pooled connection could keep pointing at an address that no longer existed — an intermittent `Connection refused` against a healthy backend. It was handled by disabling pooling outright (`spring.cloud.gateway.httpclient.pool.type: DISABLED`), found by observation during end-to-end testing rather than theorised.

That concern disappeared with the migration to AWS API Gateway rather than being solved: a private integration resolves instances through Cloud Map directly, not by caching a DNS answer, and AWS owns the connection handling. Worth recording because the workaround was a real finding about the old design, not a misconfiguration.

## Rate limiting

Throttling is configured on the API Gateway stage (`throttling_rate_limit` / `throttling_burst_limit` in `infra/terraform/envs/prod/api-gateway-http.tf`). The current numbers are starting values, not derived from measured load.

This was an open gap for most of the project's life, and closing it was one of the reasons to move off the self-hosted gateway. Spring Cloud Gateway ships a `RequestRateLimiter` filter, but its standard implementation needs Redis — an ElastiCache cluster and another dependency in the request path — and the alternative of a per-instance in-memory bucket would have limited each task separately rather than globally. API Gateway does it as stage configuration with nothing to run.

Worth being precise about what the managed service does **not** bring, since an earlier revision of these notes overstated it: usage plans and API keys, AWS WAF integration, and request validation are all **REST API** features, not HTTP API ones. The HTTP API was chosen because it is the variant that integrates with Cloud Map directly; a REST API's VPC links are NLB-only, which would have meant putting a load balancer back. So the system has throttling, and does not have usage plans or WAF.

## Single-region, Multi-AZ only

RDS Multi-AZ + ECS tasks spread across 2 AZs behind an ALB satisfies the mandatory Availability NFR and the recommended 12-hour RTO/RPO targets without cross-region replication. Cross-region DR is not built — it's a substantial jump in complexity (cross-region RDS read replica promotion, Route 53 failover routing, data residency implications for a bank) that the stated RTO/RPO targets don't require.

## Edge: AWS API Gateway, not a self-hosted gateway

The single public entry point is an AWS API Gateway HTTP API (`infra/terraform/envs/prod/api-gateway-http.tf`), reached as the `/api/*` origin of the CloudFront distribution that also serves the SPA. It replaced a self-hosted Spring Cloud Gateway service running on Fargate behind an ALB.

The original argument for self-hosting was local/cloud parity — the same container routing the same way on a developer machine as in AWS. That argument died with the local runtime (see *Deployment model* above), and what remained in its favour was portability (Appendix 1 #5, "Open, Modular, Unbundled"), which was already largely notional: Cognito is load-bearing for MFA, Cloud Map for discovery, ECS for runtime. Against it was Appendix 1 #2 ("Do Less — Reuse, Build") and a concrete list of things that stopped needing to exist.

What the migration deleted: the ALB, its security group, two listeners and a target group; the `api.<domain>` Route 53 record and the second ACM certificate the ALB needed in the main region; the `api-gateway` service itself (Spring Cloud Gateway, its ECR repository, task definition and autoscaling policy); the connection-pooling workaround described under *Resiliency scope*; and the CORS configuration, which same-origin delivery makes unnecessary. JWT validation became a Cognito authorizer on the API, and each downstream service still re-validates the same token independently, so the Zero-Trust chain is unchanged.

Two bugs surfaced while validating the swap, both worth recording because neither was visible from reading the code:

- The Spring gateway **401'd CORS preflight requests**. Its `SecurityConfig` authenticated `anyExchange()`, which caught `OPTIONS` before the `CorsWebFilter` ran. It never caused an outage only because the SPA and API were already same-origin through CloudFront, so no preflight was ever sent — the `CorsConfig` bean was dead code guarding a case that would have broken if it ever arose.
- API Gateway's `{proxy+}` is **not** equivalent to Spring's `/**`. It requires at least one path segment, so a single `ANY /api/clients/{proxy+}` route 404s `GET /api/clients` — the list endpoint. Every prefix therefore needs two routes, the bare path and the proxy form. This was caught by testing the deployed API, not by review.

The remaining trade-off is vendor coupling: routing, authentication and throttling are now AWS configuration rather than portable application code. Given how much of the rest of the stack is already AWS-specific, that was judged a smaller cost than maintaining a service whose only job was to forward requests.
