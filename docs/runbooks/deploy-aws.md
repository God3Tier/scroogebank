# Deploy the CRM to AWS by hand (Terraform)

> **Status:** for testing deployments from a developer machine. There is no CI pipeline for Terraform yet ([infrastructure plan](../feature-plans/infrastructure_plan.md) INF-09). **Owner:** Neo Jia Wen. **Last updated:** 2026-10-16.
> Decisions: [ADR-0028](../adr/ADR-0028-cicd-migrations-terraform.md), [ADR-0029](../adr/ADR-0029-environment-lifecycle-and-cost-controls.md), [ADR-0042](../adr/ADR-0042-terraform-deployment.md).

The code is in `infrastructure/terraform/` (see its [README](../../infrastructure/terraform/README.md)). Commands run from the repository root in Git Bash, unless a step says otherwise. In Windows PowerShell 5.1 (VS Code's default terminal on Windows), quote every option that contains a dot, such as `"-var-file=env/prod.tfvars"`: unquoted, PowerShell splits it at the first dot. Everything uses synthetic data only.

## Before you start

- **Tools:** Terraform 1.10 or newer (tested with 1.16.6), AWS CLI v2, Docker, GNU Make (step 2), JDK 25 and Maven, Node.js for the frontend, Python 3 for `scripts/db-run-sql.sh`.
- **AWS access.** Terraform and the scripts use the AWS CLI's credentials. Without them, Terraform fails with "No valid credential sources found".
  - **Preferred for every human operator:** use temporary credentials from AWS IAM Identity Center. Enable it for a standalone account if necessary, assign a deployment permission set approved by the account owner, then run `aws configure sso --profile crm` and `aws sso login --profile crm`. Do not create a long-lived IAM-user access key merely for this deployment.
  - If a course-managed account supplies another temporary role or credential process, follow that process and select its named CLI profile. Any fallback that requires a long-lived access key needs an explicit security review, MFA, the narrowest workable permissions, an expiry/removal plan, and must never use root credentials.
  - Select the profile in each new terminal with `export AWS_PROFILE=crm` (Git Bash), `set AWS_PROFILE=crm` (cmd, with no spaces or quotes) or `$env:AWS_PROFILE="crm"` (PowerShell, whose `set` does not set environment variables). To select it in every new terminal instead, run `setx AWS_PROFILE crm` once, then restart VS Code or open a new terminal window.
  - Check it with `aws sts get-caller-identity`, without `--profile`. If that fails but `--profile crm` works, the profile is not selected in this terminal, and Terraform will fail the same way.
- **The account must be on the paid plan.** In the Billing console, "Free Tier" shows the account plan; upgrade a Free plan account first. The Free plan caps RDS instances below the six the stack needs, and refuses its backup settings (ADR-0042).
  - New accounts may have low **service quotas**. If tasks stay in `PROVISIONING` or an instance fails to launch on a vCPU limit, check Fargate On-Demand vCPUs and EC2 Standard vCPUs in Service Quotas.
- **Cost.** Keep `running = false` and the databases stopped whenever no one is testing. Prod costs about USD 445 a month left running and about USD 65 a month stopped; two 10-hour sessions a week come to about USD 110 a month (ADR-0042 "Cost Impact").

## 1. Bootstrap the account (once per account)

```sh
cd infrastructure/terraform/bootstrap
cp terraform.tfvars.example terraform.tfvars   # set budget_emails and budget_start_date
terraform init
terraform apply                  # type yes; it must end with "Apply complete!"
terraform output state_bucket    # Output is used in step 3
```

`terraform output` reads the state that a finished apply writes, so "No outputs found" means the apply has not completed in this directory: run it again and fix the error it reports. A missing variable, such as a `terraform.tfvars` copied before `budget_start_date` existed, makes it stop and ask for the value.

This stack keeps its state in a local `terraform.tfstate` (git-ignored). Keep that file; if it is lost, `terraform import` the bucket again before the next apply.

**Budget alerts.** AWS Budgets e-mails `budget_emails` directly; there is no subscription to confirm.

- `crm-total` counts all spending since `budget_start_date` against the USD 300 project limit. It e-mails when actual cost passes 50, 70, 85, 95 and 100%, and when the forecast passes 100%.
- `crm-bedrock` e-mails when a month's Bedrock cost passes 50, 80 and 100% of `bedrock_budget_usd`.
- Both count cost before credits, so they still fire when credits cover the bill.
- Each threshold e-mails once per budget period. Cost data refreshes a few times a day, so an alert can arrive hours after the spend.

## 2. Build the Lambda zip and the images

```sh
mvn -B -pl services/cognito-triggers -am package
ls services/cognito-triggers/target/cognito-triggers.zip
```

The CRM stack deploys this file as the Cognito triggers that audit every login and password reset (ADR-0040), and its plan fails until the file exists. Keep `enable_cognito_triggers = true`: the switch exists for the mocked Terraform tests, and turning it off leaves sign-ins out of the audit log.

Build the images now, before any AWS resource exists; step 4 only pushes them. Docker Desktop must be running. The Go images compile the sqlboiler ORM code in your working tree, which is git-ignored, so regenerate it from each service's current schema first; a stale copy fails the build with `undefined: orm.…`. Each run starts a throwaway PostgreSQL in Docker. Git for Windows has no `make`: install it once with `winget install ezwinports.make`, then open a new terminal.

```sh
for s in account-service auditor client-profile-management-service sftp; do make -C services/$s sqlboiler; done
```

Give every image the same tag, and use a new tag each time (the repositories' tags are immutable), for example the commit SHA:

```sh
TAG=$(git rev-parse --short HEAD)   # echo $TAG to print it; step 3 puts it in env/prod.tfvars
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

```sh
cd infrastructure/terraform/stacks/crm
cp env/prod.s3.tfbackend.example env/prod.s3.tfbackend   # bucket = the state_bucket output
cp env/prod.tfvars.example env/prod.tfvars               # root_admin_email, alarm_email, image_tag = step 2's $TAG
terraform init -backend-config=env/prod.s3.tfbackend
terraform apply -var-file=env/prod.tfvars -var running=false
terraform output app_url # Note url for later
terraform output -raw ecs_cluster # Note for later (should be crm-sg-prod by default)
cd -
```

`running=false` creates everything but starts no task and no NAT gateway, so nothing pulls images yet. Confirm the alarm e-mail subscription.

**Staging (optional).** Staging is a second, separate copy of the stack for short promotion tests (ADR-0029): Fargate Spot, one Availability Zone, no WAF, no AWS Backup and no deletion protection. A test deployment does not need it, and running both doubles the cost.

- To create it, repeat this step with `env/staging.s3.tfbackend` and `env/staging.tfvars`, and use `staging.tfvars` in every later command for it.
- The backend file decides which stack's state this directory works on, and the scripts read that same state. To switch environments, run `terraform init -reconfigure -backend-config=env/<environment>.s3.tfbackend` first.
- Never apply one environment's tfvars while the directory is initialised on the other's backend: Terraform would try to turn that stack into the other one.

## 4. Push the images

Step 3 created the ECR repositories. From the repository root, with Docker Desktop running, push step 2's images to them. `TAG` is read from `env/prod.tfvars`, so the pushed tag is the one Terraform deploys:

```sh
TAG=$(sed -n 's/^image_tag *= *"\([^"]*\)".*/\1/p' infrastructure/terraform/stacks/crm/env/prod.tfvars); echo "$TAG"
REPOS=$(terraform -chdir=infrastructure/terraform/stacks/crm output -json ecr_repositories)
repo() { printf '%s' "$REPOS" | python -c "import json,sys; print(json.load(sys.stdin)['$1'])"; }
aws ecr get-login-password | docker login --username AWS --password-stdin "$(repo auditor | cut -d/ -f1)"

push() { docker tag "crm/$1:$TAG" "$(repo "$1"):$TAG" && docker push "$(repo "$1"):$TAG"; }
for s in user-service dashboard-service event-alert-service auditor client-service account-service transaction-service; do push "$s"; done
```

## 5. Start the stack and set up the databases

```sh
cd infrastructure/terraform/stacks/crm
terraform apply -var-file=env/prod.tfvars -var running=true
cd -
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

## 6. Upload the frontend

```sh
cd frontend
eval "$(terraform -chdir=../infrastructure/terraform/stacks/crm output -json frontend_build_env | python -c "import json,sys; print('\n'.join(f'export {k}=\"{v}\"' for k,v in json.load(sys.stdin).items()))")"
npm ci && npm run build
aws s3 sync dist/ "s3://$(terraform -chdir=../infrastructure/terraform/stacks/crm output -raw frontend_bucket)" --delete
aws cloudfront create-invalidation --distribution-id "$(terraform -chdir=../infrastructure/terraform/stacks/crm output -raw cloudfront_distribution_id)" --paths '/*' # Clears cloudfront cache so it gets the new frontend
cd -
```

## 7. Check it works

1. **Services:** in the ECS console, every service reaches its desired count. Alternatively run `aws ecs describe-services --cluster <ecs_cluster> --services user-service client-service account-service auditor transaction-service dashboard-service event-alert-service`. A service stuck restarting shows why in its log group (`/ecs/<stack>-<service>`): a `tls-init` failure usually means no NAT gateway (the stack is stopped); a database error usually means step 5's schema is missing.
2. **Sign in:** open `terraform output app_url` (from crm stack directory). user-service creates the root admin from `root_admin_email` on its first start, and Cognito e-mails a temporary password. Sign in, set a new password, and enrol an authenticator app (MFA is required).
3. **Audit:** create a client, then open the activity feed. The change appears within seconds. A message in the audit dead-letter queue, or the `audit-oldest-message` alarm, means the auditor is not consuming.

## 8. The external SFTP server and the ingest Lambda

The sftp-server stack is the bank's SFTP server ([ADR-0024](../adr/ADR-0024-transaction-ingestion-sftp-lambda.md) decision 1). transaction-ingest, a Lambda function in the crm stack, imports its files. Each stack needs the other's outputs, so the order is: crm (steps 3–5), then sftp-server, then crm again. Run the commands below in Git Bash, from the repository root.

### 8.1 Create the SFTP server

1. **Backend.** Copy the example:

   ```sh
   cp infrastructure/terraform/stacks/sftp-server/env/prod.s3.tfbackend.example infrastructure/terraform/stacks/sftp-server/env/prod.s3.tfbackend
   ```

   Set `bucket` to the same bucket as `stacks/crm/env/prod.s3.tfbackend` (the bootstrap stack's `state_bucket` output). Keep `key = "sftp-server/prod.tfstate"`: it must differ from the crm stack's key, or the two stacks overwrite each other's state.

2. **Variables.** `env/prod.tfvars` takes two values from the crm stack's outputs:

   | Variable | Value |
   |---|---|
   | `environment` | `"prod"`, as the crm stack |
   | `allowed_cidrs` | The crm output `nat_public_ips`, each as a `/32`. Port 22 accepts only these addresses |
   | `crm_ingest_public_key` | The crm output `sftp_client_public_key`. The private half is in Secrets Manager, for the Lambda |
   | `host_key_file` | Optional. Set it only if you were given a host key for the server; otherwise Terraform generates one. Save the key in the git-ignored `secrets/` folder at the repository root, and give its path relative to `stacks/sftp-server`, such as `"../../../../secrets/sftp_host_key"`. It must be an unencrypted OpenSSH private key (its first line is `-----BEGIN OPENSSH PRIVATE KEY-----`) |
   | `producer_image` | Leave unset for now (8.2) |
   | `crm_account_ids` | Leave unset for now (8.2) |

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

### 8.2 Start the producer

The producer is the bank's file generator. It needs the ECR repository that 8.1 created, so it is a second apply.

1. Build it for the instance's arm64 CPU and push it. The repository's tags are immutable, so use a new tag each time:

   ```sh
   URL=$(terraform -chdir=infrastructure/terraform/stacks/sftp-server output -raw producer_repository_url)
   PTAG=$(git rev-parse --short HEAD)
   aws ecr get-login-password | docker login --username AWS --password-stdin "${URL%%/*}"
   docker build --platform linux/arm64 -t "$URL:$PTAG" services/sftp/producer
   docker push "$URL:$PTAG"
   # Take note of p
   ```

2. Once the push has succeeded, add the image to `sftp-server/env/prod.tfvars`. Run this in the same terminal, as it uses step 1's `URL` and `PTAG`, and first delete any older `producer_image` line: Terraform refuses an attribute set twice.

   ```sh
   printf '%s\n' "producer_image = \"$URL:$PTAG\"" >> infrastructure/terraform/stacks/sftp-server/env/prod.tfvars
   tail -1 infrastructure/terraform/stacks/sftp-server/env/prod.tfvars
   ```

   The line looks like `producer_image = "123456789012.dkr.ecr.ap-southeast-1.amazonaws.com/core-banking-sftp-prod/producer:6f606ec"`. In a new terminal `URL` and `PTAG` are empty: set `URL` as in step 1, then list the tags already pushed with `aws ecr list-images --repository-name "${URL#*/}" --query 'imageIds[].imageTag' --output text`.

   Optionally, add the accounts the producer's transactions belong to:

   ```hcl
   crm_account_ids = ["<account UUID>", "<account UUID>"]
   ```

   `crm_account_ids` are the accounts the producer's transactions belong to. It is optional because the accounts do not exist until someone creates them in the deployed app, after the first apply. Left empty, the producer uses random `ACC-000123` references, which the import stores as `Unmatched` ([ADR-0037](../adr/ADR-0037-transactions-database-and-account-linking.md)), and no agent sees them. For a demo, create a few accounts in the app first and list their IDs. The producer reads no CRM database, so it cannot find them itself.

3. Apply sftp-server again. Changing either value replaces the instance (its startup script changes). The host key stays the same, so its fingerprint does too.

### 8.3 Turn on the ingest Lambda

transaction-ingest imports the files ([ADR-0024](../adr/ADR-0024-transaction-ingestion-sftp-lambda.md), INF-04).

1. Build its zip. Docker must be running:

   ```sh
   make -C services/sftp lambda   # services/sftp/receiver/dist/transaction-ingest.zip
   ```

2. Copy the sftp-server outputs into the crm stack's `env/prod.tfvars`. First delete any older `enable_ingest_lambda`, `ingest_lambda_zip`, `sftp_server_address` or `sftp_host_key_fingerprint` lines: Terraform refuses an attribute set twice.

   ```sh
   cd infrastructure/terraform/stacks
   ADDR=$(terraform -chdir=sftp-server output -raw sftp_server_address)
   FP=$(terraform -chdir=sftp-server output -raw sftp_host_key_fingerprint)
   printf '%s\n' \
     'enable_ingest_lambda      = true' \
     'ingest_lambda_zip         = "../../../../services/sftp/receiver/dist/transaction-ingest.zip"' \
     "sftp_server_address       = \"$ADDR\"" \
     "sftp_host_key_fingerprint = \"$FP\"" >> crm/env/prod.tfvars
   cd -
   ```

   `sftp_server_address` looks like `13.250.0.10:22`, and `sftp_host_key_fingerprint` like `SHA256:AbC…`. `ingest_lambda_zip` is relative to `stacks/crm`. A plan fails if any of the four is missing.
3. Apply the crm stack again.
4. Run it once without waiting for the schedule, and read its log:

   ```sh
   aws lambda invoke --function-name <stack>-transaction-ingest out.json && cat out.json
   MSYS_NO_PATHCONV=1 aws logs tail /aws/lambda/<stack>-transaction-ingest --since 10m
   ```

   `<stack>` is `crm-<legal_entity>-<environment>`, `crm-sg-prod` by default. `MSYS_NO_PATHCONV=1` stops Git Bash from turning the log group's leading `/` into `C:/Program Files/Git/`, which AWS rejects; other shells ignore it.

   A successful run writes `null`; a failed one writes an `errorMessage`, which the log explains. Files move from the SFTP server's `reports/` to `processed/` or `rejected/`, and the import's audit events reach the auditor.

To rebuild after a code change, run `make lambda` and apply the crm stack again: the zip's hash changes, so Terraform updates the function.

### 8.4 When the import cannot reach the server

- **The run fails at init:** the log names the secret or variable it could not read.
- **`connect to SFTP server` times out:** `allowed_cidrs` does not hold every crm `nat_public_ips` address, or the NAT gateway is down because the crm stack is stopped. `high_availability = true` adds a second NAT address: after turning it on, rerun the 8.1 snippet and apply sftp-server again, or runs in the second AZ time out.
- **`host key fingerprint mismatch`:** the server's host key changed, and crm still pins the old one. The key changes when:
  - the sftp-server stack is destroyed and created again without `host_key_file`, which generates a new key;
  - `host_key_file` is set, changed or removed. Terraform then replaces the instance, because the key is installed only at first boot. The address stays the same.

  Repeat 8.3 step 2 and apply crm.
- **To check the fingerprint on the server:** port 22 accepts only the NAT addresses, so `ssh-keyscan` from your machine cannot reach it. Use Session Manager instead; the `SHA256:…` it prints must equal the `sftp_host_key_fingerprint` output:

  ```sh
  aws ssm start-session --target "$(terraform -chdir=infrastructure/terraform/stacks/sftp-server output -raw instance_id)"
  sudo ssh-keygen -lf "$(sudo sshd -T | awk '$1 == "hostkey" {print $2; exit}').pub"   # the key sshd uses, whatever its type
  ```

## Between sessions

```sh
# stop
terraform -chdir=infrastructure/terraform/stacks/crm apply -var-file=env/prod.tfvars -var running=false
scripts/aws-session.sh stop
# start
scripts/aws-session.sh start
terraform -chdir=infrastructure/terraform/stacks/crm apply -var-file=env/prod.tfvars -var running=true
```

- Stopping scales every service to 0, pauses the schedules, removes the NAT gateway (its Elastic IP stays) and stops the databases and the SFTP server.
- Starting takes several minutes, mostly the databases.
- RDS starts a stopped instance again after 7 days, so stop it again after a longer break.
- For the high-availability demo week, add `-var high_availability=true`.

## Operations

- **Replay a login that was not audited**, or redrive the audit dead-letter queue: assume the `audit_replay_role_arn` output's role. The queue accepts messages only from producer roles and this one.
- **Database console:** `scripts/db-run-sql.sh <database> <file.sql>` runs any SQL file as the master user.

## Tear down

```sh
terraform -chdir=infrastructure/terraform/stacks/sftp-server destroy -var-file=env/prod.tfvars
terraform -chdir=infrastructure/terraform/stacks/crm apply   -var-file=env/prod.tfvars -var deletion_protection=false
terraform -chdir=infrastructure/terraform/stacks/crm destroy -var-file=env/prod.tfvars -var deletion_protection=false
```

- **Prod first needs its protections lifted:** apply once with `-var deletion_protection=false`. That turns off deletion protection on the databases, the client-keys table, the ALB and the user pool, and lets destroy empty the buckets. It also makes destroy delete the secrets at once and skip the databases' final snapshots, so the stack can be created again straight away; take a manual snapshot first if you want to keep any data. Staging has none to lift.
- **Then hard delete any secret still pending deletion.** The stack's secrets have fixed names (`crm-<entity>-<environment>/...`), so a secret still in its recovery window makes the next apply fail with `a secret with this name is already scheduled for deletion`. Secrets are left like this when destroy runs without the `deletion_protection=false` apply first. This deletes them at once, and does nothing if there are none:

  ```sh
  stack=crm-sg-prod   # crm-<legal_entity>-<environment>
  for s in $(aws secretsmanager list-secrets --include-planned-deletion --filters Key=name,Values="$stack/" \
      --query 'SecretList[?DeletedDate].Name' --output text); do
    aws secretsmanager delete-secret --force-delete-without-recovery --secret-id "$s"
  done
  ```

  A hard-deleted secret cannot be restored. The databases' master secrets (`rds!db-...`) are deleted with their instances and never conflict.
- **Starting over** gives a new user pool and empty databases, so you can change `root_admin_email` then.
- **The audit anchor bucket keeps each anchor locked for 7 days** (Object Lock, governance mode). Before destroying, remove the object versions as an administrator with `s3:BypassGovernanceRetention` (`aws s3api delete-object --bypass-governance-retention --version-id ...`), or wait 7 days.
- **Destroy `bootstrap` last, if at all.** Its state bucket holds the other stacks' state, so first destroy every stack that keeps its state there (`terraform state list` prints nothing in each). The bucket is versioned and has no `force_destroy`, so `terraform destroy` stops with `BucketNotEmpty` until every object version and delete marker is deleted:

  ```sh
  cd infrastructure/terraform/bootstrap
  bucket=crm-tfstate-$(aws sts get-caller-identity --query Account --output text)   # or your state_bucket_name
  aws s3api delete-objects --bucket "$bucket" --delete "$(aws s3api list-object-versions --bucket "$bucket" \
    --query '{Objects: [Versions, DeleteMarkers][][].{Key: Key, VersionId: VersionId}, Quiet: `true`}' --output json)"
  terraform destroy # If this fails, manually empty bucket in AWS Console S3
  ```

  `delete-objects` takes at most 1,000 versions per call. Old versions expire after 90 days, so the bucket rarely holds that many.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `No positional arguments are expected` or `Too many command line arguments` from Terraform | Windows PowerShell split an option at its first dot (`-backend-config=env/prod` and `.s3.tfbackend`). Quote the option, `"-backend-config=env/prod.s3.tfbackend"`, or use Git Bash |
| `failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine` (steps 2 and 4) | Docker Desktop is not running. Start it, wait for "Engine running" (`docker info` then succeeds), and run the step again. If it is running, switch it to Linux containers |
| `InstanceQuotaExceeded: ... free plan accounts` or `FreeTierRestrictionError` on an RDS instance | The account is on the AWS Free plan, which this stack does not support: upgrade it to the paid plan and apply again |
| `SubscriptionRequiredException` creating the GuardDuty detector (bootstrap) | The account is still being activated, after it was opened or upgraded to the paid plan: wait up to 24 hours and apply again |
| `a secret with this name is already scheduled for deletion` | A destroy ran without the `deletion_protection=false` apply before it, so the secrets got a 7-day recovery window. Hard delete them as in [Tear down](#tear-down), then apply again |
| A service's tasks stop with `CannotPullContainerError: ... not found` | `image_tag` in `env/prod.tfvars` is not a tag you pushed (the example's `replace-with-the-pushed-tag`, for instance), or that service's image was never pushed (step 4). Push it, set the tag and apply again. A failed deployment rolls back to the previous one, which keeps failing if it had the same problem, until the next apply |
| `tls-init` exits non-zero | No internet: the stack is stopped, or the NAT gateway is still being created |
| client-service or account-service refuses to start | Production mode needs its TLS files and `verify-full` settings; check the `tls-init` log in the same task |
| A service logs `UnknownHostException: auditor` (or another service's name), and the activity feed or a dashboard panel says it is unavailable | That service's tasks started before the service they call was registered in Service Connect. Restart the calling service (`aws ecs update-service ... --force-new-deployment`), as at the end of step 5 |
| The auditor cannot log in | Step 5's `audit-logins.sql` has not run, or the auditor started before it |
| Sign-in page says "Login pages unavailable" | The app client has no managed login style; re-apply the stack |
| `terraform output` shows `sftp_client_public_key = <<EOT` | The key ends in a newline, so it prints as a heredoc. Read it with `terraform output -raw sftp_client_public_key`, as in step 8.1 |
| `No outputs found` from the sftp-server stack | Its apply has not finished. Run it again and fix the error it reports |
| `Attribute redefined` in `crm/env/prod.tfvars` | Step 8.3's snippet appends; delete the older lines it repeats |
| `InvalidParameterValue` from `CreateSecurityGroup`: "valid descriptions are strings less than 256 characters from the following set" | A security group description holds a character EC2 refuses, such as an apostrophe. Remove it and apply again |
| `Member must satisfy regular expression pattern: [\.\-_/#A-Za-z0-9]+` from an `aws logs` command in Git Bash | Git Bash turned the log group name, which starts with `/`, into a Windows path (`C:/Program Files/Git/aws/...`) before passing it to the AWS CLI. Put `MSYS_NO_PATHCONV=1` before the command |
| Transaction reads return 502 | transaction-service is not running. Its log group (`/ecs/<stack>-transaction-service`) shows why: a database error usually means step 5's `init.sql` is missing. An empty list, not an error, means nothing has been imported yet: turn on the ingest Lambda (step 8) |
