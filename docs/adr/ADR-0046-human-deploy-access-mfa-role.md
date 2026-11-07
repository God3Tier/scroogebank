# ADR-0046: Human deploy access through an MFA-protected role assumed from IAM users

- Status: Accepted
- Decision date/time: 2026-10-16 23:56 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen (runbook owner, who administers the team's AWS account)
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/infrastructure/deployer-access`
- Related issue/PR: TBD
- Supersedes: N/A. Records how this account departs from the target in [stakeholders-and-asrs.md](../architecture/stakeholders-and-asrs.md), which names IAM Identity Center for operators
- Superseded by: N/A

## Context

The team deploys by hand with Terraform and the AWS CLI ([deploy-aws.md](../runbooks/deploy-aws.md)). Until now, each operator signed in with an IAM user whose long-lived access key had administrator rights, and that key alone was enough to change or destroy everything in the account. The runbook preferred IAM Identity Center, and the target architecture names "IAM Identity Center or short-lived federated roles" for operators.

Four restrictions decide what the account allows:

1. **The account is a member of an AWS Organization the team does not control.** Its service control policy denies `backup:CreateBackupVault` in ap-southeast-2 and `logs:CreateLogGroup` in us-east-1 ([ADR-0042](ADR-0042-terraform-deployment.md) decision 11, the [Terraform README](../../infrastructure/terraform/README.md#left-out-of-the-account)). Service control policies apply only to member accounts, never to an Organization's management account. The account also arrived with a GuardDuty detector the team did not create.
2. **The team has no access to the root user**, and the root user has no MFA (checked on 2026-10-16).
3. **No temporary credentials are supplied for the account.** There is no Identity Center access portal and no role or credential process from the Organization's administrators; operators had only IAM users.
4. **The stacks are torn down often to save cost**: the crm and sftp-server stacks after testing sessions ([ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md)), and the bootstrap stack too. The CI pipeline (INF-09) is unused for the same reason: an always-on deploy pipeline has no stack to deploy to between sessions.

## Decision Drivers

- No credential that a laptop or a leaked file can expose should, on its own, give access to the account.
- Every session that can change the account requires MFA and expires.
- CloudTrail shows which person acted.
- Access must survive the teardown of every Terraform stack, including bootstrap.
- Terraform and the repository's scripts must keep working.

## Options Considered

### Option A: IAM Identity Center groups and permission sets

- Pros: no long-lived keys; MFA at sign-in; access managed by group; the target architecture's choice.
- Cons: **not possible here.** Permission sets, which give access to AWS accounts, need an *organization instance* of Identity Center, created in the Organization's management account. A member account can create only an *account instance*, and "account instances do not support permission sets and therefore do not support access to AWS accounts" ([AWS: Account instances of IAM Identity Center](https://docs.aws.amazon.com/singlesignon/latest/userguide/account-instances-identity-center.html)). Restriction 1 rules out the management account. Rejected.

### Option B: temporary credentials supplied by the account's administrators

- Pros: no keys held by the team.
- Cons: none are supplied (restriction 3). Rejected.

### Option C: IAM users with administrator rights attached directly (as before)

- Pros: already in place.
- Cons: a leaked access key is full administrator access, with no MFA and no expiry. Rejected.

### Option D: the role and group in Terraform (bootstrap or crm stack)

- Pros: reviewable as code.
- Cons: the stacks are destroyed often (restriction 4), and the role that runs a teardown cannot live inside what it destroys. Destroying bootstrap would leave no one able to recreate it. Rejected.

### Option E: a least-privilege deployer policy instead of administrator rights

- Pros: smaller blast radius in principle.
- Cons: running this Terraform is administrator-equivalent whatever the policy says. It creates IAM roles, role policies, instance profiles and the GitHub OIDC provider, and passes roles to ECS, Lambda and EC2, so a deployer could create and use a role with any permissions. Scoping it for real needs a permissions boundary on every role in the modules, and a policy that requires it. Rejected for now.

### Option F: a second, narrower role for releases

- Pros: the crm stack's GitHub deploy policy ([`ci.tf`](../../infrastructure/terraform/stacks/crm/ci.tf)) is a ready-made release-only policy.
- Cons: every deployment currently starts with `terraform apply` on a torn-down stack, and the CI pipeline is unused (restriction 4). Not needed yet.

### Option G: an MFA-protected admin role, assumed from IAM users that can do nothing else (chosen)

- Pros: possible in a member account; each session needs MFA and expires; a leaked access key alone gives no access; not affected by any stack's teardown.
- Cons: long-lived access keys still exist, one per person; created by hand.

## Decision

1. **Role `crm-infra-admin`** with the AWS managed policy `AdministratorAccess`. Its trust policy allows the account itself, only with MFA (`"Bool": {"aws:MultiFactorAuthPresent": true}`). Its maximum session duration is 4 hours.
2. **Group `crm-deployers`**, with one inline policy that allows only:
   - `sts:AssumeRole` on `crm-infra-admin`;
   - changing your own password;
   - registering and managing your own MFA device, following AWS's self-service example without its blanket deny;
   - creating, deactivating, deleting and listing your own access keys, **only while signed in with MFA**.
3. **Created by hand in the IAM console**, outside Terraform, so no stack's destroy removes them.
4. **One IAM user per person**, in `crm-deployers`, created with a console password that must be changed at first sign-in. The account owner never creates access keys for them, so each person registers MFA, signs in again with it, and only then creates their own key.
5. **CLI:** each person has two profiles:
   - `crm-user` holds their access key;
   - `crm` takes the role with `role_arn`, `source_profile = crm-user`, `mfa_serial`, `duration_seconds = 14400`, and `role_session_name` set to the person's name, which identifies them in CloudTrail.

   Terraform cannot prompt for an MFA code, so each session starts by taking the role with the CLI and exporting its credentials into the terminal (`aws configure export-credentials --profile crm --format env`), with `AWS_PROFILE` unset.
6. **No IAM user keeps direct administrator rights.** The account owner removes their own after confirming the role works.

The session length is 4 hours because a deploy or teardown session, with its RDS and CloudFront changes, fits in it, while a leaked session still expires the same day. With the 1-hour default, applies would often be cut off part way; 12 hours would leave a leaked session usable much longer.

## Rationale

Option G is the strongest setup the account allows. Identity Center (A) and supplied temporary credentials (B) depend on an Organization the team does not control. Keeping the setup outside Terraform follows from the teardowns (D). Administrator rights are what the Terraform needs in practice (E), so the protection goes into how the role is reached: MFA on every session, sessions that expire, and keys that cannot reach anything alone.

## Consequences

### Positive

- A leaked access key alone gives no access to the account, cannot create another key, and cannot remove the user's MFA.
- Every session that can change the account was started with MFA, ends within 4 hours, and is named after its person in CloudTrail.
- Destroying any stack leaves access intact.
- Offboarding is deleting the person's IAM user.

### Negative

- Long-lived access keys still exist. They are rotated by hand.
- Everyone in a session is an administrator: there is no separation of duties between people.
- The role and group are not in code. If they are changed or deleted, they are restored by hand from the runbook and this record.
- Each session needs three commands, repeated every 4 hours, and an apply cut off by the expiry must be run again.
- The root user has no MFA and the team cannot reach it to add one ([open item 26](../architecture/open-items.md#26-root-user-without-mfa)).

## Security & Privacy Impact

- MFA guards every route to administrator rights except the root user, which is outside the team's control.
- The group's policy reaches nothing but the role and the user's own credentials. Access key changes need MFA, so a stolen key cannot be used to create a second one.
- CloudTrail records each session's `role_session_name`. Its event history keeps 90 days of management events without any trail; the bootstrap stack's trail also stores them, but is destroyed with that stack.
- Keys and MFA codes are never committed or shared, AI tools included ([project plan](../../project_plan.md) AI-usage rules).

## Cost Impact

None. IAM and STS are free.

## Operational Impact

- [deploy-aws.md](../runbooks/deploy-aws.md) "AWS access" gives each person's one-time setup and the commands that start each session; its troubleshooting table covers expired sessions and refused role sessions.
- Onboarding: the account owner creates the user in `crm-deployers` with a console password. Offboarding: delete the user.
- Option F becomes worth revisiting when CI deploys again (INF-09); Option A, if the account moves to an Organization the team controls.

## Validation

- The console generated the role's trust policy with the account as principal and `aws:MultiFactorAuthPresent` as the condition. It was reviewed against decision 1 before the role was created.
- A new IAM user who had registered MFA but not yet signed in again with it was refused `iam:ListAccessKeys`. Signing in with MFA allowed it, as decision 2 intends.
- Not yet recorded: a full Terraform deploy and teardown run under the role.

## References

- [AWS: Account instances of IAM Identity Center](https://docs.aws.amazon.com/singlesignon/latest/userguide/account-instances-identity-center.html), [AWS: Organization and account instances](https://docs.aws.amazon.com/singlesignon/latest/userguide/identity-center-instances.html)
- [AWS STS: AssumeRole](https://docs.aws.amazon.com/STS/latest/APIReference/API_AssumeRole.html) (session duration and MFA parameters)
- [AWS: IAM users self-managing an MFA device](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_policies_examples_iam_mfa-selfmanage.html)
- [ADR-0028](ADR-0028-cicd-migrations-terraform.md), [ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md), [ADR-0042](ADR-0042-terraform-deployment.md)

## Revision history

- 2026-10-16 23:56 +08:00: accepted by the decision owner. The role and group were created in the account the same day.
