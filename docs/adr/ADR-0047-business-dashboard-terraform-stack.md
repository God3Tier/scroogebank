# ADR-0047: The business dashboard in its own Terraform stack

- Status: Accepted (2026-11-01; accepted for the team by Neo Jia Wen)
- Decision date/time: 2026-10-20 10:49 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/infrastructure/business-dashboard-stack`
- Related issue/PR: TBD
- Supersedes: N/A. Amends [ADR-0042](ADR-0042-terraform-deployment.md) decision 1 (layout) while both are Proposed
- Superseded by: N/A

## Context

dashboard-service, the business dashboard (Feature 7, [ADR-0033](ADR-0033-xf2-dashboard-topology-cache-audit.md)), was deployed by `stacks/crm`. Its module sat in `services.tf`, and the stack also named it in seven shared lists: the ECR repositories, the security groups, the security group rules and egress rules, the ports, the audit queue's allowed senders, and the GitHub deploy role's services. Every change to the dashboard, such as its CPU in PR #118, meant planning and applying the whole CRM stack, which also holds the databases, Cognito, the edge and the audit chain. The dashboard's changes also landed in `services.tf` and `security_groups.tf`, which the infrastructure plan already lists as a conflict zone.

The dashboard is a leaf of the CRM:

- it owns no database and no data;
- nothing inside the VPC calls it; the frontend reaches it through the shared `/api` route;
- it calls three services (user-, client- and account-service) over Service Connect, and sends audit events to the shared queue.

Unlike the SFTP server ([ADR-0024](ADR-0024-transaction-ingestion-sftp-lambda.md) decision 1), it is not a third party. It must run inside the CRM's VPC, ECS cluster, internal ALB and Service Connect namespace, accept the CRM's Cognito tokens, trust the CRM's project CA and send to the CRM's audit queue.

The stacks are torn down after each session ([ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md), [ADR-0046](ADR-0046-human-deploy-access-mfa-role.md)), so every deployment starts from nothing and every CRM resource ID changes. Nothing is deployed now, so there is no live state to migrate.

## Decision Drivers

- Change and deploy the dashboard without applying the CRM stack.
- No values copied by hand after each rebuild.
- The same runtime behaviour and the same allowed-traffic chain as before ([ADR-0019](ADR-0019-edge-entry-chain.md) decision 4).
- The audit queue keeps refusing senders that are not on its list ([ADR-0020](ADR-0020-audit-write-path-outbox-shared-sqs.md)), without the dashboard silently losing its events.
- No extra AWS cost.

## Options Considered

### How the new stack gets the CRM stack's values

The dashboard needs about 20 values from the CRM stack: the cluster, subnets, VPC, ALB listener, five security groups, the Service Connect namespace, the Cognito issuer and web client, the audit queue, the project CA certificate, and the stop-model settings.

- **Read the CRM stack's state (`terraform_remote_state`) through one output (chosen).**
  - Pros: nothing is copied by hand, and the values are always the current ones after a rebuild. One output, `dashboard_inputs`, is the whole contract.
  - Cons: the CRM stack must be applied first. Whoever applies the dashboard needs read access to the CRM's state object, which holds secrets such as the project CA's private key. Renaming a key in the output breaks the dashboard's plan.
- **Copy outputs into tfvars, as the sftp-server stack does.**
  - Pros: the stacks stay loosely coupled, and no state access is needed.
  - Cons: about 20 values, most of which change on every rebuild. The CA certificate is a multi-line PEM. A stale value fails the apply or points at a resource that no longer exists. The sftp-server stack copies only two values that stay the same between rebuilds, which is why copying works there.
- **Look up each resource by name or tag with data sources.**
  - Pros: no state access is needed.
  - Cons: the CA certificate exists only in the CRM's state (and in plain text inside task definitions), so the CRM stack would still have to publish it somewhere. `running`, Fargate Spot and log retention are settings, not AWS resources, so the dashboard stack would repeat them as variables. About ten lookups would each depend on a naming pattern that nothing in the CRM stack declares.
- **The CRM stack writes the values to one SSM parameter.**
  - Pros: no state access is needed, and the contract is explicit.
  - Cons: one more resource holding the same values, with the same coupling as reading the state.
- **Keep the dashboard in the CRM stack.**
  - Pros: one apply and no cross-stack wiring.
  - Cons: the problems in Context.

### Where the dashboard's image repository lives

- **In the dashboard stack (chosen).** The stack owns everything the dashboard alone uses, and the CRM stack lists no dashboard image.
- **In the CRM stack.** The runbook keeps one push loop, but the CRM stack still names the dashboard.

### How the audit queue allows the dashboard's role

- **The existing `extra_audit_producer_role_arns` variable, checked by a precondition (chosen).** The queue policy compares `aws:PrincipalArn` as a string, and the ecs-service module gives the task role a fixed name, so the role can be allowed before it exists. The ARN is set once in the CRM stack's tfvars. The dashboard stack refuses to plan until it is there.
- **The CRM stack builds the ARN itself.** No manual step, but the CRM stack names the dashboard again.

## Decision

1. **Stack.** `infrastructure/terraform/stacks/business-dashboard` deploys dashboard-service:
   - There is one stack per CRM stack, that is per legal entity and environment ([ADR-0026](ADR-0026-one-stack-per-legal-entity.md)). Its state is at `business-dashboard/<entity>/<environment>.tfstate` in the same bucket.
   - It holds the ECR repository, the security group and its rules, and the service through `modules/ecs-service`: the task definition, roles, log group, target group, the listener rule at priority 50 for `/api/dashboard` and `/api/dashboard/*`, and auto scaling.
   - It also holds dashboard-service's TLS certificate, self-signed with the `tls` provider, and the Secrets Manager secret the task reads it from. The internal ALB, its only caller, reaches it over HTTPS ([ADR-0042](ADR-0042-terraform-deployment.md) decision 8).
   - Resource names keep the CRM's `crm-<entity>-<environment>` prefix, so they are the same as before.
2. **Wiring.** The stack reads the CRM stack's `dashboard_inputs` output with `terraform_remote_state`. That output is the contract between the two stacks, and renaming one of its keys is a breaking change.
3. **Network.** The dashboard stack owns its security group, the rule letting the internal ALB reach it on 8085, and the rules on the CRM's user, client and account groups that let it call each service on its port. The CRM stack defines its rules as separate resources, never inline, so no rule is managed by both stacks.
4. **Audit queue.** The CRM stack allows the dashboard's task role through `extra_audit_producer_role_arns`. A precondition in the dashboard stack fails the plan, naming the ARN, until the CRM stack's state lists it.
5. **Stop model.** The task minimum and maximum, Fargate Spot, log retention and CPU architecture come from the CRM stack's output, so the CRM's `running` variable stays the only switch. The dashboard stack must still be applied after every CRM apply that changes `running`.
6. **Order.**
   - Apply: crm, then business-dashboard, then sftp-server and crm again as before.
   - Destroy: business-dashboard before crm, because its rules sit on the CRM's security groups and its service runs in the CRM's cluster.
   - The repository is force-deleted only when the CRM stack's deletion protection is off.
7. **CI.** When the CRM stack has the GitHub deploy role ([ADR-0028](ADR-0028-cicd-migrations-terraform.md)), the dashboard stack attaches a policy to it. The policy lets the role push this image and pass this service's roles. The role already registers task definitions and updates any service in the cluster.
8. **Monitoring.** The CRM's CloudWatch dashboard ([ADR-0027](ADR-0027-observability-baseline.md)) keeps dashboard-service in its ECS CPU widget, because the service still runs in the CRM's cluster. The ALB 5xx and API latency alarms already cover its route. No alarm is added.

## Rationale

Reading the state is the only option that needs no hand-copied values and no new resources, and it can pass the CA certificate. Those matter more here than loose coupling, because every session rebuilds the CRM and changes every ID. The coupling is real: the dashboard cannot run without the CRM. The single output makes that coupling explicit, and a broken contract fails at plan time rather than at runtime.

## Consequences

### Positive

- A dashboard change is planned and applied in a stack of about 20 resources instead of the whole CRM.
- Dashboard work no longer touches `stacks/crm/services.tf` or `security_groups.tf`.
- A session can run the CRM without the dashboard, and the dashboard can be destroyed alone.

### Negative / Trade-offs

- Each session has one more apply to start and one more to stop. A dashboard left running after the CRM is stopped keeps one task billed until its stack is applied again.
- Without the dashboard stack, the business dashboard pages fail, and so does the transactions panel on the agent's home page, which calls `/api/dashboard/transactions`. A demo deployment needs both stacks.
- The first deployment needs the dashboard's role ARN in the CRM stack's tfvars.
- Whoever applies the dashboard reads the CRM's whole state, not just the output.

## Security & Privacy Impact

- The allowed-traffic chain is unchanged: the internal ALB reaches the dashboard on 8085; the dashboard reaches user-, client- and account-service on their ports and nothing else inside the VPC; egress through the NAT gateway is as before.
- The audit queue's list of senders is unchanged in effect, and the precondition stops a deployment that would lose audit events.
- Reading the CRM's state exposes its secrets, including the project CA's private key, to whoever applies the dashboard stack. Those are the same operators, using the same role ([ADR-0046](ADR-0046-human-deploy-access-mfa-role.md)), who already apply the CRM stack. The dashboard stack never writes that state.
- The CA certificate passed to the dashboard is public; its key is not passed. That is why the dashboard's own certificate is self-signed rather than issued by the project CA: the ALB, its only caller, does not check target certificates, and nothing calls it over Service Connect. A future Service Connect caller would need a CA-issued certificate, which means the CRM stack issuing it and passing its secret through `dashboard_inputs`.
- The certificate's private key is in this stack's state, in the same restricted bucket as the CRM's.

## Cost Impact

The move itself costs nothing. The same service, repository, log group, target group and rules exist as before, recorded in a different state file. The extra state object in the existing bucket is a few kilobytes. The TLS certificate's secret, added later (INF-08), costs USD 0.40 a month, running or stopped.

The only new risk is a dashboard left running after the CRM is stopped. One task at 1 vCPU and 2 GB on Fargate costs about USD 1.50 a day in ap-southeast-1.

## Operational Impact

- [deploy-aws.md](../runbooks/deploy-aws.md) gains a step for this stack, pushes the dashboard image to this stack's repository, applies this stack after each CRM start and stop, and destroys it before the CRM.
- `scripts/aws-session.sh` describes the extra apply.
- Rollback: the dashboard has no data. Deploy an earlier image tag, or destroy this stack and restore the module in the CRM stack from git.

## Validation

- `terraform validate` on all four stacks.
- `terraform test` in `stacks/business-dashboard`: three runs against mocked providers and a stand-in for the CRM's state check:
  - the predicted role name;
  - the repository settings;
  - the ALB rule and the rules on the three upstream groups;
  - the deploy-role policy with and without the CRM's role;
  - the TLS certificate's name and its secret's recovery window, with and without deletion protection;
  - that the plan fails when the audit queue does not allow the role.
- `terraform test` in `stacks/crm` still passes its six runs.
- **The first real apply is done (2026-10-20, confirmed by the decision owner):** the stack is applied, and dashboard-service runs and passes its ALB health check over HTTPS.
- **Still to check:** `/api/dashboard/*` returns panels for an agent and an admin, and its audit events reach the audit queue with no `AccessDenied` in its logs.

## References

- Terraform: the `terraform_remote_state` data source; custom conditions (`precondition`)
- [ADR-0019](ADR-0019-edge-entry-chain.md), [ADR-0020](ADR-0020-audit-write-path-outbox-shared-sqs.md), [ADR-0024](ADR-0024-transaction-ingestion-sftp-lambda.md), [ADR-0026](ADR-0026-one-stack-per-legal-entity.md), [ADR-0027](ADR-0027-observability-baseline.md), [ADR-0028](ADR-0028-cicd-migrations-terraform.md), [ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md), [ADR-0033](ADR-0033-xf2-dashboard-topology-cache-audit.md), [ADR-0042](ADR-0042-terraform-deployment.md), [ADR-0046](ADR-0046-human-deploy-access-mfa-role.md)

## Revision history

- 2026-10-20 10:49 +08:00: proposed.
- 2026-10-20: amended while Proposed, as decided by the decision owner (INF-08): the stack holds a self-signed TLS certificate and its secret, so the internal ALB reaches dashboard-service over HTTPS. Decision 1, Security & Privacy Impact, Cost Impact and Validation changed to match.
- 2026-10-20: Validation records the first apply's results, which the decision owner confirmed. The decision is unchanged.
