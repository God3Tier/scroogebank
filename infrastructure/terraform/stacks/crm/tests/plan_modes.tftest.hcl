# Plans the stack against mocked providers (no AWS account needed) and checks
# what free_plan, high_availability, running and environment change
# (ADR-0042, ADR-0029). Run with: terraform init -backend=false && terraform test

mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012" }
  }
  mock_data "aws_partition" {
    defaults = { partition = "aws" }
  }
  mock_data "aws_region" {
    defaults = { region = "ap-southeast-1" }
  }
  mock_data "aws_availability_zones" {
    defaults = { names = ["ap-southeast-1a", "ap-southeast-1b", "ap-southeast-1c"] }
  }
  mock_data "aws_iam_policy_document" {
    defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" }
  }
  mock_data "aws_iam_openid_connect_provider" {
    defaults = { arn = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com" }
  }
}

mock_provider "aws" {
  alias = "us_east_1"
}

mock_provider "aws" {
  alias = "dr"
}

mock_provider "random" {}
mock_provider "tls" {}

variables {
  environment             = "prod"
  root_admin_email        = "root.admin@example.test"
  enable_cognito_triggers = false
  running                 = true
}

run "free_plan_stays_inside_the_plan_limits" {
  command = plan

  variables {
    free_plan         = true
    high_availability = true # must be ignored on the Free plan
  }

  # The two settings a Free plan account refuses (FreeTierRestrictionError).
  assert {
    condition     = alltrue([for db in module.db : db.backup_retention_days == 1])
    error_message = "Free plan: RDS backup retention (the PITR window) must be 1 day."
  }
  assert {
    condition     = alltrue([for db in module.db : db.instance_class == "db.t4g.micro"])
    error_message = "Free plan: RDS must use a micro instance class."
  }

  assert {
    condition     = alltrue([for db in module.db : db.multi_az == false])
    error_message = "Free plan: no Multi-AZ standbys, even with high_availability."
  }
  assert {
    condition     = alltrue([for db in module.db : db.max_allocated_storage == 0])
    error_message = "Free plan: storage auto scaling must be off."
  }
  assert {
    condition     = aws_dynamodb_table.client_keys.point_in_time_recovery[0].enabled == false
    error_message = "Free plan: DynamoDB point-in-time recovery must be off."
  }
  assert {
    condition     = aws_dynamodb_table.client_keys.billing_mode == "PROVISIONED" && aws_dynamodb_table.client_keys.read_capacity == 5
    error_message = "Free plan: client-keys must use provisioned 5/5 capacity."
  }
  assert {
    condition     = length(aws_nat_gateway.this) == 1
    error_message = "Free plan: one NAT gateway."
  }
  assert {
    condition     = length(aws_wafv2_web_acl.this) == 0 && length(aws_backup_plan.daily) == 0
    error_message = "Free plan: no WAF and no AWS Backup."
  }
  assert {
    condition     = output.free_plan_settings.tasks_per_service == "1-2" && output.free_plan_settings.log_retention_days == 7
    error_message = "Free plan: 1 to 2 tasks per service and 7-day logs."
  }
}

run "paid_plan_prod" {
  command = plan

  variables {
    free_plan = false
  }

  assert {
    condition     = alltrue([for db in module.db : db.backup_retention_days == 7 && db.multi_az == false && db.max_allocated_storage == 100])
    error_message = "Prod: 7 days of backups, single-AZ outside the demo week, storage auto scaling."
  }
  assert {
    condition     = aws_dynamodb_table.client_keys.point_in_time_recovery[0].enabled && aws_dynamodb_table.client_keys.billing_mode == "PAY_PER_REQUEST"
    error_message = "Prod: client-keys has point-in-time recovery and on-demand billing."
  }
  assert {
    condition     = length(aws_wafv2_web_acl.this) == 1 && length(aws_backup_plan.daily) == 1
    error_message = "Prod: WAF and AWS Backup are on."
  }
  assert {
    condition     = length(aws_nat_gateway.this) == 1
    error_message = "Prod outside the demo week: one NAT gateway."
  }
  assert {
    condition     = output.free_plan_settings.tasks_per_service == "2-6"
    error_message = "Prod: 2 to 6 tasks per service."
  }
  assert {
    condition     = !local.db_settings.skip_final_snapshot && alltrue([for s in aws_secretsmanager_secret.service_tls : s.recovery_window_in_days == 7])
    error_message = "Prod: a deleted database leaves a final snapshot, and a deleted secret can be restored for 7 days."
  }
}

run "high_availability_week" {
  command = plan

  variables {
    free_plan         = false
    high_availability = true
  }

  assert {
    condition     = alltrue([for db in module.db : db.multi_az])
    error_message = "Demo week: every database has a Multi-AZ standby."
  }
  assert {
    condition     = length(aws_nat_gateway.this) == 2
    error_message = "Demo week: a NAT gateway per AZ."
  }
}

run "stopped_between_sessions" {
  command = plan

  variables {
    running = false
  }

  assert {
    condition     = output.free_plan_settings.tasks_per_service == "0-0"
    error_message = "Stopped: every service scales to 0."
  }
  assert {
    condition     = aws_scheduler_schedule.chain_verify.state == "DISABLED"
    error_message = "Stopped: schedules pause."
  }
  assert {
    condition     = length(aws_nat_gateway.this) == 0 && length(aws_eip.nat) == 1
    error_message = "Stopped: no NAT gateway, but its Elastic IP is kept for the SFTP allow-list."
  }
}

run "staging" {
  command = plan

  variables {
    environment = "staging"
  }

  assert {
    condition     = length(aws_wafv2_web_acl.this) == 0 && length(aws_backup_plan.daily) == 0
    error_message = "Staging: no WAF and no AWS Backup (ADR-0029)."
  }
  assert {
    condition     = one([for s in aws_ecs_cluster_capacity_providers.this.default_capacity_provider_strategy : s.capacity_provider]) == "FARGATE_SPOT"
    error_message = "Staging: Fargate Spot (ADR-0029)."
  }
  assert {
    condition     = alltrue([for db in module.db : db.backup_retention_days == 7])
    error_message = "Staging on a paid plan keeps 7 days of backups."
  }
}

run "lambdas_enabled" {
  command = plan

  # A plan only hashes the zip, so any existing file stands in for it.
  variables {
    enable_cognito_triggers   = true
    cognito_triggers_zip      = "tests/plan_modes.tftest.hcl"
    enable_ingest_lambda      = true
    ingest_lambda_zip         = "tests/plan_modes.tftest.hcl"
    sftp_server_address       = "203.0.113.10:22"
    sftp_host_key_fingerprint = "SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"
  }

  assert {
    condition     = aws_lambda_function.cognito_triggers[0].runtime == "java25" && aws_lambda_function.cognito_triggers[0].memory_size == 1769 && aws_lambda_function.cognito_triggers[0].timeout == 5
    error_message = "Cognito triggers: java25, 1,769 MB, 5 seconds (ADR-0040)."
  }
  assert {
    condition     = length(aws_lambda_function.cognito_triggers[0].vpc_config) == 0
    error_message = "Cognito triggers run outside the VPC (ADR-0040)."
  }
  assert {
    condition     = aws_lambda_function.ingest[0].reserved_concurrent_executions == 1 && aws_lambda_function.ingest[0].runtime == "provided.al2023"
    error_message = "Ingest: Go on provided.al2023 with reserved concurrency 1 (ADR-0024)."
  }
  assert {
    condition     = aws_scheduler_schedule.ingest[0].schedule_expression == "rate(5 minutes)"
    error_message = "Ingest runs every 5 minutes."
  }
}

run "teardown_ready" {
  command = plan

  variables {
    deletion_protection = false
  }

  assert {
    condition     = aws_lb.internal.enable_deletion_protection == false && aws_dynamodb_table.client_keys.deletion_protection_enabled == false && aws_cognito_user_pool.this.deletion_protection == "INACTIVE"
    error_message = "deletion_protection = false must lift every protection so destroy can run."
  }
  assert {
    condition     = aws_s3_bucket.documents.force_destroy && aws_s3_bucket.anchor.force_destroy
    error_message = "deletion_protection = false lets destroy empty the buckets."
  }
  assert {
    condition     = local.db_settings.skip_final_snapshot && alltrue([for s in aws_secretsmanager_secret.service_tls : s.recovery_window_in_days == 0])
    error_message = "deletion_protection = false deletes the secrets at once and takes no final snapshot, so the stack can be created again straight away."
  }
}
