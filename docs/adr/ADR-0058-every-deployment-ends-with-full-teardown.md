# ADR-0058: Every deployment starts from an empty account and ends with a full teardown

- Status: Proposed
- Decision date/time: 2026-10-22 20:33 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: TBD (drafted on `dev`)
- Related issue/PR: TBD
- Supersedes: [ADR-0053](ADR-0053-email-delivery-through-ses.md) decision 2 (mail-identity is long-lived); [ADR-0042](ADR-0042-terraform-deployment.md) decision 1 in part (bootstrap applied once per account, and holding AWS Budgets); [ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md) in part (prod stopped, not destroyed, between sessions); [ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md) in part (the budget alert thresholds)
- Superseded by: N/A

## Context

Several team members deploy the CRM by hand into the same AWS account ([deploy-aws.md](../runbooks/deploy-aws.md)), as the shared `crm-infra-admin` role ([ADR-0046](ADR-0046-human-deploy-access-mfa-role.md)). Until now, some stacks outlived a deployment:

- `bootstrap` was applied once per account, with its state in a local `terraform.tfstate` on one person's machine. No one else could change or destroy it.
- `mail-identity` was long-lived by design (ADR-0053 decision 2), so team inboxes did not have to be verified again.
- Prod was stopped between sessions rather than destroyed (ADR-0029), and an `env/prod.tfvars` from an earlier deployment could carry values from another account into the next one, such as step 9.3's SFTP server address.

So a deployment's starting point depended on who deployed last and what they left behind. The runbook had to cover leftovers case by case, and a teammate could not tell whether a stack in the account was in use.

## Decision Drivers

- Everyone starts a deployment from the same, known state.
- One person's deployment does not depend on another person's machine or local state.
- No idle spend after a deployment.
- Access survives every teardown (ADR-0046, unchanged).

## Options Considered

- **Tear down every Terraform stack at the end of each deployment, `mail-identity` and `bootstrap` included (chosen).**
  - Pros: every deployment starts from the same empty account; no local state outlives the deployment that created it; nothing bills between deployments.
  - Cons: each deployment repeats the SES verification (DKIM, and every inbox clicks its link again) and recreates the state bucket and CloudTrail.
- **Tear down everything except `mail-identity` (ADR-0053 as accepted).**
  - Pros: no repeated SES verification.
  - Cons: the account is never empty; whoever created the identity holds its tfvars, and its state is in a bucket that `bootstrap`'s teardown would delete.
- **Tear down the app stacks only, keeping `bootstrap` and `mail-identity`.**
  - Pros: fastest start.
  - Cons: `bootstrap`'s state stays on one machine; the starting state still depends on what was kept.
- **Keep the stop model (ADR-0029).**
  - Pros: data and Cognito users survive between sessions.
  - Cons: about USD 65 a month while stopped; leftover state and tfvars from earlier deployments.

## Decision

1. **The starting state is an empty account.** Before step 1, the account holds no resource from this repository's Terraform. Only what is created by hand stays: the `crm-infra-admin` role, the `crm-deployers` group and its users (ADR-0046), the budget alerts (decision 7), and the registered domain with its hosted zone (ADR-0042 decision 8).
2. **Every deployment ends with a full teardown**, in this order: `sftp-server`, `business-dashboard`, `agent-engagement`, `crm`, `mail-identity`, then `bootstrap`, after which the remaining secrets are hard-deleted and the state bucket emptied. The runbook's last step checks that the account is empty again.
3. **`mail-identity` is created in every deployment** (supersedes ADR-0053 decision 2). Its identities, DKIM records and recipient verifications are recreated each time.
4. **`bootstrap` is applied at the start of every deployment** (supersedes ADR-0042 decision 1 in part). Its local state lives on the deployer's machine only for that deployment.
5. **Stopping is a pause within one deployment** (amends ADR-0029). `running = false` and `scripts/aws-session.sh stop` may pause a deployment overnight or between test sessions, but the deployment still ends with decision 2.
6. **One deployment at a time.** If the state bucket `crm-tfstate-<account>` exists when someone starts, another deployment is in progress: they ask in the team channel rather than apply.
7. **The budget alerts are made by hand, outside Terraform** (supersedes ADR-0042 decision 1 in part). ADR-0029's alerts must watch spending across every deployment and teardown, so the account owner keeps them in the Billing console, and `bootstrap` no longer creates `crm-total` or `crm-bedrock`. They alert when actual cost passes **10, 20, 40, 60, 80 and 100%** of the limit (supersedes ADR-0029's 50, 70, 85, 95 and 100%), so the first warning comes early enough to stop a deployment left running. The USD 300 limit and the separate Bedrock alert are unchanged from ADR-0029, and the budgets count cost before credits.

## Rationale

A single starting state makes every step of the runbook apply the same way for everyone, and removes the leftovers the runbook had to work around. Repeated SES verification costs a few minutes of clicking and up to an hour of DKIM waiting per deployment; the team accepted that in exchange for a predictable empty account.

## Consequences

### Positive

- Every deployment follows the same steps, from the same state.
- No deployment depends on state kept on another person's machine.
- The budget alerts keep counting across teardowns, so no spending goes unwatched between deployments.
- Nothing bills between deployments, apart from the hosted zone (USD 0.50 a month).

### Negative / Trade-offs

- Each deployment repeats SES verification: every `verified_recipients` inbox, and `sender_address` without a domain, must click its new verification email, and DKIM must verify again before mail is sent.
- No data survives a deployment: users, clients and the audit chain start empty each time (synthetic data only, so nothing is lost).
- The CloudTrail trail and its bucket are destroyed with `bootstrap`; CloudTrail's 90-day event history still records each session.
- A deployment takes longer to start, and a teardown longer to finish.
- Two people cannot deploy at the same time in the account.
- The budget alerts are no longer reviewed or tested as code; their thresholds and the cost-before-credits setting are kept by hand.

## Security & Privacy Impact

None beyond the above. Access stays outside Terraform (ADR-0046). The audit anchor bucket's Object Lock still applies within a deployment; the teardown removes the anchors as before.

## Cost Impact

Removes the stopped-stack cost of about USD 65 a month (ADR-0042 "Cost Impact"). A deployment bills only while it exists.

## Operational Impact

- [deploy-aws.md](../runbooks/deploy-aws.md): an empty-account check before step 1, `bootstrap` and `mail-identity` applied every deployment, "Between sessions" reframed as a pause, and a full teardown with a final check.
- [infrastructure/terraform/README.md](../../infrastructure/terraform/README.md) and the `bootstrap` and `mail-identity` header comments say the same.
- `bootstrap` drops the two budgets, their variables and `tests/budgets.tftest.hcl`. The deployer whose local state still tracks them runs `terraform state rm` on them once, so Terraform forgets them without deleting them.

## Validation

- A full deployment and teardown run from an empty account, ending with the runbook's empty-account check printing nothing.

## References

- [ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md), [ADR-0042](ADR-0042-terraform-deployment.md), [ADR-0046](ADR-0046-human-deploy-access-mfa-role.md), [ADR-0053](ADR-0053-email-delivery-through-ses.md), [ADR-0055](ADR-0055-sender-address-without-a-domain.md)

## Revision history

- 2026-10-22 20:33 +08:00: proposed.
- 2026-10-22: decision 7 added while Proposed, as decided by the decision owner: the budget alerts move out of `bootstrap` into the Billing console.
- 2026-10-22: decision 7 amended while Proposed, as decided by the decision owner: the alert thresholds become 10, 20, 40, 60, 80 and 100%, superseding ADR-0029's thresholds.
