# AI usage log

## 2026-10-20 — Activity feed timeout, page names, dashboard budgets, accounts Pending until verified

- Human owner: Neo Jia Wen.
- Tool: Claude Code (Anthropic).
- Use: four changes on four branches, all from `dev`.
  - `task/base-features/auditor-channel-warm-up`: read the owner's
    CloudWatch line for the activity feed failing on first load
    (`DEADLINE_EXCEEDED`, about 1.8 s of the 2 s deadline spent
    connecting) and make user-service connect to the auditor at startup.
  - `task/frontend/page-names`: rename Agent/Admin Dashboard to
    Agent/Admin Home, and Agent/Admin Insights to Agent/Admin Dashboard.
  - `task/business-dashboard/longer-upstream-budgets`: raise
    dashboard-service's budgets to 10 s per call and 15 s overall, which
    the owner chose, and amend ADR-0033.
  - `task/base-features/verified-clients-only-accounts`: draft ADR-0050.
    A first version refused an agent's account for an unverified client.
    Checking the specification and the requirements Q&A showed no such
    rule, the Q&A's "doc submitted, account opened, account active"
    workflow, and that admins do not manage client accounts (UM18). The
    owner then chose: an unverified client's accounts open as Pending; after
    verifying, the agent may make them Active; a new
    `PATCH /api/accounts/{id}` lets the owning agent change a verified
    client's account to any status, never an admin, audited with the status
    before and after. Implemented in account-service and on the client and
    accounts pages.
- Human verification required: decide ADR-0050 and tell Joseph Yau
  (Feature 2) and the frontend owner. In AWS, check that the activity feed
  loads on the first visit after a deploy with no `DEADLINE_EXCEEDED`, that
  the dashboards load after a restart with no `exceeded its budget`, and,
  after deploying account-service and the frontend together, that an agent
  can open only a Pending account for an unverified client, activate it
  after verifying, change its status on the accounts page, and that an
  admin's `PATCH` returns 403.
- Data: repository content, the owner's local copies of the specification
  and the requirements Q&A (read, not committed), and one CloudWatch log
  line the owner pasted, with no client data. No secrets or real client
  data were used.
- Verification: user-service's 83 tests pass, dashboard-service's 105,
  account-service's `make test` (including the new repository test against
  PostgreSQL) and `go vet`, and the frontend's tests, lint and build on
  both frontend branches. The new tests for the auditor channel, each
  account-service rule (unverified, admin, the Pending exception) and each
  frontend rule (the form's statuses, the page's control, the admin prompt)
  fail without their change.

## 2026-10-20 — INF-03 least-privilege database logins, and stale statuses

- Human owner: Neo Jia Wen.
- Tool: Claude Code (Anthropic).
- Use: work out what each service does in its database (no DDL, `TRUNCATE`
  or sequences; soft deletes are updates; transaction-service only reads);
  recommend that db-migrate apply user-service's and event-alert-service's
  migrations in AWS only, with their own Flyway off there and their code
  unchanged, which the owner chose; add login callbacks to the db-migrate
  image, with an advisory lock against concurrent grants; give every
  service, and the ingest Lambda, its own login secret in the CRM stack,
  with tests; amend ADR-0028 decision 3 and ADR-0042 decision 6. Also
  update the docs that still said INF-03, INF-08, INF-12 and INF-13 were
  not applied to AWS, using only what the owner confirmed had worked there.
- Human verification required: decide ADR-0028 and ADR-0042; build and
  push db-migrate with the other images, apply crm to the running stack,
  and check that the users and alerts `db-migrate` streams say "up to
  date", every service is healthy, no log group shows `permission denied`
  or `password authentication failed`, and the ingest Lambda imports the
  next file. Tell Azeez (event-alert-service) and Joseph Yau (the receiver
  and the ingest Lambda).
- Data: repository content only, with throwaway passwords in local Docker
  containers. Nothing ran in AWS, and no secrets or real client data were
  used.
- Verification: `terraform fmt -check` is clean; `terraform validate` passes
  for all four stacks; `terraform test` passes 7/7 in `stacks/crm`, 3/3 in
  `stacks/business-dashboard` and 8/8 in `modules/ecs-service`, and the CRM
  test fails when a service is given its master secret. The image, run
  against PostgreSQL 17 with passwords checked from another container:
  each login signed in, could change rows but not the schema, its history
  or roles, and lost its old password on a rerun; `transaction_service`
  could only read; `transaction_ingest` ran the receiver's upsert and outbox
  relay; a later table was granted on the next migrate; three concurrent
  migrations succeeded and the lock serialised the callbacks; a database
  migrated with Spring Boot's `__` separator was up to date with it and
  reported a description mismatch without it.

## 2026-10-20 — INF-14 redeploy on start for Service Connect

- Human owner: Neo Jia Wen.
- Tool: Claude Code (Anthropic).
- Use: find why a new stack's services needed a restart after their first
  start (AWS gives a task the Service Connect names that existed when its
  service's latest deployment was made, and scaling up makes no new
  deployment); show that creating called services first cannot fix it,
  because client-service and account-service call each other; recommend
  redeploying each service when its minimum task count changes, which the
  owner chose; add the trigger and a module test; amend ADR-0042 decision 4,
  and document in the runbook that every stack must be created stopped.
- Human verification required: decide ADR-0042; create a new stack stopped,
  start it, and check that no log group shows `UnknownHostException`
  without a restart.
- Data: repository content and the AWS ECS developer guide only. Nothing ran
  in AWS, and no secrets or real client data were used.
- Verification: `terraform fmt -check` is clean for the modules;
  `terraform validate` passes for the crm and business-dashboard stacks;
  `terraform test` passes 7/7 in `stacks/crm`, 3/3 in
  `stacks/business-dashboard` and 7/7 in `modules/ecs-service`, and the new
  module test fails when the trigger is removed.

## 2026-10-20 — INF-03 db-migrate init container

- Human owner: Neo Jia Wen.
- Tool: Claude Code (Anthropic).
- Use: find where the runbook uses `scripts/db-run-sql.sh` and how to stop
  needing it for deploys; recommend a Flyway `db-migrate` init container in
  each Go service's task over a separate migration task (the owner chose it,
  and to collapse client-service's two SQL files into one because nothing is
  deployed); build one `infrastructure/db-migrate` image from the Go services'
  `data/` directories, with an `afterMigrate.sql` callback that creates the
  auditor's and chain-verify's logins from Secrets Manager placeholders; add
  the `migrations` input to the `ecs-service` module and wire it into the CRM
  stack; run the same image against each Go service's local Compose database
  (`make migrate`, used by `make run`, and the SFTP stack's `db-migrate`
  service before `receiver-api`), as the owner asked, so an edited migration
  fails locally first; drop `--expand-env` and the audit console's password
  secrets; write module and stack tests; amend ADR-0028 decision 3
  (resolving open item 8) and ADR-0042 decision 6, and update the runbook,
  the four services' READMEs, the architecture pages,
  `docs/database/migrations.md`, the Terraform README, the infrastructure plan
  and the resource register.
- Human verification required: decide ADR-0028 and ADR-0042; tell the
  client-service, account-service, auditor and receiver owners that a deployed
  migration is never edited, and that an existing local database needs one
  `make db-reset`; on a new stack, build and push the db-migrate image,
  apply crm, and check each of the four services' `db-migrate` log
  streams and that the auditor connects as `auditor_app`.
- Data: repository content and synthetic values only. Local PostgreSQL
  containers, synthetic passwords and a throwaway CA were used for the checks.
  Nothing ran in AWS, and no secrets or real client data were used.
- Verification: `terraform fmt -check` is clean for the tracked files;
  `terraform validate` passes for the crm, business-dashboard and sftp-server
  stacks; `terraform test` passes 7/7 in `stacks/crm`, 3/3 in
  `stacks/business-dashboard` and 5/5 in `modules/ecs-service`, and the new
  crm assertion fails when a service loses its migrations. The image, run
  against PostgreSQL 17: each database migrated from empty and a rerun changed
  nothing; the audit logins worked with their roles and a rerun replaced their
  passwords; a missing placeholder and an edited migration both stopped it;
  a database set up by hand was refused; LF and CRLF copies of a file gave the
  same checksum; `verify-full` worked with a two-CA bundle and refused a wrong
  CA and a wrong host name. Locally, `make migrate` in account-service,
  client-service and the auditor, and `make db-up` in the SFTP stack, migrated
  fresh Compose databases and changed nothing on a rerun; on account-service,
  a database loaded with `psql` and an edited migration were both refused
  with the Makefile's hint. The test containers and volumes were removed. `db-run-sql.sh` built the same psql override with
  stubbed `terraform` and `aws` commands.

## 2026-10-20 — INF-08 HTTPS from the internal ALB to the Java services

- Human owner: Neo Jia Wen.
- Tool: Claude Code (Anthropic).
- Use: list which services the internal ALB reaches and over what; compare
  where dashboard-service's certificate could come from (the owner chose a
  self-signed one in its own stack over one issued by the CRM's CA); make the
  `ecs-service` module's Java `tls-init` script write the service's
  certificate and set Spring Boot's `SERVER_SSL_CERTIFICATE` and
  `SERVER_SSL_CERTIFICATEPRIVATEKEY`; give user-service and event-alert-service
  project-CA certificates and the dashboard a self-signed one; switch the three
  target groups to HTTPS and user-service's Service Connect port to TCP; write
  the module's first plan test and extend the stacks' tests; amend ADR-0042
  decisions 5, 7 and 8 and ADR-0047, and update the runbook, the Terraform
  README, the infrastructure plan and the resource register.
- Human verification required: decide ADR-0042 and ADR-0047; apply the CRM
  stack, then the business-dashboard stack (after `terraform init` there, for
  the new `tls` provider); check that all five target groups use HTTPS and are
  healthy, the users, alerts and dashboard pages load, and the dashboard's
  user-service panels work, which proves the dashboard checks user-service's
  certificate.
- Data: repository content and synthetic test values only. A throwaway EC key
  and self-signed certificate were made in the session's scratch folder for
  the local checks. Nothing ran in AWS, and no secrets or real client data
  were used.
- Verification: `terraform fmt -check` is clean for the tracked files;
  `terraform validate` passes for the module and the crm, business-dashboard
  and sftp-server stacks; `terraform test` passes 7/7 in `stacks/crm`, 3/3 in
  `stacks/business-dashboard` and 2/2 in `modules/ecs-service`. Spring Boot
  3.5.16 read the `EC PRIVATE KEY` format Terraform writes and served TLS 1.3
  from those two variables: a locally built dashboard-service jar, and the
  existing dashboard-service image after the module's Java `tls-init` script
  ran in it as root, with the app then running as a non-root user. Both
  answered `UP` on HTTPS with the certificate's name checked, and 400 on plain
  HTTP.

## 2026-10-20 — INF-13 custom domain and HTTPS internal ALB

- Human owner: Neo Jia Wen.
- Tool: Claude Code (Anthropic).
- Use: check which code has no deployment and where the Terraform README was
  out of date; explain the difference between HTTPS to the internal ALB and
  INF-08's TLS in the Java services; add `domain_name` to the CRM stack (ACM
  certificates for CloudFront and the internal ALB, DNS validation and host
  records in a hand-registered Route 53 zone, the ALB on HTTPS 443 verified by
  API Gateway, the ingest Lambda calling it by name, Cognito callbacks on the
  custom domain); write its mocked plan tests; amend ADR-0042 decisions 8 and
  9, resolve open item 2, and update the runbook, the Terraform README, the
  system overview, the infrastructure plan and the resource register.
  The runbook's step 3 sets `domain_name = "itsag3t6.com"` only in an account
  that has that domain's hosted zone, so a personal account deploys without one.
- Human verification required: decide ADR-0042; in the project account, apply
  the CRM stack with `domain_name = "itsag3t6.com"`, then the
  business-dashboard stack; check that both certificates are issued, the ALB
  listens on HTTPS 443, the app signs in and loads at
  `https://sg.itsag3t6.com`, and an ingest run reaches account-service; in a
  personal account, check that step 3 writes no `domain_name` and the stack
  deploys on HTTP and `*.cloudfront.net`.
- Data: repository content and synthetic test values only. To see what was
  deployed, the git-ignored local tfvars files, the bootstrap stack's local
  state (resource types only) and the local Docker image list were read, with
  account IDs and e-mail addresses masked in the output; an SSH public key was
  shown. An AWS CLI call stopped at the MFA prompt, so no AWS API was called,
  and no secrets or real client data were used. That the ingest Lambda imports
  files in AWS is the owner's statement, not observed. Adding `domain_name` to
  the git-ignored `crm/env/prod.tfvars`, a `terraform fmt -write=false` syntax
  check printed that whole file unmasked: the operators' two e-mail addresses,
  the account ID, and the SFTP server's address and host key fingerprint. It
  holds no passwords or keys; nothing was copied elsewhere. The zone lookup's
  JMESPath query was tested offline against sample responses.
- Verification: `terraform fmt -check` is clean for the CRM stack;
  `terraform validate` passes for the crm, business-dashboard and sftp-server
  stacks; `terraform test` passes 7/7 in `stacks/crm` (the new `custom_domain`
  run and a no-domain check in `prod`) and 3/3 in
  `stacks/business-dashboard`. Nothing ran in AWS.

## 2026-10-20 — INF-12 business-dashboard stack

- Human owner: Neo Jia Wen.
- Tool: Claude Code (Anthropic).
- Use: compare ways of passing the CRM stack's values to a separate stack
  (remote state, copied tfvars, lookups by name, an SSM parameter); move
  dashboard-service from `stacks/crm` into a new `stacks/business-dashboard`
  that reads the CRM's new `dashboard_inputs` output, owns its image
  repository, security group and rules on the CRM's service groups, and stops
  its plan when the audit queue does not allow its role; write its mocked
  plan tests; draft ADR-0047, amend ADR-0042, and update the runbook, the
  Terraform README, the infrastructure and business-dashboard plans, the
  resource register and `scripts/aws-session.sh`. Also ignore the local
  `references/` folder.
- Human verification required: decide ADR-0047; on the next session, apply
  the CRM stack with the dashboard's role in `extra_audit_producer_role_arns`,
  then the business-dashboard stack, and check that `/api/dashboard/*` returns
  panels for an agent and an admin and that its audit events reach the queue.
- Data: repository content and synthetic test values only. No AWS credentials,
  secrets, real client data or live AWS resources were used. `terraform fmt
  -check` printed one line each from the git-ignored real `env/prod.tfvars`
  files (an image URI with the account ID, and the SFTP server's address);
  they were not changed or copied.
- Verification: `terraform fmt -check` is clean for every tracked file;
  `terraform validate` passes for the crm, business-dashboard and sftp-server
  stacks; `terraform test` passes 6/6 in `stacks/crm` and 3/3 in
  `stacks/business-dashboard`, whose failing-precondition run confirms the
  audit check. Nothing ran in AWS.

## 2026-10-16 — INF-05 transaction-service image

- Human owner: Neo Jia Wen; the Feature 3 owner (Joseph Yau) to confirm, as the
  infrastructure plan lists.
- Tool: Claude Code (Anthropic).
- Use: make the SFTP receiver's Dockerfile multi-stage, its default target a
  distroless transaction-service image built from `src/serverd` as
  account-service's is, with the development image kept as the `tools` target
  for the compose file; generate the receiver's ORM for the image in a
  throwaway database; add transaction-service to the runbook's build, push and
  check steps; amend ADR-0042 decision 9; replace the hand-written Secrets
  Manager fake in the INF-04 tests with a mockery mock.
- Human verification required: build the image with Docker after
  `make -C services/sftp sqlboiler`, run `make -C services/sftp test`, and check
  in a test account (INF-02) that transaction-service starts and user-service's
  transaction reads succeed.
- Data: repository content and synthetic test values only. No AWS credentials,
  secrets, real client data or live AWS resources were used.
- Verification: with Docker, `make sqlboiler` generated the ORM in a throwaway
  database and `make test` passed, the database-backed tests included. The
  image built for linux/amd64, runs as the distroless nonroot user, and against
  the test database answered `/health` and `/api/transactions` with 200.
  `make lambda` built a zip holding an executable arm64 `bootstrap` and the
  RDS CA bundle; in the `provided.al2023` runtime emulator it initialised, ran
  one poll per invocation and returned the poll's error. This also covers the
  INF-04 checks that entry left for Docker. Nothing ran in AWS.

## 2026-10-16 — INF-04 transaction-ingest Lambda

- Human owner: Neo Jia Wen; the Feature 3 owner (Joseph Yau) confirms the
  runtime (ADR-0024 decision 7) on the pull request.
- Tool: Claude Code (Anthropic).
- Use: run the SFTP receiver's poller as the Lambda function ADR-0024 describes:
  a Lambda mode in `src/cronjob` that builds the poller once per execution
  environment and polls once per invocation; read the database login, SSH
  private key and Cognito client secret from Secrets Manager at init; quote the
  database login in the connection string; add `make lambda` to build the arm64
  `bootstrap` zip with the RDS CA bundle; in Terraform, point `DB_SSLROOTCERT`
  at that bundle and turn off Lambda's own retries of the scheduled run; update
  the receiver README, the runbook, the Terraform README and the
  infrastructure plan; amend ADR-0042 decisions 9 and 10 to match.
- Human verification required: review the code; run `make test` and
  `make lambda` with Docker; apply to a test account (INF-02) and check one
  invocation imports files and audits them.
- Data: repository content and synthetic test values only. No AWS credentials,
  secrets, real client data or live AWS resources were used.
- Verification: `go vet` and the cronjob, accounts and poller tests passed
  locally, and the arm64 `bootstrap` cross-compiled; the database-backed SQS
  relay tests were not run (no database or Docker). Terraform validate and 6
  CRM mocked-provider tests passed. `make lambda` was not run (Docker was not
  running).

## 2026-10-16 — Terraform review and repair

- Human owner: Neo Jia Wen.
- Tool: OpenAI Codex.
- Use: inspect current remote branches; repair a committed ADR-index merge
  conflict; preserve and mark the abandoned Free-plan design as superseded;
  add reproducible Terraform provider locks; replace long-lived administrator
  access-key guidance with temporary-credential guidance; correct cross-region
  RDS backup encryption; restrict production OAuth callbacks and direct browser
  auth flows; add CloudTrail/frontend TLS controls, CloudTrail versioning and
  redacted WAF request logging; synchronize cost and resource documentation.
- Human verification required: review the new Sydney KMS-key cost and policy,
  approve the proposed ADRs, run Checkov in CI, inspect a saved real-account
  staging plan, and complete INF-02 before any production apply.
- Data: repository content and synthetic configuration only. No AWS credentials,
  secrets, real client data or live AWS resources were used.
- Verification: Terraform 1.16.6 formatting and validation passed for bootstrap,
  CRM and SFTP roots; 2 bootstrap and 6 CRM mocked-provider tests passed.
  Docker was unavailable and the configured secondary Python package index was
  unreachable, so Checkov did not run; no live plan/apply or cost evidence is
  claimed.

## 2026-09-27 — EDA-D1-01 AgentCore Web Search spike

- Human owner: Azeez
- Tool: OpenAI Codex
- Use: repository/branch inspection, official AWS documentation verification,
  disposable spike-harness implementation, tests, IAM/teardown notes, cost record,
  and feature-plan status update.
- Human verification required: review the code and policies; supply approved
  short-lived AWS development credentials; run only in the lower environment;
  inspect requests/results; verify billing data and teardown; accept any later ADR
  before production-shaped work.
- Data: repository content and synthetic public-market example terms only. No
  secrets, real client data, or confidential bank data were supplied.
- Limitation: the configured AWS credentials were invalid, so Codex did not create
  resources or claim live latency, search quality, or billed-cost evidence.

## 2026-09-27 — EDA-D2-01/D2-02 contracts and service foundation

- Human owner: Azeez; Hayyun review remains required for the CRM/domain scope.
- Tool: OpenAI Codex.
- Use: contract and schema drafting, Java 25 Spring Boot service scaffolding,
  Flyway migration design, JPA repositories, lifecycle rules, synthetic fixture
  loading, PostgreSQL Testcontainers tests, documentation, and Git integration.
- Human verification required: Hayyun reviews the domain/API ownership changes and
  both developers accept the proposed ADRs before promotion to `dev`.
- Data: repository content and synthetic identifiers/holdings only. No secrets,
  real client data, confidential bank data, or AWS services were used.
- Verification: local Maven build and seven service tests passed against a
  disposable PostgreSQL 16 Testcontainer.

## 2026-09-27 — EDA-D2-03 holdings and deterministic scoring

- Human owner: Azeez; Hayyun review remains required for the CRM/domain scope.
- Tool: OpenAI Codex.
- Use: synthetic agent-scoped holdings adapter, freshness policy, fixed-decimal
  exposure calculations, versioned materiality scoring, failure-state design,
  tests, documentation, and proposed ADR-0015.
- Human verification required: review and calibrate prototype weights/thresholds,
  confirm agent-scope behavior, and accept ADR-0005/ADR-0015 before promotion to
  `dev`.
- Data: repository content and synthetic positions only. No secrets, real client
  data, external providers, or AWS services were used.
- Verification: 21 local event-alert tests passed, including agent isolation,
  freshness boundaries, multi-currency math, replay equality, stale penalties,
  and fail-closed cases.

## 2026-09-27 — EDA-D2-04 alert mutation API and authorization

- Human owner: Azeez; Hayyun review remains required for the CRM/domain scope.
- Tool: OpenAI Codex.
- Use: decision/feedback REST mutations, JWT role mapping, assigned-agent
  ownership enforcement, optimistic concurrency, idempotency, redacted audit
  outbox writes, failure responses, tests, and contract documentation.
- Human verification required: Hayyun reviews the API/security behavior and both
  developers accept the proposed authorization/audit ADRs before promotion to
  `dev`.
- Data: repository content and synthetic identities/alerts only. No secrets,
  real client data, external providers, or AWS services were used.
- Verification: the full Maven build passed with 39 event-alert tests, including
  Flyway migration, JPA schema validation, repository joins/authorization, and
  mutation transactions against disposable PostgreSQL 16 Testcontainers.

## 2026-09-30 — EDA-D2-04 explicit decision-state contract

- Human owner: Azeez; Hayyun review remains required for the CRM/domain scope.
- Tool: OpenAI Codex.
- Use: exposed the persisted previous and new alert states in decision responses,
  synchronized the OpenAPI and narrative contracts, and extended the controller
  contract test so clients do not need to infer lifecycle transitions.
- Human verification required: Hayyun reviews the API behavior and both
  developers accept the proposed authorization/audit ADRs before promotion to
  `dev`.
- Data: repository content and synthetic identities/alerts only. No secrets,
  real client data, external providers, or AWS services were used.
- Verification: the full 92-test Maven reactor build passed with no failures or
  errors, including disposable PostgreSQL 16 Testcontainers coverage.

## 2026-09-28 — EDA-D1-03 offline ingestion core

- Human owner: Azeez.
- Tool: OpenAI Codex.
- Use: provider-neutral public-search contracts, grouped-query controls,
  watermark and lease persistence, canonical URL governance and deduplication,
  bounded retries, durable partial-run replay accounting, AgentCore adapter
  boundary, worker-mode guard, tests, and documentation.
- Human verification required: review the provider contract and source controls;
  accept the proposed processing/search/content-governance ADRs; supply the
  separately approved IAM/Gateway implementation only after EDA-D1-02 clears.
- Data: repository content and synthetic public-market terms only. No credentials,
  client data, external provider calls, or AWS services were used.
- Verification: the full Maven build passed with 88 tests, including retry,
  deduplication, durable replay accounting, and PostgreSQL 16 Testcontainers
  schema validation. Live AgentCore behavior, latency, cost, and IAM enforcement
  are not claimed.

## 2026-09-30 — EDA-D1-03 bounded ingestion replay

- Human owner: Azeez.
- Tool: OpenAI Codex.
- Use: explicit historical replay windows, worker-mode isolation, future/span
  validation, idempotent run reuse, durable result accounting, watermark safety,
  tests, and documentation.
- Human verification required: review the replay span and connect this internal
  boundary only through the future authenticated, audited operator workflow.
- Data: repository content and synthetic public-market terms only. No secrets,
  real client data, provider calls, or AWS services were used.
- Verification: the full Maven build passed with 92 tests, including bounded,
  future-window, worker-mode, durable-count, and watermark-regression cases plus
  PostgreSQL 16 Testcontainers validation.

## 2026-09-29 — EDA-D1-04 offline AI safety layer

- Human owner: Azeez.
- Tool: OpenAI Codex.
- Use: public-only classifier and briefing contracts, model kill switch,
  prompt-injection/private-field screening, strict structured-output validation,
  citation/instrument enforcement, retry/timeout/token caps, deterministic
  rule-review fallback, synthetic evaluation fixture, metric harness, tests, and
  documentation.
- Human verification required: accept ADR-0010, review safety patterns and
  candidate benchmark gates, expand and label the held-out fixture set, and run
  the live benchmark only through an approved provider connection.
- Data: repository content and synthetic public-market examples only. No secrets,
  real client data, model provider calls, or AWS services were used.
- Limitation: offline tests verify contracts and failure behavior, not Luna model
  quality, latency, tokens, cost, regional routing, or provider availability.

## 2026-09-29 — EDA-D1-04 Bedrock Runtime adapter

- Human owner: Azeez.
- Tool: OpenAI Codex.
- Use: official-contract review, pinned AWS SDK integration, default-off Bedrock
  Runtime Converse adapter, global Luna inference-profile configuration, bounded
  timeouts/tokens, fail-closed model allowlisting, safe provider-error mapping,
  mocked tests, and documentation.
- Human verification required: accept ADR-0010, review the IAM policy and global
  cross-region data boundary, then run the held-out benchmark only through the
  approved lower-environment connection.
- Data: repository content and synthetic public JSON only. No credentials, real
  client data, model requests, or AWS service calls were used.
- Verification: mocked adapter tests exercise request construction, disabled and
  unapproved-model rejection, timeout propagation, and retryable throttling. Live
  IAM, routing, quality, latency, tokens, and cost remain unverified.

## 2026-09-29 — EDA-D1-04 offline benchmark reporting

- Human owner: Azeez.
- Tool: OpenAI Codex.
- Use: provider usage-metadata contract, Bedrock token/latency/stop-reason capture,
  strict benchmark-run schema, complete fixture coverage checks, p95 latency and
  token aggregation, candidate quality/safety gates, JSON report entry point,
  tests, and evaluation documentation.
- Human verification required: review fixture labels and candidate thresholds,
  record the approved model price used for estimated cost, inspect every safety
  result, and accept ADR-0010 before enabling the provider.
- Data: repository content and synthetic benchmark observations only. No model,
  AWS, credential, or private-data access occurred.
- Verification: focused tests cover metadata capture, passing/failing gates,
  complete-run enforcement, version propagation, cost recording, and p95 latency.
  No live benchmark result is claimed.

## 2026-09-29 — EDA-D1-04 synthetic fixture expansion

- Human owner: Azeez; Hayyun review remains required before labels are accepted.
- Tool: OpenAI Codex.
- Use: expand the public-only evaluation fixture from five to eighteen synthetic
  cases, add full event-type and negative/safety coverage, enforce unique IDs and
  synthetic identifiers, verify expected safety-screen outcomes, and prevent an
  AI-draft fixture from qualifying as model-enablement evidence.
- Human verification required: Azeez and Hayyun inspect every expected event type,
  negative label, and safety outcome before changing `labelStatus` to
  `HUMAN_REVIEWED` or using results for an enablement decision.
- Data: invented issuer/news examples and synthetic identifiers only. No real
  market article, client data, credentials, model call, or AWS service was used.
- Verification: 54 non-Docker service tests, including fixture-integrity and
  report-eligibility tests, pass locally. The full rerun could not start its two
  PostgreSQL Testcontainers classes because Docker Desktop was unavailable; the
  preceding full build passed 74 tests before this fixture-only change. No model
  quality, latency, routing, token, or cost result is claimed.

## 2026-09-29 — EDA-D1-05 offline operations controls

- Human owner: Azeez.
- Tool: OpenAI Codex.
- Use: provider-neutral default-off kill switches, hard search/token/budget
  limits, low-cardinality metrics, durable redacted audit-outbox persistence for
  re-enable, cost-control documentation, and an offline operations runbook.
- Human verification required: accept ADR-0014, review limits and planning cost
  assumptions, integrate provider/operator/audit adapters, and validate AWS
  billing, IaC, alarms, and an operations drill before cloud enablement.
- Data: repository content and synthetic test values only. No secrets, real
  client data, external providers, or AWS services were used.
- Verification: the full Maven build passed with 72 tests, including PostgreSQL
  Testcontainers coverage for durable re-enable audit and outbox-capacity failure.

## 2026-09-28 — EDA-D2-05 event-alert dashboard

- Human owner: Azeez; Hayyun review remains required for the CRM/frontend scope.
- Tool: OpenAI Codex.
- Use: active frontend discovery, React alert queue/detail implementation,
  evidence and explanation presentation, agent decisions and feedback,
  read-only administrator oversight, responsive state design, API adapter,
  synthetic fixtures, tests, and documentation.
- Human verification required: Hayyun reviews the frontend integration and a
  developer completes the visual desktop/mobile pass before promotion to
  `feat/event-driven-alerts`.
- Data: repository content and synthetic client, exposure, alert, and public
  citation examples only. No secrets, real client data, external providers, or
  AWS services were used.
- Verification: frontend unit/interaction tests, ESLint, production build, and
  static UI-pattern audit were run locally. Automated screenshot review was not
  available because no controllable browser was exposed to the session.

## 2026-09-30 — EDA-D2-05 dashboard workflow hardening

- Human owner: Azeez; Hayyun review remains required for the CRM/frontend scope.
- Tool: OpenAI Codex.
- Use: latest-decision presentation, immediate status synchronization after a
  mutation, terminal-state decision guards, persistent feedback access, styling,
  tests, and documentation.
- Human verification required: Hayyun reviews the workflow and a developer
  completes the desktop/mobile visual pass before feature-branch integration.
- Data: repository content and synthetic alert/decision values only. No secrets,
  real client data, external services, or AWS resources were used.
- Verification: nine frontend unit/interaction tests, ESLint, and the production
  Vite build passed. Automated screenshots remain pending because the local
  headless browser could not establish its Chrome CDP channel after diagnostics
  and restart.

## 2026-09-30 — EDA-D2-05 authoritative decision-state handling

- Human owner: Azeez; Hayyun review remains required for the CRM experience.
- Tool: OpenAI Codex.
- Use: aligned the synthetic decision adapter with the expanded API response and
  changed the dashboard to render the audited server-supplied `newStatus` rather
  than independently reconstructing the lifecycle transition.
- Human verification required: Hayyun reviews the interaction and completes the
  desktop/mobile visual pass before promotion to `dev`.
- Data: repository content and synthetic alerts only. No secrets, real client
  data, external providers, or AWS services were used.
- Verification: all nine frontend tests passed, ESLint completed without errors,
  and the Vite production build succeeded.

## 2026-09-30 — EDA-D2-06 offline integration and lifecycle controls

- Human owner: Azeez; Hayyun review remains required for shared domain and
  experience scope.
- Tool: OpenAI Codex.
- Use: integrate the API, ingestion, AI, operations, and dashboard task streams;
  implement the source-to-alert persistence path, operational quota wiring,
  leased Kafka audit publication, strict auditor allowlisting, bounded retention,
  PostgreSQL end-to-end coverage, and desktop/mobile browser verification.
- Human verification required: Hayyun reviews the integrated behavior and the
  proposed ADRs; Azeez reviews the synthetic AI labels before any feature-to-dev
  PR or provider enablement.
- Data: repository content and synthetic fixtures only. No credentials, real
  client data, AWS endpoint, model provider, or production broker was used.
- Verification: focused Java/PostgreSQL tests and the Go auditor contract test
  passed; the full clean Maven/frontend verification is recorded in the offline
  integration evidence after completion. Desktop and mobile synthetic dashboard
  screenshots were inspected locally.

## 2026-09-30 — EDA-D2-06 offline delivery and recovery closure

- Human owner: Azeez; Hayyun review remains required for shared auditor and
  domain behavior.
- Tool: OpenAI Codex.
- Use: add an embedded-Kafka producer/consumer acknowledgement test, verify that
  a failed event does not stop its publisher batch, exercise expired lease
  recovery and the retry cap against PostgreSQL, and add the Go auditor contract
  suite to CI with a disposable healthy database.
- Human verification required: Hayyun reviews the cross-service contract and
  proposed ADRs before feature promotion; the authenticated operator role
  contract remains a human design decision.
- Data: repository content and synthetic UUIDs/payloads only. No credentials,
  real client data, AWS endpoint, production broker, or external model was used.
- Verification: the clean Java reactor, disposable Go/PostgreSQL auditor tests,
  frontend tests/lint/build, Compose validation, and diff checks are recorded in
  the offline integration evidence.

## 2026-09-30 — Holdings-derived public search universe

- Human owner: Azeez; Hayyun review remains required for holdings/domain scope.
- Tool: OpenAI Codex.
- Use: replace the static active-instrument search universe with a Singapore-side
  aggregate derived from synthetic client holdings; map stable instrument IDs to
  public ticker, issuer, and sector terms; add minimum client-count/share privacy
  controls, synthetic reference migration, tests, ADR, and documentation.
- Human verification required: review ADR-0016, the 5%/two-client prototype
  thresholds, selection-inference risk, and the real instrument-master boundary
  before AWS enablement or promotion to `dev`.
- Data: repository-owned synthetic holdings and public synthetic instrument
  metadata only. No credentials, real clients, AWS endpoint, or external model
  was used.
- Verification: focused selection/query tests and the full local verification
  suite are recorded in the offline integration evidence.

## 2026-09-30 — Stakeholder and ASR report documentation

- Human owner: Azeez; team review is required before proposal/final-report use.
- Tool: OpenAI Codex.
- Use: draft a Solution View that introduces referenced AWS services, distinguish
  application users from IAM identities, document stakeholder permissions, and
  write Admin, Agent, and Event-Driven Client Alert ASRs with main/alternative
  flows, pre/post-conditions, architectural significance, and Mermaid sequences.
- Human verification required: confirm the Admin/Agent core-feature behavior with
  the relevant owners; confirm AWS service names and deployed evidence with the
  platform owner; accept the proposed alert ADRs before presenting planned
  controls as implemented.
- Data: repository architecture decisions, plans, NFR notes, and synthetic-system
  descriptions only. No credentials, real client data, AWS connection, external
  model call, or production evidence was used.
- Verification: cross-checked terminology against ADR-0004 and proposed
  ADR-0006 through ADR-0016; checked Markdown structure, Mermaid participants,
  regional privacy boundaries, and explicit separation of current MVP behavior
  from future vector-similarity work.

## 2026-10-16 — Client Health SF01 transaction intake decision

- Human owner: Garvit; Feature 3 owners must review provider-side changes.
- Tool: Cursor.
- Use: inspect the current transaction-service contracts and accepted identity,
  ingestion and database decisions; select a bounded service-authenticated pull
  API for six-month replay and daily Client Health intake; update ADR-0044, the
  feature plan and contract documentation.
- Human verification required: Feature 3 owners approve the dedicated replay
  endpoint, stable synthetic source fingerprint and six-month provider fixtures
  before their shared service files change.
- Data: repository documentation and synthetic contract examples only. No
  credentials, real client data, AWS calls or external model calls were used.
- Verification: synchronized `feat/client-health-monitor` through PR #111,
  reviewed ADR-0024, ADR-0025, ADR-0034, ADR-0037 and ADR-0043, and ran Git
  whitespace/diff checks.

## 2026-10-16 — Client Health SF01 consumer foundation

- Human owner: Garvit.
- Tool: Cursor.
- Use: implement provider-independent matched-transaction records, bounded
  cursor replay, complete-run validation, de-duplication and failure-path tests.
- Human verification required: review contract fields, replay limits and the
  fail-closed behavior before committing; Feature 3 provider changes remain
  separately owned.
- Data: synthetic identifiers and transaction examples only. No credentials,
  real client data, AWS calls or external services were used.
- Verification: 27 client-health module tests passed with the local Java 24
  compiler override; CI must repeat them on the required Java 25 toolchain.

## 2026-10-21 — Client Health architecture acceptance and SF01 mock replay

- Human owner: Garvit.
- Tool: Cursor.
- Use: revise and accept ADR-0034 after owner review; add the transaction replay
  page schema and unsafe fixtures; implement a contract-faithful six-month
  synthetic provider covering all three planned signals and controls.
- Human verification required: Swayam reviews the accepted architecture before
  building SF05-SF07; Joseph Yau/Neo Jia Wen review and implement the Feature 3
  provider side before production enablement.
- Data: repository-owned synthetic identifiers and transactions only. No
  credentials, real client data, AWS calls or external services were used.
- Verification: all 32 client-health tests passed with the local Java 24
  compiler override, including contract/schema/mock coverage; CI must repeat
  them on Java 25. Lint diagnostics and Git whitespace checks passed.

## 2026-10-21 — Client Health SF02 activity projection and baselines

- Human owner: Garvit.
- Tool: Cursor.
- Use: add explicit activity classification, atomic/idempotent transaction
  projection, replay-coverage tracking, and six-month/90-day/30-day account and
  client baseline summaries for the future deterministic rules.
- Human verification required: review ADR-0051, the 08:00 Asia/Singapore window
  boundaries, SGD-only failure behavior, and coordinate production health-db
  migration ownership with Swayam before a database adapter is added.
- Data: repository-owned synthetic transactions only. No credentials, real
  client data, AWS calls or external services were used.
- Verification: all 39 client-health tests passed with the local Java 24
  compiler override, including projection, status-transition, rollback,
  classification, currency, coverage, roll-up and synthetic baseline coverage;
  Java 25 CI verification remains required.

## 2026-10-21 — Client Health SF03 deterministic signal rules

- Human owner: Garvit.
- Tool: Cursor.
- Use: record ADR-0052; add rolling 30-day period summaries; implement the
  versioned recurring-credit interruption, customer-activity decline and
  sustained-net-outflow rules plus typed outcomes for RH-SF04.
- Human verification required: review project/demo thresholds and severity
  bands before commit; they are not universal banking standards. Java 25 CI
  must repeat local verification.
- Data: repository-owned synthetic baseline scenarios only. No credentials,
  real client data, AWS calls, Bedrock calls or external services were used.
- Verification: all 42 client-health tests passed with the local Java 24
  compiler override, covering positive signals, exact low-severity boundaries,
  healthy controls, just-below thresholds, insufficient/Unknown activity and
  deterministic repeatability; Java 25 CI remains required.

## 2026-10-21 — Client Health SF04 analysis orchestration

- Human owner: Garvit.
- Tool: Cursor.
- Use: record ADR-0053; implement renewable run-lease/watermark ports,
  deterministic findings/signals/fingerprints, one-finding-per-client grouping,
  retry-safe task handoff, and explicit cooldown/cap suppression outcomes.
- Human verification required: Swayam reviews and implements task-side atomic
  admission in RH-SF05; production run persistence and Java 25 CI remain
  required.
- Data: repository-owned synthetic transactions/findings only. No credentials,
  real client data, AWS calls, Bedrock calls or external services were used.
- Verification: all 47 client-health tests passed with the local Java 24
  compiler override, including success, repeated-run, lease-contention,
  suppression, partial-failure and deterministic-retry scenarios.

## 2026-10-21 — Client Health offline rule evaluation

- Human owner: Garvit.
- Tool: Cursor.
- Use: build a deterministic labelled-scenario harness for
  `relationship-health-v1`, covering positive, healthy-control, exact-boundary,
  just-below, Unknown-classification and sparse-history cases; render and
  document fixed-set rule metrics and limitations.
- Human verification required: treat 1.00 precision/coverage only as agreement
  with six repository-owned synthetic scenario groups, never as real-client or
  production accuracy; threshold changes require a new ruleset version.
- Data: repository-owned synthetic aggregate histories only. No credentials,
  real client data, AWS calls, Bedrock calls or external services were used.
- Verification: all six scenario groups and repeated evaluations passed; all 49
  client-health tests passed with the local Java 24 compiler override. Java 25
  CI remains required.

## 2026-10-22 — Client Health SF08 audit/outbox foundation

- Human owner: Garvit; Auditor-owner review remains required for shared files.
- Tool: Cursor.
- Use: implement a deterministic redacted relationship-health audit envelope,
  task/suppression/run event mapping, transactional outbox and delivery ports,
  and a bounded leased relay with idempotent enqueue semantics and redacted
  retry categories.
- Human verification required: do not add or deploy the Flyway/JDBC outbox,
  SQS gateway, IAM policy or Auditor allowlist until SF05 V1 is corrected and
  the Auditor owner accepts the service/action/payload contract.
- Data: repository-owned synthetic opaque identifiers and task states only. No
  credentials, real client data, AWS calls, SQS calls or external services were
  used.
- Verification: deterministic IDs, client-reference hashing, note exclusion,
  suppression mapping, duplicate enqueue, continue-after-failure and retry
  release behavior passed; all 54 Client Health tests passed locally with the
  Java 24 compiler override. Java 25 CI remains required.
