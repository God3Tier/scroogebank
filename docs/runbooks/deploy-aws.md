# Deploy the CRM to AWS by hand (Terraform)

> **Status:** for testing deployments from a developer machine. There is no CI pipeline for Terraform yet ([infrastructure plan](../feature-plans/infrastructure_plan.md) INF-09). **Owner:** Neo Jia Wen. **Last updated:** 2026-10-20.
> Decisions: [ADR-0028](../adr/ADR-0028-cicd-migrations-terraform.md), [ADR-0029](../adr/ADR-0029-environment-lifecycle-and-cost-controls.md), [ADR-0042](../adr/ADR-0042-terraform-deployment.md), [ADR-0046](../adr/ADR-0046-human-deploy-access-mfa-role.md) (AWS access), [ADR-0047](../adr/ADR-0047-business-dashboard-terraform-stack.md) (the business-dashboard stack).

The code is in `infrastructure/terraform/` (see its [README](../../infrastructure/terraform/README.md)). Commands run from the repository root in Git Bash, unless a step says otherwise. In Windows PowerShell 5.1 (VS Code's default terminal on Windows), quote every option that contains a dot, such as `"-var-file=env/prod.tfvars"`: unquoted, PowerShell splits it at the first dot. Everything uses synthetic data only.

## Before you start

- **Tools:** Terraform 1.10 or newer (tested with 1.16.6), AWS CLI v2, Docker, GNU Make (step 2), JDK 25 and Maven, Node.js for the frontend, Python 3 for `scripts/db-run-sql.sh`.
- **AWS access:** set up your CLI once and start each session as in [AWS access](#aws-access) below. Terraform and the scripts use the credentials that section puts in your terminal; without them, Terraform fails with "No valid credential sources found".
- **The account must be on the paid plan.** In the Billing console, "Free Tier" shows the account plan; upgrade a Free plan account first. The Free plan caps RDS instances below the six the stack needs, and refuses its backup settings (ADR-0042).
  - New accounts may have low **service quotas**. If tasks stay in `PROVISIONING` or an instance fails to launch on a vCPU limit, check Fargate On-Demand vCPUs and EC2 Standard vCPUs in Service Quotas.
- **Cost.** Keep `running = false` and the databases stopped whenever no one is testing. Prod costs about USD 360 a month left running at one task per service and about USD 65 a month stopped; two 10-hour sessions a week come to about USD 100 a month (ADR-0042 "Cost Impact").

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
```

or PowerShell:

```powershell
Remove-Item Env:AWS_PROFILE -ErrorAction SilentlyContinue
aws sts get-caller-identity --profile crm
aws configure export-credentials --profile crm --format powershell | Invoke-Expression
```

Then run every step of this runbook in that terminal. After 4 hours the credentials expire: run the three lines again. A Terraform apply that is cut off by the expiry stops part way, and its state is saved; start a new session and run it again.

Terraform cannot ask for an MFA code, so it gets the session's credentials from the last line instead of the `crm` profile.

- **Do not set `AWS_PROFILE`.** With it set, Terraform may try the `crm` profile itself and fail. If you ran `setx AWS_PROFILE crm` before, remove it with `reg delete HKCU\Environment /v AWS_PROFILE /f` and open a new terminal.
- **If the MFA prompt hangs or never appears in Git Bash**, run that line in PowerShell, or as `winpty aws sts get-caller-identity --profile crm`. The session is cached for both, so the `eval` line then works in Git Bash.
- If `aws configure export-credentials` is not recognised, update the AWS CLI.

## Register a domain (once per account)

The CRM stack serves the app at `https://<legal_entity>.<domain>` (`sg.<domain>` for prod) and puts the internal ALB on HTTPS, with ACM certificates validated in the domain's Route 53 zone ([ADR-0042](../adr/ADR-0042-terraform-deployment.md) decision 8). You register the domain by hand, outside Terraform, like the `crm-infra-admin` role. Do it once, before step 1: destroying a stack, `bootstrap` included, never removes the domain or its zone, so every later deployment reuses them.

1. In the Route 53 console, open **Domains → Registered domains → Register domains**, search for a name and check out. Choose a name that does not imitate a real bank or organisation, and leave out "bank": a new domain whose page is a bank-branded sign-in can be flagged as phishing.
2. Register it for 1 year with **auto-renew off**, and keep privacy protection on.
3. **Cost:** about USD 15 a year for a `.com`; the console shows each TLD's price, and some are cheaper. It is charged at once and not refunded. **AWS credits do not cover domain registration**, so it goes on the account's card. The hosted zone adds USD 0.50 a month.
4. **Verify the registrant e-mail within 15 days.** Route 53 sends a link to the registrant contact's address; without it, the domain is suspended and certificates stop validating.

Registration usually finishes within minutes, and Route 53 e-mails when it does. Route 53 also creates a public hosted zone with the same name and points the domain at it. Terraform finds that zone by name, so never delete it or create a second zone with the same name ([Troubleshooting](#troubleshooting)).

Without a domain, leave `domain_name` out in step 3: the app keeps its `*.cloudfront.net` address and the internal ALB listens on HTTP inside the VPC.

## 1. Bootstrap the account (once per account)

```sh
cd infrastructure/terraform/bootstrap
[ -f terraform.tfvars ] || cp terraform.tfvars.example terraform.tfvars   # keeps an existing file; set budget_emails and budget_start_date
terraform init
terraform apply                  # type yes; it must end with "Apply complete!"
terraform output -raw state_bucket   # steps 3, 4 and 9.1 read this output themselves
cd -
```

`terraform output` reads the state that a finished apply writes, so "No outputs found" means the apply has not completed in this directory: run it again and fix the error it reports. A missing variable, such as a `terraform.tfvars` copied before `budget_start_date` existed, makes it stop and ask for the value.

This stack keeps its state in a local `terraform.tfstate` (git-ignored). Keep that file; if it is lost, `terraform import` the bucket again before the next apply.

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
build auditor             services/auditor/Dockerfile             .
build client-service      services/client-profile-management-service/Dockerfile services/client-profile-management-service
build account-service     services/account-service/Dockerfile     services/account-service
build transaction-service services/sftp/receiver/Dockerfile       services/sftp/receiver
```

## 3. Create the CRM stack, stopped

In the same terminal as step 2, so `TAG` is still set (in a new one, set it again as in step 2), write the backend file from the bootstrap stack's `state_bucket` output, and put the image tag, your domain and the business dashboard's audit role in `env/prod.tfvars`. The role is dashboard-service's, from the business-dashboard stack (step 4); the audit queue refuses its events, and that stack refuses to plan, until it is listed here ([ADR-0047](../adr/ADR-0047-business-dashboard-terraform-stack.md)):

```sh
cd infrastructure/terraform/stacks/crm
DOMAIN='<your-domain>'   # the one you registered (Register a domain), such as example.com - set it yourself
STATE_BUCKET=$(terraform -chdir=../../bootstrap output -raw state_bucket); echo "$STATE_BUCKET"
sed "s/^bucket *=.*/bucket       = \"${STATE_BUCKET:?no state_bucket output: finish step 1}\"/" env/prod.s3.tfbackend.example > env/prod.s3.tfbackend
[ -f env/prod.tfvars ] || cp env/prod.tfvars.example env/prod.tfvars   # keeps an existing file
sed -i "s/^image_tag *=.*/image_tag = \"${TAG:?set TAG as in step 2}\"/" env/prod.tfvars
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
sed -i '/^extra_audit_producer_role_arns *=/d' env/prod.tfvars   # replaces an older line
printf '%s\n' "extra_audit_producer_role_arns = [\"arn:aws:iam::${ACCOUNT:?not signed in}:role/crm-sg-prod-dashboard-service-task\"]" >> env/prod.tfvars
sed -i '/^domain_name *=/d' env/prod.tfvars   # replaces an older line
printf 'domain_name = "%s"\n' "${DOMAIN:?set DOMAIN}" >> env/prod.tfvars
grep -E '^(image_tag|root_admin_email|alarm_email|enable_ingest_lambda|extra_audit_producer_role_arns|domain_name)' env/prod.tfvars
```

The role's name starts with the stack's prefix, `crm-<legal_entity>-<environment>`; change `crm-sg-prod` for another stack. Without a domain, skip the `DOMAIN` line and the two `domain_name` lines.

**In a new account, check for an old `env/prod.tfvars`.** The `cp` line keeps an existing file, so one from an earlier deployment still has step 9.3's `enable_ingest_lambda`, `ingest_lambda_zip`, `sftp_server_address` and `sftp_host_key_fingerprint` lines. Delete those four lines before the apply. They point at the old account's SFTP server, and step 9.3 adds them back with the new stack's values. Left in, they make this apply create the ingest Lambda early, which a new account's Lambda concurrency quota of 10 can refuse ("decreases account's UnreservedConcurrentExecution below its minimum value of [10]").

Then set `root_admin_email` and `alarm_email` in `env/prod.tfvars` by hand: they are your mailboxes, not Terraform outputs. dashboard-service's image tag is set in the business-dashboard stack, so an `image_tags` entry for it here does nothing.

Create the stack, still in `stacks/crm`:

```sh
terraform init -backend-config=env/prod.s3.tfbackend
terraform apply -var-file=env/prod.tfvars -var running=false
cd -
```

`running=false` creates everything but starts no task and no NAT gateway, so nothing pulls images yet. With `domain_name`, the apply also requests two certificates, `sg.<domain>` (in us-east-1, for CloudFront) and `alb.sg.<domain>` (for the internal ALB), and waits for ACM to validate them through DNS, usually a few minutes. Confirm the alarm e-mail subscription. Later steps read the stack's outputs (the cluster name, the app URL and the rest) with `terraform output` themselves, so there is nothing to note down.

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

## 5. Push the images

Steps 3 and 4 created the ECR repositories: the CRM stack's in step 3, dashboard-service's in step 4. From the repository root, with Docker Desktop running, push step 2's images to them. `TAG` is read from `env/prod.tfvars`, so the pushed tag is the one Terraform deploys:

```sh
TAG=$(sed -n 's/^image_tag *= *"\([^"]*\)".*/\1/p' infrastructure/terraform/stacks/crm/env/prod.tfvars); echo "$TAG"
REPOS=$(terraform -chdir=infrastructure/terraform/stacks/crm output -json ecr_repositories)
repo() { printf '%s' "$REPOS" | python -c "import json,sys; print(json.load(sys.stdin)['$1'])"; }
aws ecr get-login-password | docker login --username AWS --password-stdin "$(repo auditor | cut -d/ -f1)"

push() { docker tag "crm/$1:$TAG" "$(repo "$1"):$TAG" && docker push "$(repo "$1"):$TAG"; }
for s in user-service event-alert-service auditor client-service account-service transaction-service; do push "$s"; done

DASHBOARD=$(terraform -chdir=infrastructure/terraform/stacks/business-dashboard output -raw ecr_repository)
docker tag "crm/dashboard-service:$TAG" "$DASHBOARD:$TAG" && docker push "$DASHBOARD:$TAG"
```

## 6. Start the stacks and set up the databases

```sh
cd infrastructure/terraform/stacks/crm
terraform apply -var-file=env/prod.tfvars -var running=true
cd -
terraform -chdir=infrastructure/terraform/stacks/business-dashboard apply -var-file=env/prod.tfvars   # takes running=true from the CRM stack
```

Then, **once per new stack**, apply the schemas that no service applies itself. user-service and event-alert-service run Flyway on startup and need nothing. Each command starts a psql task inside the VPC and prints its output.

```sh
# Note: switch to Flyway later? So we don't have to maintain these scripts
scripts/db-run-sql.sh clients      services/client-profile-management-service/data/V1_init.sql
scripts/db-run-sql.sh clients      services/client-profile-management-service/data/V2_agent_subject.sql
scripts/db-run-sql.sh accounts     services/account-service/data/V1_init.sql
scripts/db-run-sql.sh transactions services/sftp/receiver/data/init.sql
scripts/db-run-sql.sh audit        services/auditor/data/schema.sql
scripts/db-run-sql.sh audit        infrastructure/terraform/sql/audit-logins.sql --expand-env
```

- **Run `services/auditor/data/schema.sql` only on an empty audit-db.** It begins with `DROP TABLE`.
- The last command creates the auditor's and chain-verify's own logins (ADR-0022 decision 2). Their passwords stay in Secrets Manager.
- Then restart every service. The auditor needs the restart to connect with its new login. The other services need it for Service Connect: a task can call only the Service Connect names that existed when the task started. On a new stack, a service can start before the services it calls are registered. It then fails with `UnknownHostException: auditor` or a similar error until it restarts.

  ```sh
  # Stop gap measure for now: A sturdier long-term fix would be to make Terraform create the called services before the services that call them, or to restart services automatically after the first start
  cluster=$(terraform -chdir=infrastructure/terraform/stacks/crm output -raw ecs_cluster)
  for s in user-service client-service account-service auditor dashboard-service event-alert-service; do
    aws ecs update-service --cluster "$cluster" --service "$s" --force-new-deployment --query 'service.serviceName' --output text
  done
  ```

Until the db-migrate task exists (INF-03), the other services log in as their database's master user.

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
     --services user-service client-service account-service auditor transaction-service dashboard-service event-alert-service \
     --query 'services[].[serviceName,runningCount,desiredCount]' --output table
   ```

   A service stuck restarting shows why in its log group (`/ecs/$STACK-<service>`): a `tls-init` failure usually means no NAT gateway (the stack is stopped); a database error usually means step 6's schema is missing.
2. **Sign in:** open the URL that `terraform -chdir=infrastructure/terraform/stacks/crm output -raw app_url` prints: `https://sg.<domain>` with a domain, or the `*.cloudfront.net` address without one. user-service creates the root admin from `root_admin_email` on its first start, and Cognito e-mails a temporary password. Sign in, set a new password, and enrol an authenticator app (MFA is required).
3. **Audit:** create a client, then open the activity feed. The change appears within seconds. A message in the audit dead-letter queue, or the `audit-oldest-message` alarm, means the auditor is not consuming.

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

## Between sessions

```sh
# stop
terraform -chdir=infrastructure/terraform/stacks/crm apply -var-file=env/prod.tfvars -var running=false
terraform -chdir=infrastructure/terraform/stacks/business-dashboard apply -var-file=env/prod.tfvars
scripts/aws-session.sh stop
# start
scripts/aws-session.sh start
terraform -chdir=infrastructure/terraform/stacks/crm apply -var-file=env/prod.tfvars -var running=true
terraform -chdir=infrastructure/terraform/stacks/business-dashboard apply -var-file=env/prod.tfvars
```

- Stopping scales every service to 0, pauses the schedules, removes the NAT gateway (its Elastic IP stays) and stops the databases and the SFTP server.
- The business-dashboard stack reads `running` from the CRM stack's state, so apply it after the CRM stack every time. Skipped after a stop, dashboard-service keeps one task running, at about USD 1.50 a day. Skipped after a start, it stays at 0 tasks.
- Starting takes several minutes, mostly the databases.
- RDS starts a stopped instance again after 7 days, so stop it again after a longer break.
- For the high-availability demo week, add `-var high_availability=true`.

## Operations

- **Replay a login that was not audited**, or redrive the audit dead-letter queue: assume the `audit_replay_role_arn` output's role. The queue accepts messages only from producer roles and this one.
- **Database console:** `scripts/db-run-sql.sh <database> <file.sql>` runs any SQL file as the master user.

## Tear down

```sh
STACK=$(terraform -chdir=infrastructure/terraform/stacks/crm output -raw ecs_cluster); echo "$STACK"   # the secrets step below needs it after the outputs are gone
terraform -chdir=infrastructure/terraform/stacks/sftp-server destroy -var-file=env/prod.tfvars
terraform -chdir=infrastructure/terraform/stacks/crm apply   -var-file=env/prod.tfvars -var deletion_protection=false
terraform -chdir=infrastructure/terraform/stacks/business-dashboard apply   -var-file=env/prod.tfvars
terraform -chdir=infrastructure/terraform/stacks/business-dashboard destroy -var-file=env/prod.tfvars
terraform -chdir=infrastructure/terraform/stacks/crm destroy -var-file=env/prod.tfvars -var deletion_protection=false
```

- **Destroy business-dashboard before crm.** Its rules sit on the CRM stack's security groups and its service runs in the CRM's cluster, so the CRM stack cannot be destroyed while it exists. Its apply after the CRM's `deletion_protection=false` apply lets its destroy empty dashboard-service's image repository.

- **Prod first needs its protections lifted:** apply once with `-var deletion_protection=false`. That turns off deletion protection on the databases, the client-keys table, the ALB and the user pool, and lets destroy empty the buckets. It also makes destroy delete the secrets at once and skip the databases' final snapshots, so the stack can be created again straight away; take a manual snapshot first if you want to keep any data. Staging has none to lift.
- **Then hard delete any secret still pending deletion.** The stack's secrets have fixed names (`crm-<entity>-<environment>/...`), so a secret still in its recovery window makes the next apply fail with `a secret with this name is already scheduled for deletion`. Secrets are left like this when destroy runs without the `deletion_protection=false` apply first. This deletes them at once, and does nothing if there are none. It uses `STACK` from the block above; in a new terminal the destroyed stack has no outputs, so set `STACK=crm-<legal_entity>-<environment>` (`crm-sg-prod` by default) instead:

  ```sh
  for s in $(aws secretsmanager list-secrets --include-planned-deletion --filters Key=name,Values="${STACK:?set STACK}/" \
      --query 'SecretList[?DeletedDate].Name' --output text); do
    aws secretsmanager delete-secret --force-delete-without-recovery --secret-id "$s"
  done
  ```

  A hard-deleted secret cannot be restored. The databases' master secrets (`rds!db-...`) are deleted with their instances and never conflict.
- **Starting over** gives a new user pool and empty databases, so you can change `root_admin_email` then.
- **The domain and its hosted zone stay** ([Register a domain](#register-a-domain-once-per-account)): they are outside Terraform, so destroying `bootstrap` leaves them too. The certificates and DNS records go with the crm stack. The zone costs USD 0.50 a month while it exists; delete it only when you give up the domain, since a later deployment would then need a new zone and the domain's name servers changed to it.
- **The audit anchor bucket keeps each anchor locked for 7 days** (Object Lock, governance mode). Before destroying, remove the object versions as an administrator with `s3:BypassGovernanceRetention` (`aws s3api delete-object --bypass-governance-retention --version-id ...`), or wait 7 days.
- **Destroy `bootstrap` last, if at all.** Its state bucket holds the other stacks' state, so first destroy every stack that keeps its state there (`terraform state list` prints nothing in each). The bucket is versioned and has no `force_destroy`, so `terraform destroy` stops with `BucketNotEmpty` until every object version and delete marker is deleted:

  ```sh
  cd infrastructure/terraform/bootstrap
  bucket=$(terraform output -raw state_bucket); echo "${bucket:?no state_bucket output}"
  aws s3api delete-objects --bucket "$bucket" --delete "$(aws s3api list-object-versions --bucket "$bucket" \
    --query '{Objects: [Versions, DeleteMarkers][][].{Key: Key, VersionId: VersionId}, Quiet: `true`}' --output json)"
  terraform destroy # If this fails, manually empty bucket in AWS Console S3
  ```

  `delete-objects` takes at most 1,000 versions per call. Old versions expire after 90 days, so the bucket rarely holds that many.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `ExpiredToken` or "The security token included in the request is expired" | The 4-hour session is over. Start a new one ([AWS access](#aws-access)) and run the step again |
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
| `tls-init` exits non-zero | No internet: the stack is stopped, or the NAT gateway is still being created |
| client-service or account-service refuses to start | Production mode needs its TLS files and `verify-full` settings; check the `tls-init` log in the same task |
| A service logs `UnknownHostException: auditor` (or another service's name), and the activity feed or a dashboard panel says it is unavailable | That service's tasks started before the service they call was registered in Service Connect. Restart the calling service (`aws ecs update-service ... --force-new-deployment`), as at the end of step 6 |
| The auditor cannot log in | Step 6's `audit-logins.sql` has not run, or the auditor started before it |
| Sign-in page says "Login pages unavailable" | The app client has no managed login style; re-apply the stack |
| `terraform output` shows `sftp_client_public_key = <<EOT` | The key ends in a newline, so it prints as a heredoc. Read it with `terraform output -raw sftp_client_public_key`, as in step 9.1 |
| `No outputs found` from the sftp-server stack | Its apply has not finished. Run it again and fix the error it reports |
| The business-dashboard plan stops with `The audit queue does not allow arn:aws:iam::…-dashboard-service-task` | The CRM stack's `extra_audit_producer_role_arns` is missing that ARN. Add it to `crm/env/prod.tfvars`, apply the CRM stack, then the business-dashboard stack (steps 3 and 4) |
| The business-dashboard plan fails to read the remote state (`Unable to find remote state`), or says the object has no attribute `dashboard_inputs` | The CRM stack it points at has not been applied, or was applied from code older than ADR-0047. Apply the CRM stack first, and check that `state_bucket`, `legal_entity` and `environment` in `business-dashboard/env/prod.tfvars` match the CRM stack's |
| `RepositoryNotEmptyException` destroying the business-dashboard stack | Its repository still has `force_delete` off. Apply the CRM stack with `-var deletion_protection=false`, apply the business-dashboard stack, then destroy it ([Tear down](#tear-down)) |
| The crm plan stops with `no matching Route 53 Hosted Zone found` | `domain_name` is not a hosted zone in this account: a typo, a registration that has not finished, or a deleted zone. `aws route53 list-hosted-zones-by-name --dns-name <domain>` lists it. To recreate a deleted zone, create a public zone with the same name, then set the domain's name servers to the zone's four NS values (Route 53 → Registered domains → the domain → **Edit name servers**) |
| The crm plan stops with `multiple Route 53 Hosted Zone matched` | Two zones have the domain's name. Keep the one whose NS values match the domain's name servers (Route 53 → Registered domains) and delete the other |
| The crm apply waits a long time on `aws_acm_certificate_validation` | ACM cannot see the validation records: the domain's name servers do not point at this zone (it was recreated), or the domain is suspended because the registrant e-mail was not verified ([Register a domain](#register-a-domain-once-per-account)). Fix that; the apply carries on, or times out after 75 minutes and can be run again |
| `CNAMEAlreadyExists` creating the CloudFront distribution | Another distribution already answers on `sg.<domain>`, usually one from a crm stack that was not destroyed. Destroy that stack, or remove the name from that distribution, and apply again |
| `AccessDenied` from ACM in us-east-1 | The account refuses certificates in us-east-1, which CloudFront requires. Remove `domain_name` from `crm/env/prod.tfvars` and apply: the app keeps its `*.cloudfront.net` address, and the internal ALB goes back to HTTP |
| `Attribute redefined` in `crm/env/prod.tfvars` or `sftp-server/env/prod.tfvars` | A variable is set twice, usually by a line added by hand or by an older copy of the runbook's snippets, which appended without removing the old line. Delete the older line. The snippets in 9.2 and 9.3 now replace their lines themselves |
| `InvalidParameterValue` from `CreateSecurityGroup`: "valid descriptions are strings less than 256 characters from the following set" | A security group description holds a character EC2 refuses, such as an apostrophe. Remove it and apply again |
| `Member must satisfy regular expression pattern: [\.\-_/#A-Za-z0-9]+` from an `aws logs` command in Git Bash | Git Bash turned the log group name, which starts with `/`, into a Windows path (`C:/Program Files/Git/aws/...`) before passing it to the AWS CLI. Put `MSYS_NO_PATHCONV=1` before the command |
| Transaction reads return 502 | transaction-service is not running. Its log group (`/ecs/<stack>-transaction-service`) shows why: a database error usually means step 6's `init.sql` is missing. An empty list, not an error, means nothing has been imported yet: turn on the ingest Lambda (step 9) |
