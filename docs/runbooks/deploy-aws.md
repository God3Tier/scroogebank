# Deploy the CRM to AWS by hand (Terraform)

> **Status:** for testing deployments from a developer machine. There is no CI pipeline for Terraform yet ([infrastructure plan](../feature-plans/infrastructure_plan.md) INF-09). **Owner:** Neo Jia Wen. **Last updated:** 2026-10-22.
> Decisions: [ADR-0028](../adr/ADR-0028-cicd-migrations-terraform.md), [ADR-0029](../adr/ADR-0029-environment-lifecycle-and-cost-controls.md), [ADR-0042](../adr/ADR-0042-terraform-deployment.md), [ADR-0046](../adr/ADR-0046-human-deploy-access-mfa-role.md) (AWS access), [ADR-0047](../adr/ADR-0047-business-dashboard-terraform-stack.md) (the business-dashboard stack), [ADR-0058](../adr/ADR-0058-every-deployment-ends-with-full-teardown.md) (every deployment ends with a full teardown; proposed).

**Every deployment starts from an empty account and ends with [Tear down](#tear-down)**, which destroys every Terraform stack, `bootstrap` and `mail-identity` included ([ADR-0058](../adr/ADR-0058-every-deployment-ends-with-full-teardown.md)). That way everyone starts from the same state. Only what is created by hand stays between deployments: the `crm-infra-admin` role and your IAM user ([AWS access](#aws-access)), and the domain with its hosted zone ([Register a domain](#register-a-domain-once-per-account)). One deployment runs at a time; [Check the account is empty](#check-the-account-is-empty) tells you whether one is in progress.

The code is in `infrastructure/terraform/` (see its [README](../../infrastructure/terraform/README.md)). Commands run from the repository root in Git Bash, unless a step says otherwise. In Windows PowerShell 5.1 (VS Code's default terminal on Windows), quote every option that contains a dot, such as `"-var-file=env/prod.tfvars"`: unquoted, PowerShell splits it at the first dot. Everything uses synthetic data only.

## Before you start

- **Tools:** Terraform 1.10 or newer (tested with 1.16.6), AWS CLI v2, Docker, GNU Make (step 2), JDK 25 and Maven, Node.js for the frontend, Python 3 for the scripts (step 5, 9.5 and the database console).
- **AWS access:** set up your CLI once and start each session as in [AWS access](#aws-access) below. Terraform and the scripts use the credentials that section puts in your terminal; without them, Terraform fails with "No valid credential sources found".
- **The account must be on the paid plan.** In the Billing console, "Free Tier" shows the account plan; upgrade a Free plan account first. The Free plan caps RDS instances below the six the stack needs, and refuses its backup settings (ADR-0042).
  - New accounts may have low **service quotas**. If tasks stay in `PROVISIONING` or an instance fails to launch on a vCPU limit, check Fargate On-Demand vCPUs and EC2 Standard vCPUs in Service Quotas.
- **Cost.** A deployment bills from step 1 until its teardown. Prod costs about USD 360 a month left running at one task per service, and about USD 65 a month even when stopped ([Pausing a deployment](#pausing-a-deployment)), so tear it down when you are done rather than leaving it stopped (ADR-0042 "Cost Impact").

## AWS access

Everyone deploys as the IAM role `crm-infra-admin`, which accepts only sign-ins with MFA and gives temporary credentials for up to 4 hours. Your own IAM user, in the group `crm-deployers`, can do nothing else: its access key alone cannot reach any resource. The account owner creates the role, the group and your user by hand in the IAM console, outside Terraform, so tearing down any stack leaves them in place. [ADR-0046](../adr/ADR-0046-human-deploy-access-mfa-role.md) explains why: IAM Identity Center is not available in this account.

You get your user name, a temporary password, the account ID and the sign-in URL (`https://<ACCOUNT_ID>.signin.aws.amazon.com/console`) from the account owner.

### Set up the CLI (once)

1. Sign in at the URL and set a new password (if prompted).
2. Register MFA: your user name (top right) → **Security credentials** → **Assign MFA device** → authenticator app.
3. **(IMPT!)** Sign out and sign in again with a code: you can create an access key only while signed in with MFA.
4. On the same page, under **Access keys**, choose **Create access key** → **Command Line Interface**. Never commit the key or paste it anywhere, AI tools included.
5. Copy your MFA device's **Identifier** from the MFA section, such as `arn:aws:iam::<ACCOUNT_ID>:mfa/<device-name>`.
6. Store the key in its own profile. Enter the key ID, the secret, `ap-southeast-1` and `json` when asked:

   ```sh
   aws configure --profile crm-user
   ```

7. Create the profile that takes the role. Set your two values in the first lines, then paste the rest as it is: the account ID is read from your MFA device's ARN. `ME` names you in CloudTrail, since everyone uses the same role: letters, digits and `_+=,.@-` only, **no spaces**. `duration_seconds` cannot exceed the role's 4 hours.

   ```sh
   MFA_ARN='arn:aws:iam::<ACCOUNT_ID>:mfa/<device-name>'   # the Identifier from step 5 - set it yourself
   ME='<your-name>' # Set it yourself
   ACCOUNT_ID=$(cut -d: -f5 <<<"$MFA_ARN")
   aws configure set role_arn "arn:aws:iam::$ACCOUNT_ID:role/crm-infra-admin" --profile crm
   aws configure set source_profile crm-user --profile crm
   aws configure set mfa_serial "$MFA_ARN" --profile crm
   aws configure set duration_seconds 14400 --profile crm
   aws configure set role_session_name "$ME" --profile crm
   aws configure set region ap-southeast-1 --profile crm
   ```

8. Test it: `aws sts get-caller-identity --profile crm` asks for an MFA code, then prints an ARN containing `assumed-role/crm-infra-admin/<your-name>`.

`crm-user` is who you are, and can only take the role; `crm` is the role you act as. Only ever use `crm`.

### Start each session

In the terminal you deploy from, Git Bash:

```sh
unset AWS_PROFILE
aws sts get-caller-identity --profile crm      # asks for your MFA code
eval "$(aws configure export-credentials --profile crm --format env)"
export AWS_REGION=ap-southeast-1               # the line above copies keys, not the profile's region
```

or PowerShell:

```powershell
Remove-Item Env:AWS_PROFILE -ErrorAction SilentlyContinue
aws sts get-caller-identity --profile crm
aws configure export-credentials --profile crm --format powershell | Invoke-Expression
$env:AWS_REGION = 'ap-southeast-1'
```

Then run every step of this runbook in that terminal. After 4 hours the credentials expire: run the four lines again. While the `crm` profile's cached session is still valid, `aws sts get-caller-identity --profile crm` does not ask for a code, but the terminal's copy of the credentials can still have expired; the `export-credentials` line refreshes it. A Terraform apply that is cut off by the expiry stops part way, and its state is saved; start a new session and run it again.

Terraform cannot ask for an MFA code, so it gets the session's credentials from the last line instead of the `crm` profile.

- **Do not set `AWS_PROFILE`.** With it set, Terraform may try the `crm` profile itself and fail. If you ran `setx AWS_PROFILE crm` before, remove it with `reg delete HKCU\Environment /v AWS_PROFILE /f` and open a new terminal.
- **If the MFA prompt hangs or never appears in Git Bash**, run that line in PowerShell, or as `winpty aws sts get-caller-identity --profile crm`. The session is cached for both, so the `eval` line then works in Git Bash.
- If `aws configure export-credentials` is not recognised, update the AWS CLI.

## Register a domain (once per account)

**NOTE**: if using the project account, this section has already been manually set up for you. Proceed to [Check the account is empty](#check-the-account-is-empty).

The CRM stack serves the app at `https://<legal_entity>.<domain>` (`sg.<domain>` for prod) and puts the internal ALB on HTTPS, with ACM certificates validated in the domain's Route 53 zone ([ADR-0042](../adr/ADR-0042-terraform-deployment.md) decision 8). The domain is registered by hand, outside Terraform, like the `crm-infra-admin` role: destroying a stack, `bootstrap` included, never removes the domain or its zone, so every later deployment reuses them.

**The project account already has `itsag3t6.com`**, registered until 21 July 2027, so skip the registration there. If **Route 53 → Hosted zones** has no `itsag3t6.com` (it was missing on 2026-10-20), create it once:

1. **Route 53 → Hosted zones → Create hosted zone:** domain name `itsag3t6.com`, type **Public hosted zone**.
2. Open the new zone and copy the four values of its **NS** record.
3. **Route 53 → Registered domains → `itsag3t6.com` → Actions → Edit name servers:** replace the four name servers with those, and save.
4. Wait until `nslookup -type=NS itsag3t6.com 8.8.8.8` lists the same four: usually minutes to an hour, at most about 48 hours. Until then, certificates do not validate.

**IMPT**: Keep the zone between deployments: a new zone gets new name servers, and these steps would have to be repeated. Once it exists, step 3 finds it and sets `domain_name` itself, so the app is at `https://sg.itsag3t6.com`.

**In any other account**, such as a personal one for testing, either deploy without a domain (step 3 then leaves `domain_name` out by itself), or register one of your own once, before step 1:

1. In the Route 53 console, open **Domains → Registered domains → Register domains**, search for a name and check out. Choose a name that does not imitate a real bank or organisation, and leave out "bank": a new domain whose page is a bank-branded sign-in can be flagged as phishing.
2. Register it for 1 year with **auto-renew off**, and keep privacy protection on.
3. **Cost:** about USD 15 a year for a `.com`; the console shows each TLD's price, and some are cheaper. It is charged at once and not refunded. **AWS credits do not cover domain registration**, so it goes on the account's card. The hosted zone adds USD 0.50 a month.
4. **Verify the registrant e-mail within 15 days.** Route 53 sends a link to the registrant contact's address; without it, the domain is suspended and certificates stop validating.

Registration usually finishes within minutes, and Route 53 e-mails when it does. Route 53 also creates a public hosted zone with the same name and points the domain at it. Terraform finds that zone by name, so never delete it or create a second zone with the same name ([Troubleshooting](#troubleshooting)).

Without a domain, the app keeps its `*.cloudfront.net` address and the internal ALB listens on HTTP inside the VPC.

## Check the account is empty

Before step 1, check that no earlier deployment is still in the account. Each line prints nothing in an empty account:

```sh
aws s3api list-buckets --query 'Buckets[?starts_with(Name, `crm-`) || starts_with(Name, `core-banking-`)].Name' --output text
aws ecs list-clusters --query 'clusterArns' --output text
aws rds describe-db-instances --query 'DBInstances[].DBInstanceIdentifier' --output text
aws sesv2 list-email-identities --query 'EmailIdentities[].IdentityName' --output text
aws secretsmanager list-secrets --include-planned-deletion --filters Key=name,Values=crm- --query 'SecretList[].Name' --output text
```

- **`crm-tfstate-<account>` is listed, or a cluster or database:** another deployment is in progress, or its teardown did not finish. Ask the team who it belongs to, and do not apply anything: your stacks would share its state bucket and names. Its owner finishes [Tear down](#tear-down).
- **Only secrets are listed:** a teardown skipped the hard delete. Run the secrets loop in [Tear down](#tear-down) with `STACK=crm-sg-prod`, then check again.

## 1. Bootstrap the account

```sh
cd infrastructure/terraform/bootstrap
[ -f terraform.tfvars ] || cp terraform.tfvars.example terraform.tfvars   # keeps an existing file; set budget_emails and budget_start_date
terraform init
terraform apply                  # type yes; it must end with "Apply complete!"
terraform output -raw state_bucket   # steps 3, 4 and 9.1 read this output themselves
cd -
```

`terraform output` reads the state that a finished apply writes, so "No outputs found" means the apply has not completed in this directory: run it again and fix the error it reports. A missing variable, such as a `terraform.tfvars` copied before `budget_start_date` existed, makes it stop and ask for the value.

This stack keeps its state in a local `terraform.tfstate` (git-ignored), on your machine only. Keep that file until your teardown has destroyed `bootstrap`; if it is lost before then, `terraform import` the state bucket again, or no one can destroy the stack. A `terraform.tfstate` left from an earlier deployment of yours holds no resources once that deployment was torn down, so the apply creates everything again.

Bootstrap creates no GuardDuty detector: the account already has one, and a second fails. The crm stack also leaves out the cross-Region backup copy and WAF request logging, which the account's service control policy refuses. The [Terraform README](../../infrastructure/terraform/README.md#left-out-of-the-account) lists what each omission means.

**Budget alerts.** AWS Budgets e-mails `budget_emails` directly; there is no subscription to confirm.

- `crm-total` counts all spending since `budget_start_date` against the USD 300 project limit. It e-mails when actual cost passes 50, 70, 85, 95 and 100%, and when the forecast passes 100%.
- `crm-bedrock` e-mails when a month's Bedrock cost passes 50, 80 and 100% of `bedrock_budget_usd`.
- Both count cost before credits, so they still fire when credits cover the bill.
- Each threshold e-mails once per budget period. Cost data refreshes a few times a day, so an alert can arrive hours after the spend.

## 2. Build the Lambda zip and the images

```sh
mvn -B -pl services/cognito-triggers -am clean package
ls services/cognito-triggers/target/cognito-triggers.zip
```

The CRM stack deploys this file as the Cognito triggers that audit every login and password reset (ADR-0040), and its plan fails until the file exists. Keep `clean`: VS Code's Java extension writes its own class files to `target/classes`, and without `clean` Maven can package those instead of recompiling; the tests then fail with `ClassFormatError`. Keep `enable_cognito_triggers = true`: the switch exists for the mocked Terraform tests, and turning it off leaves sign-ins out of the audit log.

Build the images now, before any AWS resource exists; step 5 only pushes them. Docker Desktop must be running. The Go images compile the sqlboiler ORM code in your working tree, which is git-ignored, so regenerate it from each service's current schema first; a stale copy fails the build with `undefined: orm.…`. Each run starts a throwaway PostgreSQL in Docker. Git for Windows has no `make`: install it once with `winget install ezwinports.make`, then open a new terminal.

```sh
for s in account-service auditor client-profile-management-service sftp; do make -C services/$s sqlboiler; done
```

Give every image the same tag, and use a new tag each time (the repositories' tags are immutable), for example the commit SHA:

```sh
TAG=$(git rev-parse --short HEAD)   # echo $TAG to print it; step 3 writes it into env/prod.tfvars, and step 4 copies it from there
build() { docker build --platform linux/amd64 -t "crm/$1:$TAG" -f "$2" "$3"; }
build user-service        services/user-service/Dockerfile        .
build dashboard-service   services/dashboard-service/Dockerfile   .
build event-alert-service services/event-alert-service/Dockerfile .
build engagement-service  services/engagement-service/Dockerfile  .
build auditor             services/auditor/Dockerfile             .
build client-service      services/client-profile-management-service/Dockerfile services/client-profile-management-service
build account-service     services/account-service/Dockerfile     services/account-service
build transaction-service services/sftp/receiver/Dockerfile       services/sftp/receiver
build db-migrate          infrastructure/db-migrate/Dockerfile    .
```

`db-migrate` is Flyway with every service's database migrations, and the logins the services connect with. The task of each service with a database runs it before the service starts ([Schema migrations](../database/migrations.md)). Build it from the same commit as the other images: user-service's, event-alert-service's and engagement-service's migrations reach AWS only through it.

## 3. Create the CRM stack, stopped

In the same terminal as step 2, so `TAG` is still set (in a new one, set it again as in step 2), write the backend file from the bootstrap stack's `state_bucket` output, and put the image tag, the domain (when this account has its zone) and the audit roles of the stacks deployed beside this one in `env/prod.tfvars`. The roles are dashboard-service's, from the business-dashboard stack (step 4, [ADR-0047](../adr/ADR-0047-business-dashboard-terraform-stack.md)), and engagement-service's, from the agent-engagement stack (step 4a, [ADR-0051](../adr/ADR-0051-engagement-service-and-mail-dispatcher.md)); the audit queue refuses their events, and those stacks refuse to plan, until they are listed here:

```sh
cd infrastructure/terraform/stacks/crm
STATE_BUCKET=$(terraform -chdir=../../bootstrap output -raw state_bucket); echo "$STATE_BUCKET"
sed "s/^bucket *=.*/bucket       = \"${STATE_BUCKET:?no state_bucket output: finish step 1}\"/" env/prod.s3.tfbackend.example > env/prod.s3.tfbackend
[ -f env/prod.tfvars ] || cp env/prod.tfvars.example env/prod.tfvars   # keeps an existing file
sed -i -E '/^(enable_ingest_lambda|ingest_lambda_zip|sftp_server_address|sftp_host_key_fingerprint) *=/d' env/prod.tfvars   # step 9.3 adds them back
sed -i "s/^image_tag *=.*/image_tag = \"${TAG:?set TAG as in step 2}\"/" env/prod.tfvars
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
sed -i '/^extra_audit_producer_role_arns *=/d' env/prod.tfvars   # replaces an older line
printf '%s\n' "extra_audit_producer_role_arns = [\"arn:aws:iam::${ACCOUNT:?not signed in}:role/crm-sg-prod-dashboard-service-task\", \"arn:aws:iam::${ACCOUNT}:role/crm-sg-prod-engagement-service-task\"]" >> env/prod.tfvars
DOMAIN=$(aws route53 list-hosted-zones-by-name --dns-name itsag3t6.com \
  --query 'HostedZones[?Name==`"itsag3t6.com."` && Config.PrivateZone==`false`].Name' --output text | sed 's/\.$//')
sed -i '/^domain_name *=/d' env/prod.tfvars   # replaces an older line
[ -z "$DOMAIN" ] || printf 'domain_name = "%s"\n' "$DOMAIN" >> env/prod.tfvars
echo "domain: ${DOMAIN:-none, so the app uses its *.cloudfront.net address}"
grep -E '^(image_tag|root_admin_email|alarm_email|enable_ingest_lambda|extra_audit_producer_role_arns|domain_name)' env/prod.tfvars
```

The roles' names start with the stack's prefix, `crm-<legal_entity>-<environment>`; change `crm-sg-prod` for another stack.

`DOMAIN` is `itsag3t6.com` in the project account, which has that domain's public hosted zone. In an account without it, such as a personal one, `DOMAIN` is empty and no `domain_name` line is written, so the same commands deploy without a domain. To use a domain you registered yourself, replace the lookup with `DOMAIN=yourdomain.com`. If the `echo` line says `none` in the project account, the hosted zone is missing ([Register a domain](#register-a-domain-once-per-account)) or the lookup failed (check for an error above it). Fix that first, since the stack would otherwise deploy without the domain.

**The second `sed` line deletes step 9.3's four lines** (`enable_ingest_lambda`, `ingest_lambda_zip`, `sftp_server_address` and `sftp_host_key_fingerprint`), which an `env/prod.tfvars` from an earlier deployment still has, since the `cp` line keeps an existing file. Their commented-out copies from the example file stay. Those lines point at the old account's SFTP server, and step 9.3 adds them back with the new stack's values. Left in, they would make this apply create the ingest Lambda early, which a new account's Lambda concurrency quota of 10 can refuse ("decreases account's UnreservedConcurrentExecution below its minimum value of [10]"). If you run this block again after step 9.3, for example to fix the audit roles, run step 9.3's item 2 again before the next apply, or that apply deletes the ingest Lambda.

Then set `root_admin_email` and `alarm_email` in `env/prod.tfvars` by hand: they are your mailboxes, not Terraform outputs. dashboard-service's image tag is set in the business-dashboard stack, so an `image_tags` entry for it here does nothing.

Create the stack, still in `stacks/crm`:

```sh
terraform init -backend-config=env/prod.s3.tfbackend
terraform apply -var-file=env/prod.tfvars -var running=false
cd -
```

`running=false` creates everything but starts no task and no NAT gateway, so nothing pulls images yet.

**Always create a stack stopped, as here, never with `running=true`.** The services can call each other only because of it:
- A task can call another service through Service Connect only if that service existed when its own service's latest deployment was made.
- This apply creates the services together, so their first deployments can miss each other. client-service and account-service call each other, so no order of creation avoids that.
- Starting the stack (step 6) redeploys every service before any of its tasks start, once all of them exist, so the tasks get every name (ADR-0042 decision 4).
- A stack whose first apply had `running=true` starts its tasks on those first deployments instead. Its services then fail with `UnknownHostException` until they are redeployed ([Troubleshooting](#troubleshooting)).

The business-dashboard stack follows this stack's `running`, so step 4 creates it stopped too.

With `domain_name`, the apply also requests two certificates, `sg.<domain>` (in us-east-1, for CloudFront) and `alb.sg.<domain>` (for the internal ALB), and waits for ACM to validate them through DNS, usually a few minutes. Confirm the alarm e-mail subscription. Later steps read the stack's outputs (the cluster name, the app URL and the rest) with `terraform output` themselves, so there is nothing to note down.

**Staging (optional).** Staging is a second, separate copy of the stack for short promotion tests (ADR-0029): Fargate Spot, one Availability Zone, no WAF, no AWS Backup and no deletion protection. A test deployment does not need it, and running both doubles the cost.

- To create it, repeat this step and step 4 with `env/staging.s3.tfbackend` and `env/staging.tfvars`, and use `staging.tfvars` in every later command for it. The same `domain_name` puts staging at `sg-staging.<domain>`, beside prod.
- The backend file decides which stack's state this directory works on, and the scripts read that same state. To switch environments, run `terraform init -reconfigure -backend-config=env/<environment>.s3.tfbackend` first.
- Never apply one environment's tfvars while the directory is initialised on the other's backend: Terraform would try to turn that stack into the other one.

## 4. Create the business-dashboard stack, stopped

This stack deploys dashboard-service into the CRM stack's cluster ([ADR-0047](../adr/ADR-0047-business-dashboard-terraform-stack.md)). It reads the CRM stack's state, so apply it after step 3. It takes the image tag from the CRM stack's `env/prod.tfvars`, so both stacks deploy the same tag:

```sh
cd infrastructure/terraform/stacks/business-dashboard
STATE_BUCKET=$(terraform -chdir=../../bootstrap output -raw state_bucket); echo "$STATE_BUCKET"
TAG=$(sed -n 's/^image_tag *= *"\([^"]*\)".*/\1/p' ../crm/env/prod.tfvars); echo "$TAG"
sed "s/^bucket *=.*/bucket       = \"${STATE_BUCKET:?no state_bucket output: finish step 1}\"/" env/prod.s3.tfbackend.example > env/prod.s3.tfbackend
[ -f env/prod.tfvars ] || cp env/prod.tfvars.example env/prod.tfvars   # keeps an existing file
sed -i -e "s/^state_bucket *=.*/state_bucket = \"$STATE_BUCKET\"/" -e "s/^image_tag *=.*/image_tag = \"${TAG:?no image_tag in crm/env/prod.tfvars: finish step 3}\"/" env/prod.tfvars
terraform init -backend-config=env/prod.s3.tfbackend
terraform apply -var-file=env/prod.tfvars
cd -
```

It takes the task count from the CRM stack, so, like step 3, this apply starts no task. If the plan stops with `The audit queue does not allow arn:aws:iam::…`, the CRM stack was applied without that ARN in `extra_audit_producer_role_arns`: run step 3's first block again, apply the CRM stack, then this step.

## 4a. Create the mail identity and the agent-engagement stack, stopped

Agent engagement ([ADR-0051](../adr/ADR-0051-engagement-service-and-mail-dispatcher.md) to [ADR-0054](../adr/ADR-0054-ai-follow-up-drafts-and-holiday-data.md)) sends calendar invites, reminders and client emails through SES. With the CRM stack's `domain_name`, mail comes from `no-reply@mail.<app host>`, signed with the domain's DKIM. In an account without the domain's hosted zone, mail comes from one verified address you choose, `sender_address`, with no DNS records ([ADR-0055](../adr/ADR-0055-sender-address-without-a-domain.md)). Receivers check that address's domain's DMARC policy, so mail from an address at a domain that publishes `p=quarantine` or `p=reject` (many webmail providers do) may go to spam or be refused. Check with `nslookup -type=TXT _dmarc.<domain>`, and prefer a domain with `p=none` or no DMARC record.

**The mail identity.** Like every stack, it is created in each deployment and destroyed in its teardown ([ADR-0058](../adr/ADR-0058-every-deployment-ends-with-full-teardown.md) decision 3), so the verification below is repeated every time. Apply it early: DKIM can take up to an hour, and each inbox must click a new link. The commands set `domain_name` as step 3 does, when this account has the zone. Put the team inboxes that may receive mail in `verified_recipients` in `env/prod.tfvars` by hand, and, without a domain, `sender_address` too: real addresses only there, never in the example:

```sh
cd infrastructure/terraform/stacks/mail-identity
STATE_BUCKET=$(terraform -chdir=../../bootstrap output -raw state_bucket); echo "$STATE_BUCKET"
sed "s/^bucket *=.*/bucket       = \"${STATE_BUCKET:?no state_bucket output: finish step 1}\"/" env/prod.s3.tfbackend.example > env/prod.s3.tfbackend
[ -f env/prod.tfvars ] || cp env/prod.tfvars.example env/prod.tfvars   # then set verified_recipients
DOMAIN=$(sed -n 's/^domain_name *= *"\([^"]*\)".*/\1/p' ../crm/env/prod.tfvars)
sed -i '/^domain_name *=/d' env/prod.tfvars   # the CRM stack's, or none
[ -z "$DOMAIN" ] || printf 'domain_name = "%s"\n' "$DOMAIN" >> env/prod.tfvars
echo "domain: ${DOMAIN:-none, so set sender_address in env/prod.tfvars}"
terraform init -backend-config=env/prod.s3.tfbackend
terraform apply -var-file=env/prod.tfvars
terraform output dkim_status   # with a domain: PENDING until SES finds the DKIM records, usually within an hour
cd -
```

Each address in `verified_recipients`, and `sender_address` without a domain, receives an email from Amazon Web Services; click its link. The link from an earlier deployment no longer works, since that identity was destroyed. In the SES sandbox, mail reaches only those addresses. **That includes the agents, not only the clients:** invites and reminders go to the agent's Cognito email (the address they sign in with), and follow-up emails go to the client's email in client-service, so list both, or give agents and demo clients addresses that are already listed (synthetic data only). An unlisted address fails with `ses_rejected` (`Email address is not verified`), and that mail is not retried. Apply this stack again to add or remove an address. To see what is verified:

```sh
aws sesv2 list-email-identities --query 'EmailIdentities[].[IdentityName,VerificationStatus]' --output table
```

**The agent-engagement stack.** Like step 4, it reads the CRM stack's state and takes the image tag from the CRM stack's `env/prod.tfvars`. It copies `sender_address` from mail-identity's `env/prod.tfvars`, so both stacks use the same address; with a domain there is none, and the stack ignores it anyway:

```sh
cd infrastructure/terraform/stacks/agent-engagement
STATE_BUCKET=$(terraform -chdir=../../bootstrap output -raw state_bucket); echo "$STATE_BUCKET"
TAG=$(sed -n 's/^image_tag *= *"\([^"]*\)".*/\1/p' ../crm/env/prod.tfvars); echo "$TAG"
sed "s/^bucket *=.*/bucket       = \"${STATE_BUCKET:?no state_bucket output: finish step 1}\"/" env/prod.s3.tfbackend.example > env/prod.s3.tfbackend
[ -f env/prod.tfvars ] || cp env/prod.tfvars.example env/prod.tfvars   # keeps an existing file
sed -i -e "s/^state_bucket *=.*/state_bucket = \"$STATE_BUCKET\"/" -e "s/^image_tag *=.*/image_tag = \"${TAG:?no image_tag in crm/env/prod.tfvars: finish step 3}\"/" env/prod.tfvars
SENDER=$(sed -n 's/^sender_address *= *"\([^"]*\)".*/\1/p' ../mail-identity/env/prod.tfvars)
sed -i '/^sender_address *=/d' env/prod.tfvars   # mail-identity's, or none
[ -z "$SENDER" ] || printf 'sender_address = "%s"\n' "$SENDER" >> env/prod.tfvars
echo "sender: ${SENDER:-none, so mail comes from the domain}"
terraform init -backend-config=env/prod.s3.tfbackend
terraform apply -var-file=env/prod.tfvars
cd -
```

It creates engagement-db (a `db.t4g.micro`, about USD 0.02 to 0.03 an hour while it runs) and starts no task while the CRM stack is stopped. `ai_enabled = false` in `env/prod.tfvars` keeps AI drafting off; turn it on only for a test or demo run, and re-check the Nova Micro profile's regions first (`aws bedrock get-inference-profile --inference-profile-identifier apac.amazon.nova-micro-v1:0`) against ADR-0054. If the plan stops with `The audit queue does not allow ...`, run step 3's first block again and apply the CRM stack first; if it cannot read the SES identity or configuration set, apply the mail identity first. If it stops with `The CRM stack has no domain_name`, set `sender_address` in mail-identity's `env/prod.tfvars`, apply that stack, and run this block again.

## 5. Push the images

Steps 3, 4 and 4a created the ECR repositories: the CRM stack's in step 3, dashboard-service's in step 4, engagement-service's in step 4a. From the repository root, with Docker Desktop running, push step 2's images to them. `TAG` is read from `env/prod.tfvars`, so the pushed tag is the one Terraform deploys:

```sh
TAG=$(sed -n 's/^image_tag *= *"\([^"]*\)".*/\1/p' infrastructure/terraform/stacks/crm/env/prod.tfvars); echo "$TAG"
REPOS=$(terraform -chdir=infrastructure/terraform/stacks/crm output -json ecr_repositories)
repo() { printf '%s' "$REPOS" | python -c "import json,sys; print(json.load(sys.stdin)['$1'])"; }
aws ecr get-login-password | docker login --username AWS --password-stdin "$(repo auditor | cut -d/ -f1)"

push() { docker tag "crm/$1:$TAG" "$(repo "$1"):$TAG" && docker push "$(repo "$1"):$TAG"; }
for s in user-service event-alert-service auditor client-service account-service transaction-service db-migrate; do push "$s"; done

DASHBOARD=$(terraform -chdir=infrastructure/terraform/stacks/business-dashboard output -raw ecr_repository)
docker tag "crm/dashboard-service:$TAG" "$DASHBOARD:$TAG" && docker push "$DASHBOARD:$TAG"

ENGAGEMENT=$(terraform -chdir=infrastructure/terraform/stacks/agent-engagement output -raw ecr_repository)
docker tag "crm/engagement-service:$TAG" "$ENGAGEMENT:$TAG" && docker push "$ENGAGEMENT:$TAG"
```

## 6. Start the stacks

```sh
cd infrastructure/terraform/stacks/crm
terraform apply -var-file=env/prod.tfvars -var running=true
cd -
terraform -chdir=infrastructure/terraform/stacks/business-dashboard apply -var-file=env/prod.tfvars   # takes running=true from the CRM stack
terraform -chdir=infrastructure/terraform/stacks/agent-engagement apply -var-file=env/prod.tfvars     # the same
```

The databases need nothing by hand. Each service's task runs its `db-migrate` container first. It applies the database's migrations, then creates the service's own login, whose password stays in Secrets Manager ([Database logins](../database/migrations.md#database-logins)). No service connects as its database's master user. The ingest Lambda's login is created by transaction-service's db-migrate.

**No service needs a restart afterwards.** Each apply redeploys every service in its stack before starting its tasks, so they can call each other through Service Connect. This works only because step 3 created the stack stopped. Each plan shows every `aws_ecs_service` updating in place, which is expected.

## 7. Upload the frontend

```sh
cd frontend
eval "$(terraform -chdir=../infrastructure/terraform/stacks/crm output -json frontend_build_env | python -c "import json,sys; print('\n'.join(f'export {k}=\"{v}\"' for k,v in json.load(sys.stdin).items()))")"
npm ci && npm run build
aws s3 sync dist/ "s3://$(terraform -chdir=../infrastructure/terraform/stacks/crm output -raw frontend_bucket)" --delete
aws cloudfront create-invalidation --distribution-id "$(terraform -chdir=../infrastructure/terraform/stacks/crm output -raw cloudfront_distribution_id)" --paths '/*' # Clears cloudfront cache so it gets the new frontend
cd -
```

## 8. Check it works

1. **Services:** every service reaches its desired count, in the ECS console or with:

   ```sh
   STACK=$(terraform -chdir=infrastructure/terraform/stacks/crm output -raw ecs_cluster)   # crm-<legal_entity>-<environment>
   aws ecs describe-services --cluster "$STACK" \
     --services user-service client-service account-service auditor transaction-service dashboard-service event-alert-service engagement-service \
     --query 'services[].[serviceName,runningCount,desiredCount]' --output table
   ```

   A service stuck restarting shows why in its log group (`/ecs/$STACK-<service>`): a `tls-init` failure usually means no NAT gateway (the stack is stopped); for a `db-migrate` failure, its `db-migrate/…` log stream gives Flyway's reason ([Troubleshooting](#troubleshooting)).
2. **Sign in:** open the URL that `terraform -chdir=infrastructure/terraform/stacks/crm output -raw app_url` prints: `https://sg.<domain>` with a domain, or the `*.cloudfront.net` address without one. user-service creates the root admin from `root_admin_email` on its first start, and Cognito e-mails a temporary password. Sign in, set a new password, and enrol an authenticator app (MFA is required).
3. **Audit:** create a client, then open the activity feed. The change appears within seconds. A message in the audit dead-letter queue, or the `audit-oldest-message` alarm, means the auditor is not consuming.
4. **Sign-in audit (Cognito triggers):** the sign-in in item 2 ran the trigger. Read its log:

   ```sh
   MSYS_NO_PATHCONV=1 aws logs tail "/aws/lambda/$STACK-cognito-triggers" --since 30m
   ```

   1. **Login:** each sign-in logs `AUDIT_SENT` with `"action":"LOGIN"`.
   2. **Password reset:** sign out and use "Forgot password". The reset logs `AUDIT_SENT` with `"action":"PASSWORD_RESET"`, and it shows in the activity feed.
   3. **Failures:** `AUDIT_SEND_FAILED` means the user signed in but the event was not sent. Its `error` field says why ([Replaying failed events](../../services/cognito-triggers/README.md#replaying-failed-events)).
   4. **Cold start:** the `REPORT` line's `Init Duration` plus `Duration` should be well under Cognito's 5-second limit.

   Test with real sign-ins, not `aws lambda invoke` with the sample LOGIN or PASSWORD_RESET events: those would put a made-up user's login into the audit chain, and it cannot be removed. `confirm-sign-up.json` is safe to invoke, since the trigger ignores that event.
5. **Agent engagement:** as an agent whose Cognito address is a verified recipient, create a meeting in Calendar. Within about 2 minutes the invite reaches that mailbox and adds the meeting to the agent's calendar; moving or cancelling it updates or removes the entry. Follow-up Emails writes a template draft while `ai_enabled` is off.

## 9. The external SFTP server and the ingest Lambda

The sftp-server stack is the bank's SFTP server ([ADR-0024](../adr/ADR-0024-transaction-ingestion-sftp-lambda.md) decision 1). transaction-ingest, a Lambda function in the crm stack, imports its files. Each stack needs the other's outputs, so the order is: crm (steps 3–6), then sftp-server, then crm again. Run the commands below in Git Bash, from the repository root.

### 9.1 Create the SFTP server

1. **Backend.** Write it from the example, with the bootstrap stack's `state_bucket` output, the same bucket as the crm stack's:

   ```sh
   cd infrastructure/terraform/stacks/sftp-server
   STATE_BUCKET=$(terraform -chdir=../../bootstrap output -raw state_bucket); echo "$STATE_BUCKET"
   sed "s/^bucket *=.*/bucket       = \"${STATE_BUCKET:?no state_bucket output: finish step 1}\"/" env/prod.s3.tfbackend.example > env/prod.s3.tfbackend
   cd -
   ```

   Keep `key = "sftp-server/prod.tfstate"`: it must differ from the crm stack's key, or the two stacks overwrite each other's state.

2. **Variables.** `env/prod.tfvars` takes two values from the crm stack's outputs:

   | Variable | Value |
   |---|---|
   | `environment` | `"prod"`, as the crm stack |
   | `allowed_cidrs` | The crm output `nat_public_ips`, each as a `/32`. Port 22 accepts only these addresses |
   | `crm_ingest_public_key` | The crm output `sftp_client_public_key`. The private half is in Secrets Manager, for the Lambda |
   | `host_key_file` | Optional. Set it only if you were given a host key for the server; otherwise Terraform generates one. Save the key in the git-ignored `secrets/` folder at the repository root, and give its path relative to `stacks/sftp-server`, such as `"../../../../secrets/sftp_host_key"`. It must be an unencrypted OpenSSH private key (its first line is `-----BEGIN OPENSSH PRIVATE KEY-----`) |
   | `producer_image` | Leave unset for now (9.2) |

   This writes the file from the outputs:

   ```sh
   cd infrastructure/terraform/stacks
   NAT=$(terraform -chdir=crm output -json nat_public_ips | python -c "import json,sys; print(', '.join(f'\"{ip}/32\"' for ip in json.load(sys.stdin)))")
   KEY=$(terraform -chdir=crm output -raw sftp_client_public_key | tr -d '\r\n')
   printf '%s\n' \
     'environment           = "prod"' \
     "allowed_cidrs         = [$NAT]" \
     "crm_ingest_public_key = \"$KEY\"" > sftp-server/env/prod.tfvars
   cat sftp-server/env/prod.tfvars
   cd -
   ```

   Read a single output with `-raw`. Plain `terraform output` prints a value that ends in a newline as `<<EOT`, with the value on the lines below. The snippet overwrites the file, so add any `host_key_file` line after running it.

   These snippets write files with `printf`, not a here-document (`<<EOF`): copied with this list's indentation, a here-document's closing `EOF` is indented too, and the shell waits for it at a `>` prompt.

3. **Apply:**

   ```sh
   cd infrastructure/terraform/stacks/sftp-server
   terraform init -backend-config=env/prod.s3.tfbackend
   terraform apply -var-file=env/prod.tfvars
   terraform output   # sftp_server_address, sftp_host_key_fingerprint, producer_repository_url, instance_id
   cd -
   ```

### 9.2 Start the producer

The producer is the bank's file generator. It needs the ECR repository that 9.1 created, so it is a second apply.

1. Build it for the instance's arm64 CPU and push it. The repository's tags are immutable, so use a new tag each time:

   ```sh
   URL=$(terraform -chdir=infrastructure/terraform/stacks/sftp-server output -raw producer_repository_url)
   PTAG=$(git rev-parse --short HEAD)
   aws ecr get-login-password | docker login --username AWS --password-stdin "${URL%%/*}"
   docker build --platform linux/arm64 -t "$URL:$PTAG" services/sftp/producer
   docker push "$URL:$PTAG"
   ```

2. Once the push has succeeded, add the image to `sftp-server/env/prod.tfvars`. Run this in the same terminal, as it uses step 1's `URL` and `PTAG`. It replaces any older `producer_image` line, since Terraform refuses an attribute set twice:

   ```sh
   : "${URL:?set URL as in step 1}" "${PTAG:?set PTAG as in step 1}" &&
   sed -i -E '/^producer_image *=/d' infrastructure/terraform/stacks/sftp-server/env/prod.tfvars &&
   printf '%s\n' "producer_image = \"$URL:$PTAG\"" >> infrastructure/terraform/stacks/sftp-server/env/prod.tfvars
   tail -1 infrastructure/terraform/stacks/sftp-server/env/prod.tfvars
   ```

   The line looks like `producer_image = "123456789012.dkr.ecr.ap-southeast-1.amazonaws.com/core-banking-sftp-prod/producer:6f606ec"`. In a new terminal `URL` and `PTAG` are empty: set `URL` as in step 1, then list the tags already pushed with `aws ecr list-images --repository-name "${URL#*/}" --query 'imageIds[].imageTag' --output text`.

3. Apply sftp-server again. Changing `producer_image` replaces the instance (its startup script changes), so the plan must show `aws_instance.sftp` being replaced. The host key stays the same, so its fingerprint does too. Without this apply, the instance has no Docker and no producer, and no transactions are written.

   ```sh
   terraform -chdir=infrastructure/terraform/stacks/sftp-server apply -var-file=env/prod.tfvars
   ```

The producer starts with an empty account list and publishes nothing until you give it the CRM's accounts (9.5).

### 9.3 Turn on the ingest Lambda

transaction-ingest imports the files ([ADR-0024](../adr/ADR-0024-transaction-ingestion-sftp-lambda.md), INF-04).

1. Build its zip. Docker must be running:

   ```sh
   make -C services/sftp lambda   # services/sftp/receiver/dist/transaction-ingest.zip
   ```

2. Copy the sftp-server outputs into the crm stack's `env/prod.tfvars`. This replaces any older `enable_ingest_lambda`, `ingest_lambda_zip`, `sftp_server_address` or `sftp_host_key_fingerprint` lines, such as ones from an earlier deployment, since Terraform refuses an attribute set twice:

   ```sh
   cd infrastructure/terraform/stacks
   ADDR=$(terraform -chdir=sftp-server output -raw sftp_server_address)
   FP=$(terraform -chdir=sftp-server output -raw sftp_host_key_fingerprint)
   : "${ADDR:?no sftp-server output: finish 9.1}" "${FP:?no sftp-server output: finish 9.1}" &&
   sed -i -E '/^(enable_ingest_lambda|ingest_lambda_zip|sftp_server_address|sftp_host_key_fingerprint) *=/d' crm/env/prod.tfvars &&
   printf '%s\n' \
     'enable_ingest_lambda      = true' \
     'ingest_lambda_zip         = "../../../../services/sftp/receiver/dist/transaction-ingest.zip"' \
     "sftp_server_address       = \"$ADDR\"" \
     "sftp_host_key_fingerprint = \"$FP\"" >> crm/env/prod.tfvars
   cd -
   ```

   `sftp_server_address` looks like `13.250.0.10:22`, and `sftp_host_key_fingerprint` like `SHA256:AbC…`. `ingest_lambda_zip` is relative to `stacks/crm`. A plan fails if any of the four is missing.
3. Apply the crm stack again. Keep `running` as it is now, `true` since step 6: a different value starts or stops the whole stack, not only the Lambda.

   ```sh
   cd infrastructure/terraform/stacks/crm
   terraform apply -var-file=env/prod.tfvars -var running=true
   cd -
   ```

4. Run it once without waiting for the schedule, and read its log:

   ```sh
   STACK=$(terraform -chdir=infrastructure/terraform/stacks/crm output -raw ecs_cluster)   # crm-<legal_entity>-<environment>
   aws lambda invoke --function-name "$STACK-transaction-ingest" out.json && cat out.json
   MSYS_NO_PATHCONV=1 aws logs tail "/aws/lambda/$STACK-transaction-ingest" --since 10m
   ```

   The cluster is named after the stack, `crm-sg-prod` by default. `MSYS_NO_PATHCONV=1` stops Git Bash from turning the log group's leading `/` into `C:/Program Files/Git/`, which AWS rejects; other shells ignore it.

   A successful run writes `null`; a failed one writes an `errorMessage`, which the log explains. Files move from the SFTP server's `reports/` to `processed/` or `rejected/`, and the import's audit events reach the auditor.

To rebuild after a code change, run `make lambda` and apply the crm stack again: the zip's hash changes, so Terraform updates the function.

### 9.4 When the import cannot reach the server

- **The run fails at init:** the log names the secret or variable it could not read.
- **`connect to SFTP server` times out:** `allowed_cidrs` does not hold every crm `nat_public_ips` address, or the NAT gateway is down because the crm stack is stopped. `high_availability = true` adds a second NAT address: after turning it on, rerun the 9.1 snippet and apply sftp-server again, or runs in the second AZ time out.
- **`host key fingerprint mismatch`:** the server's host key changed, and crm still pins the old one. The key changes when:
  - the sftp-server stack is destroyed and created again without `host_key_file`, which generates a new key;
  - `host_key_file` is set, changed or removed. Terraform then replaces the instance, because the key is installed only at first boot. The address stays the same.

  Repeat 9.3 step 2 and apply crm.
- **To check the fingerprint on the server:** port 22 accepts only the NAT addresses, so `ssh-keyscan` from your machine cannot reach it. Use Session Manager instead; the `SHA256:…` it prints must equal the `sftp_host_key_fingerprint` output:

  ```sh
  aws ssm start-session --target "$(terraform -chdir=infrastructure/terraform/stacks/sftp-server output -raw instance_id)"
  sudo ssh-keygen -lf "$(sudo sshd -T | awk '$1 == "hostkey" {print $2; exit}').pub"   # the key sshd uses, whatever its type
  ```

### 9.5 Give the producer the CRM's accounts

The producer writes transactions only for the accounts in its list, and nothing while the list is empty ([ADR-0045](../adr/ADR-0045-producer-account-list-synced-from-crm.md)). It cannot read the CRM itself, so copy the accounts to it once some exist:

1. Open at least one account in the deployed app: on a client's page, **Open account**.
2. Run the sync. The crm stack must be running, because the script reads the accounts database through `scripts/db-run-sql.sh`:

   ```sh
   scripts/sync-producer-accounts.sh
   ```

   It prints how many accounts it synced: those neither closed nor deleted, at most the 100 most recently opened. The producer uses them from its next file, within `publish_interval_seconds` (5 minutes by default).

Run it again after opening, closing or deleting accounts, and after the sftp-server stack replaces the instance, which starts with an empty list. Stopping the instance between sessions keeps the list. To see the list on the server, open a Session Manager shell (9.4) and run `cat /etc/producer/account-ids`; `sudo docker logs --tail 5 producer` says when a run published nothing.

## Pausing a deployment

To pause a deployment that runs over several sessions, such as overnight, stop it and start it again later. A pause is not the end of a deployment: when you are done, [tear it down](#tear-down), so the next person starts from an empty account.

```sh
# stop
terraform -chdir=infrastructure/terraform/stacks/crm apply -var-file=env/prod.tfvars -var running=false
terraform -chdir=infrastructure/terraform/stacks/business-dashboard apply -var-file=env/prod.tfvars
terraform -chdir=infrastructure/terraform/stacks/agent-engagement apply -var-file=env/prod.tfvars
scripts/aws-session.sh stop
# start
scripts/aws-session.sh start
terraform -chdir=infrastructure/terraform/stacks/crm apply -var-file=env/prod.tfvars -var running=true
terraform -chdir=infrastructure/terraform/stacks/business-dashboard apply -var-file=env/prod.tfvars
terraform -chdir=infrastructure/terraform/stacks/agent-engagement apply -var-file=env/prod.tfvars
```

- Stopping scales every service to 0, pauses the schedules, removes the NAT gateway (its Elastic IP stays) and stops the databases and the SFTP server.
- The business-dashboard and agent-engagement stacks read `running` from the CRM stack's state, so apply them after the CRM stack every time. Skipped after a stop, dashboard-service keeps one task running, at about USD 1.50 a day. Skipped after a start, it stays at 0 tasks.
- Starting and stopping each redeploy every service, for Service Connect (step 3), so each plan shows every `aws_ecs_service` updating in place. A stop briefly starts one new task per service, which the scale to 0 stops within seconds.
- The script also stops and starts engagement-db when the agent-engagement stack exists. While stopped, no invite or reminder is sent; a reminder whose meeting starts in the meantime is recorded as missed (ADR-0051 decision 6).
- Starting takes several minutes, mostly the databases.
- RDS starts a stopped instance again after 7 days. A deployment paused that long should be torn down instead.
- For the high-availability demo week, add `-var high_availability=true`.

## Operations

- **Replay a login that was not audited**, or redrive the audit dead-letter queue: assume the `audit_replay_role_arn` output's role. The queue accepts messages only from producer roles and this one.
- **Database console:** `scripts/db-run-sql.sh <database> <file.sql>` runs any SQL file as the master user.

## Tear down

Every deployment ends here, and destroys every stack ([ADR-0058](../adr/ADR-0058-every-deployment-ends-with-full-teardown.md)). Run the four parts in order, in one terminal, from the repository root. Take a manual snapshot first only if you need data from this deployment: nothing survives it.

**1. The app stacks:**

```sh
STACK=$(terraform -chdir=infrastructure/terraform/stacks/crm output -raw ecs_cluster); echo "$STACK"   # part 2 needs it after the outputs are gone
terraform -chdir=infrastructure/terraform/stacks/sftp-server destroy -var-file=env/prod.tfvars
terraform -chdir=infrastructure/terraform/stacks/crm apply   -var-file=env/prod.tfvars -var deletion_protection=false
terraform -chdir=infrastructure/terraform/stacks/business-dashboard apply   -var-file=env/prod.tfvars
terraform -chdir=infrastructure/terraform/stacks/business-dashboard destroy -var-file=env/prod.tfvars
terraform -chdir=infrastructure/terraform/stacks/agent-engagement apply   -var-file=env/prod.tfvars
terraform -chdir=infrastructure/terraform/stacks/agent-engagement destroy -var-file=env/prod.tfvars
terraform -chdir=infrastructure/terraform/stacks/crm destroy -var-file=env/prod.tfvars -var deletion_protection=false
```

- **Destroy business-dashboard before crm.** Its rules sit on the CRM stack's security groups and its service runs in the CRM's cluster, so the CRM stack cannot be destroyed while it exists. Its apply after the CRM's `deletion_protection=false` apply lets its destroy empty dashboard-service's image repository.
- **Destroy agent-engagement before crm too,** for the same reasons: its rule sits on client-service's group, it runs in the CRM's cluster, and engagement-db uses the CRM's subnet group, parameter group and KMS key. Its apply after the CRM's `deletion_protection=false` apply turns off engagement-db's deletion protection and final snapshot.
- **Prod first needs its protections lifted:** the first crm apply above, with `-var deletion_protection=false`, turns off deletion protection on the databases, the client-keys table, the ALB and the user pool, and lets destroy empty the buckets. It also makes destroy delete the secrets at once and skip the databases' final snapshots. Staging has none to lift.
- **The audit anchor bucket keeps each anchor locked for 7 days** (Object Lock, governance mode). If the crm destroy stops on it, remove the object versions as an administrator with `s3:BypassGovernanceRetention` (`aws s3api delete-object --bypass-governance-retention --version-id ...`), and run the destroy again.
- **The certificates and DNS records go with the crm stack.** The domain and its hosted zone stay: they are outside Terraform ([Register a domain](#register-a-domain-once-per-account)).

**2. Hard delete any secret still pending deletion.** The stacks' secrets have fixed names (`crm-<entity>-<environment>/...`, which includes business-dashboard's `.../tls/dashboard`), so a secret still in its recovery window makes the next deployment's apply fail with `a secret with this name is already scheduled for deletion`. Secrets are left like this when destroy runs without the `deletion_protection=false` apply first. This deletes them at once, and does nothing if there are none. It uses `STACK` from part 1; in a new terminal the destroyed stack has no outputs, so set `STACK=crm-<legal_entity>-<environment>` (`crm-sg-prod` by default) instead:

  ```sh
  for s in $(aws secretsmanager list-secrets --include-planned-deletion --filters Key=name,Values="${STACK:?set STACK}/" \
      --query 'SecretList[?DeletedDate].Name' --output text); do
    aws secretsmanager delete-secret --force-delete-without-recovery --secret-id "$s"
  done
  ```

  A hard-deleted secret cannot be restored. The databases' master secrets (`rds!db-...`) are deleted with their instances and never conflict.

**3. The mail identity, then `bootstrap`.** `bootstrap`'s state bucket holds every other stack's state, so it goes last, once `terraform state list` prints nothing in each of them. The bucket is versioned and has no `force_destroy`, so `terraform destroy` stops with `BucketNotEmpty` until every object version and delete marker is deleted:

```sh
terraform -chdir=infrastructure/terraform/stacks/mail-identity destroy -var-file=env/prod.tfvars
cd infrastructure/terraform/bootstrap
bucket=$(terraform output -raw state_bucket); echo "${bucket:?no state_bucket output}"
aws s3api delete-objects --bucket "$bucket" --delete "$(aws s3api list-object-versions --bucket "$bucket" \
  --query '{Objects: [Versions, DeleteMarkers][][].{Key: Key, VersionId: VersionId}, Quiet: `true`}' --output json)"
terraform destroy # If this fails, manually empty bucket in AWS Console S3
cd -
```

- `delete-objects` takes at most 1,000 versions per call. Old versions expire after 90 days, so the bucket rarely holds that many.
- Destroying `bootstrap` also removes the budgets and the CloudTrail trail and its bucket. CloudTrail's 90-day event history still shows who did what.
- The verified inboxes and DKIM go with `mail-identity`. The next deployment verifies them again (step 4a).

**4. Check the account is empty:** run the commands in [Check the account is empty](#check-the-account-is-empty). Each must print nothing. Anything left is the next person's problem, so remove it now: a listed stack resource means a destroy above stopped part way; run it again and fix the error it reports.

The next deployment starts with a new user pool and empty databases, so `root_admin_email` can change then.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `ExpiredToken` or "The security token included in the request is expired" | The 4-hour session is over. Start a new one ([AWS access](#aws-access)) and run the step again |
| `NoRegion: You must specify a region`, then `password is empty` from `docker login` (step 5) | The terminal has the session's keys but no region: the `export-credentials` line does not copy the profile's region. Run `export AWS_REGION=ap-southeast-1` (PowerShell: `$env:AWS_REGION = 'ap-southeast-1'`), as in [Start each session](#start-each-session), and run the login line again |
| `docker push` says `denied: Your authorization token has expired` | The `docker login` line failed (look above it for `NoRegion` or `ExpiredToken`), so Docker used its stored ECR login, which expires after 12 hours. Fix that error and run the login line again; it must print `Login Succeeded` |
| `AccessDenied` calling `AssumeRole` | A wrong or reused MFA code, a wrong `mfa_serial`, or your user is not in `crm-deployers`. Check the profile with `aws configure list --profile crm` |
| Terraform says "No valid credential sources found", or mentions MFA or a token provider | The session's credentials are not in this terminal, or `AWS_PROFILE` is set. Run the session lines in this terminal ([AWS access](#aws-access)) |
| `No positional arguments are expected` or `Too many command line arguments` from Terraform | Windows PowerShell split an option at its first dot (`-backend-config=env/prod` and `.s3.tfbackend`). Quote the option, `"-backend-config=env/prod.s3.tfbackend"`, or use Git Bash |
| `failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine` (steps 2 and 5) | Docker Desktop is not running. Start it, wait for "Engine running" (`docker info` then succeeds), and run the step again. If it is running, switch it to Linux containers |
| `InstanceQuotaExceeded: ... free plan accounts` or `FreeTierRestrictionError` on an RDS instance | The account is on the AWS Free plan, which this stack does not support: upgrade it to the paid plan and apply again |
| `AccessDeniedException` with "explicit deny in a service control policy" on `CreateBackupVault` or `CreateLogGroup` | The DR vault or WAF logging was uncommented, and the account still refuses it. Comment it out again ([Terraform README](../../infrastructure/terraform/README.md#left-out-of-the-account)) |
| `a secret with this name is already scheduled for deletion` | A destroy ran without the `deletion_protection=false` apply before it, so the secrets got a 7-day recovery window. Hard delete them as in [Tear down](#tear-down), then apply again |
| A service's tasks stop with `CannotPullContainerError: ... not found` | `image_tag` in `env/prod.tfvars` is not a tag you pushed (the example's `replace-with-the-pushed-tag`, for instance), or that service's image was never pushed (step 5). Push it, set the tag and apply again. A failed deployment rolls back to the previous one, which keeps failing if it had the same problem, until the next apply |
| The producer writes no files; its log says `lists no CRM accounts` | Its account list is empty: the sync has not run since the instance was created, or the CRM has no open accounts. Open one and run `scripts/sync-producer-accounts.sh` (9.5) |
| `sync-producer-accounts.sh` stops with `could not read the accounts query's output` | The query ran, but its log could not be fetched in time. The producer's list is unchanged; run the script again |
| A meeting's invite stays `Queued` | engagement-service is not running (the stack is stopped, or the agent-engagement stack was not applied after the CRM's start), or `mail_enabled = false`. Mail waiting over 5 minutes raises the `engagement-dispatch-lagging` alarm |
| Invites or reminders show `Failed` with `agent_email_unavailable` | Cognito has no `email` for that agent, or `COGNITO_USER_POOL_ID` is wrong. Check the user in the pool |
| Mail shows `Failed` with `ses_rejected`, and the log says the address is not verified | The SES account is in the sandbox and the recipient is not a verified identity, or has not clicked its verification email. For an invite or reminder the recipient is the **agent's** Cognito email; for a follow-up email it is the client's. Add it to mail-identity's `verified_recipients`, apply mail-identity and click the link, then edit or create the meeting again: failed mail is not retried |
| The `engagement-holiday-data` alarm fires | engagement-service logged `holiday_coverage_short` (from October, no public holidays for next year yet) or `holiday_sync_mismatch` (a stored holiday that MOM's data.gov.sg list no longer has). Search the log for those words. For a short list, check whether MOM has published next year on data.gov.sg; the next daily sync or start adds it, and `holiday_sync_failed` in the log means data.gov.sg could not be read. For a mismatch, correct the row in a new migration (ADR-0057) |
| Mail fails with `ses_error`, or a draft send says the email service did not accept the message | engagement-service logs `SES refused the message (<code>): <AWS error> <status> <message>`, with addresses shown as `<from>` and `<address>`. `AccessDenied 403` names the resource the task role may not use. The `SendEngagementMail` policy allows every identity in the account and region (`identity/*`) and the configuration set, from the sender address only: apply the agent-engagement stack if it is older, and check the configuration set's name. With no such line, the log says `SES could not be reached`: check the NAT gateway |
| Every send fails with `ses_rejected` just after mail-identity was created, without a domain | `sender_address` has not clicked its verification email. Click it, or apply mail-identity again to resend it |
| Every send fails with `ses_rejected` just after mail-identity was created | DKIM is not verified yet (`terraform -chdir=infrastructure/terraform/stacks/mail-identity output dkim_status` is not `SUCCESS`). Wait for SES to find the records, usually within an hour |
| The agent-engagement plan cannot read the SES email identity or configuration set | The mail-identity stack was not applied for this environment, the CRM stack's `domain_name` differs from its `domain_name`, or, without a domain, the two stacks' `sender_address` differ (the agent-engagement block copies it; check for an edit made since). Apply mail-identity with the same values, and run the agent-engagement block again |
| Mail sent without a domain lands in spam or is refused | The sender address's domain publishes DMARC `p=quarantine` or `p=reject`, and SES signs with its own DKIM key, so DMARC fails (ADR-0055). Use an address at a domain with `p=none` or no DMARC record, or deploy in the account with the domain |
| `tls-init` exits non-zero | No internet: the stack is stopped, or the NAT gateway is still being created |
| A service that serves TLS refuses to start: client-service or account-service, or a Java service (user-, dashboard- or event-alert-service) naming `/tls/cert.pem` or `/tls/key.pem` | It needs the certificate files `tls-init` writes; check the `tls-init` log in the same task. client-service and account-service also need production mode's `verify-full` settings |
| A service logs `UnknownHostException: auditor` (or another service's name), and the activity feed or a dashboard panel says it is unavailable | The calling service's latest deployment was made before the service it calls existed. Either the stack's first apply had `running=true` instead of creating it stopped (step 3), or the called service was created or replaced since the stack last started. Redeploy the calling service: `aws ecs update-service --cluster "$(terraform -chdir=infrastructure/terraform/stacks/crm output -raw ecs_cluster)" --service <service> --force-new-deployment`. The next stop and start also fixes it |
| A service with a database never starts its app; its `db-migrate/…` log stream says `Migration checksum mismatch` | A migration file was edited after it was applied. Restore the file, put the change in the next version ([Schema migrations](../database/migrations.md)), then build and push db-migrate with a new tag and apply crm |
| The same, with `Found non-empty schema(s) "public" but no schema history table` | The database's schema was applied by hand, before db-migrate existed. Tear the stack down and create it again |
| The same, with `No value provided for placeholder` | Its task definition lacks the login secrets. Apply crm from current code |
| A service, or the ingest Lambda, logs `password authentication failed for user "<login>"` | Its login was not created: the pushed db-migrate image is older than the Terraform, so it has no login callback. Build and push db-migrate from the same commit (steps 2 and 5), set `image_tag`, and apply crm. The ingest Lambda also fails like this until transaction-service has started once with its login; its next run succeeds |
| A service logs `permission denied for table <table>` | Its login was not granted that table, because something created it outside a migration. Move the change into a migration ([Schema migrations](../database/migrations.md)); meanwhile, a redeploy runs db-migrate, which grants every table: `aws ecs update-service --cluster "$(terraform -chdir=infrastructure/terraform/stacks/crm output -raw ecs_cluster)" --service <service> --force-new-deployment` |
| Sign-in page says "Login pages unavailable" | The app client has no managed login style; re-apply the stack |
| `terraform output` shows `sftp_client_public_key = <<EOT` | The key ends in a newline, so it prints as a heredoc. Read it with `terraform output -raw sftp_client_public_key`, as in step 9.1 |
| `No outputs found` from the sftp-server stack | Its apply has not finished. Run it again and fix the error it reports |
| The business-dashboard plan stops with `The audit queue does not allow arn:aws:iam::…-dashboard-service-task` | The CRM stack's `extra_audit_producer_role_arns` is missing that ARN. Add it to `crm/env/prod.tfvars`, apply the CRM stack, then the business-dashboard stack (steps 3 and 4) |
| The business-dashboard plan fails to read the remote state (`Unable to find remote state`), or says the object has no attribute `dashboard_inputs` | The CRM stack it points at has not been applied, or was applied from code older than ADR-0047. Apply the CRM stack first, and check that `state_bucket`, `legal_entity` and `environment` in `business-dashboard/env/prod.tfvars` match the CRM stack's |
| `RepositoryNotEmptyException` destroying the business-dashboard stack | Its repository still has `force_delete` off. Apply the CRM stack with `-var deletion_protection=false`, apply the business-dashboard stack, then destroy it ([Tear down](#tear-down)) |
| `Required plugins are not installed` or `Inconsistent dependency lock file`, naming `hashicorp/tls`, in the business-dashboard stack | The directory was initialised before the stack used the `tls` provider for dashboard-service's certificate. Run its `terraform init -backend-config=env/prod.s3.tfbackend` again |
| The crm plan stops with `no matching Route 53 Hosted Zone found` | `domain_name` is not a hosted zone in this account: a typo, a registration that has not finished, or a deleted zone. `aws route53 list-hosted-zones-by-name --dns-name <domain>` lists it. To recreate a deleted zone, create a public zone with the same name, then set the domain's name servers to the zone's four NS values (Route 53 → Registered domains → the domain → **Edit name servers**) |
| The crm plan stops with `multiple Route 53 Hosted Zone matched` | Two zones have the domain's name. Keep the one whose NS values match the domain's name servers (Route 53 → Registered domains) and delete the other |
| The crm apply waits a long time on `aws_acm_certificate_validation` | ACM cannot see the validation records: the domain's name servers do not point at this zone (it was recreated), or the domain is suspended because the registrant e-mail was not verified ([Register a domain](#register-a-domain-once-per-account)). Fix that; the apply carries on, or times out after 75 minutes and can be run again |
| `CNAMEAlreadyExists` creating the CloudFront distribution | Another distribution already answers on `sg.<domain>`, usually one from a crm stack that was not destroyed. Destroy that stack, or remove the name from that distribution, and apply again |
| `AccessDenied` from ACM in us-east-1 | The account refuses certificates in us-east-1, which CloudFront requires. Remove `domain_name` from `crm/env/prod.tfvars` and apply: the app keeps its `*.cloudfront.net` address, and the internal ALB goes back to HTTP |
| `Attribute redefined` in `crm/env/prod.tfvars` or `sftp-server/env/prod.tfvars` | A variable is set twice, usually by a line added by hand or by an older copy of the runbook's snippets, which appended without removing the old line. Delete the older line. The snippets in 9.2 and 9.3 now replace their lines themselves |
| `InvalidParameterValue` from `CreateSecurityGroup`: "valid descriptions are strings less than 256 characters from the following set" | A security group description holds a character EC2 refuses, such as an apostrophe. Remove it and apply again |
| `Member must satisfy regular expression pattern: [\.\-_/#A-Za-z0-9]+` from an `aws logs` command in Git Bash | Git Bash turned the log group name, which starts with `/`, into a Windows path (`C:/Program Files/Git/aws/...`) before passing it to the AWS CLI. Put `MSYS_NO_PATHCONV=1` before the command |
| Transaction reads return 502 | transaction-service is not running. Its log group (`/ecs/<stack>-transaction-service`) shows why; for a failed migration, see its `db-migrate/…` stream. An empty list, not an error, means nothing has been imported yet: turn on the ingest Lambda (step 9) |
