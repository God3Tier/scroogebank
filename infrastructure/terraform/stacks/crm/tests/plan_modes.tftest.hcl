# Plans the stack against mocked providers (no AWS account needed) and checks
# what high_availability, running, environment, deletion_protection and
# domain_name change (ADR-0042, ADR-0029). Run with:
# terraform init -backend=false && terraform test

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
  mock_data "aws_route53_zone" {
    defaults = { zone_id = "Z0123456789EXAMPLE", name = "example.com" }
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

# Known ARNs for the services' login secrets, which a plan leaves unknown, so
# the runs can check which secret each service reads. override_during needs
# Terraform 1.11 or later.
override_resource {
  target          = aws_secretsmanager_secret.app_login["user_service"]
  override_during = plan
  values          = { arn = "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:crm-sg-prod/db/login/user_service" }
}
override_resource {
  target          = aws_secretsmanager_secret.app_login["client_service"]
  override_during = plan
  values          = { arn = "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:crm-sg-prod/db/login/client_service" }
}
override_resource {
  target          = aws_secretsmanager_secret.app_login["account_service"]
  override_during = plan
  values          = { arn = "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:crm-sg-prod/db/login/account_service" }
}
override_resource {
  target          = aws_secretsmanager_secret.app_login["transaction_service"]
  override_during = plan
  values          = { arn = "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:crm-sg-prod/db/login/transaction_service" }
}
override_resource {
  target          = aws_secretsmanager_secret.app_login["transaction_ingest"]
  override_during = plan
  values          = { arn = "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:crm-sg-prod/db/login/transaction_ingest" }
}
override_resource {
  target          = aws_secretsmanager_secret.app_login["event_alert_service"]
  override_during = plan
  values          = { arn = "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:crm-sg-prod/db/login/event_alert_service" }
}
override_resource {
  target          = aws_secretsmanager_secret.app_login["client_health_service"]
  override_during = plan
  values          = { arn = "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:crm-sg-prod/db/login/client_health_service" }
}

variables {
  environment             = "prod"
  root_admin_email        = "root.admin@example.test"
  enable_cognito_triggers = false
  running                 = true
}

run "prod" {
  command = plan

  assert {
    condition     = length(module.db) == 7 && alltrue([for db in module.db : db.instance_class == "db.t4g.micro"])
    error_message = "Prod: seven micro RDS instances, one per database-owning service (ADR-0023)."
  }
  assert {
    condition = (
      module.db["health"].db_name == "health" &&
      aws_secretsmanager_secret.app_login["client_health_service"].name == "${local.name}/db/health/client_health_service" &&
      contains(keys(aws_security_group.this), "db_health") &&
      aws_vpc_security_group_ingress_rule.this["db_health-from-client_health-5432"].from_port == 5432 &&
      aws_vpc_security_group_ingress_rule.this["db_health-from-db_console-5432"].from_port == 5432
    )
    error_message = "Client Health gets its own database, application login secret and narrowly scoped database ingress."
  }
  assert {
    condition     = alltrue([for db in module.db : db.backup_retention_days == 7 && db.multi_az == false && db.max_allocated_storage == 100])
    error_message = "Prod: 7 days of backups, single-AZ outside the demo week, storage auto scaling."
  }
  assert {
    condition     = aws_dynamodb_table.client_keys.point_in_time_recovery[0].enabled && aws_dynamodb_table.client_keys.billing_mode == "PAY_PER_REQUEST"
    error_message = "Prod: client-keys has point-in-time recovery and on-demand billing."
  }
  # WAF logging and the DR vault are commented out while the SCP denies them;
  # restore the logging check and the DR assertion below with those resources.
  assert {
    condition     = length(aws_wafv2_web_acl.this) == 1 && length(aws_backup_plan.daily) == 1
    error_message = "Prod: WAF and AWS Backup are on."
  }
  # assert {
  #   # Provider-computed ARNs are unknown during a mocked plan. The vault's
  #   # kms_key_arn argument directly references this key in backup.tf; this
  #   # assertion protects the conditional creation in prod.
  #   condition     = length(aws_kms_key.backup_dr) == 1 && length(aws_backup_vault.dr) == 1
  #   error_message = "Prod: create the customer-managed DR key and cross-region backup vault together."
  # }
  assert {
    condition     = length(aws_nat_gateway.this) == 1
    error_message = "Prod outside the demo week: one NAT gateway."
  }
  assert {
    condition     = local.min_tasks == 1 && local.max_tasks == 6 && local.log_retention_days == 30
    error_message = "Prod: 1 to 6 tasks per service and 30-day logs."
  }
  assert {
    condition     = !local.db_settings.skip_final_snapshot && alltrue([for s in aws_secretsmanager_secret.service_tls : s.recovery_window_in_days == 7])
    error_message = "Prod: a deleted database leaves a final snapshot, and a deleted secret can be restored for 7 days."
  }
  assert {
    # CloudFront's generated domain is unknown until apply, so the plan test
    # checks the inputs and the independently known auth-flow configuration.
    condition     = length(var.local_dev_origins) == 0 && !contains(aws_cognito_user_pool_client.web.explicit_auth_flows, "ALLOW_USER_SRP_AUTH")
    error_message = "Prod: web sign-in must exclude local callback origins and direct SRP authentication."
  }
  assert {
    condition     = aws_lb_listener.internal.protocol == "HTTP" && aws_lb_listener.internal.port == 80 && length(aws_acm_certificate.alb) == 0 && length(aws_acm_certificate.app) == 0 && length(aws_cloudfront_distribution.this.aliases) == 0
    error_message = "Without domain_name: no certificates, the internal ALB on HTTP 80 and the default CloudFront name."
  }
  assert {
    condition     = join(",", keys(aws_secretsmanager_secret.service_tls)) == "account,alerts,auditor,client,health,transaction,user" && join(",", tls_cert_request.service["alerts"].dns_names) == "event-alert-service" && join(",", tls_cert_request.service["health"].dns_names) == "client-health-monitor-service"
    error_message = "Every service behind the ALB in this stack, and the auditor, gets a project-CA certificate (INF-08)."
  }
  assert {
    condition     = output.dashboard_inputs.upstreams.user.url == "https://user-service:8081" && output.dashboard_inputs.upstreams.transaction.url == "https://transaction-service:8080"
    error_message = "dashboard-service calls user-service and transaction-service over HTTPS."
  }
  assert {
    condition     = alltrue([for m in [module.user_service, module.client_service, module.account_service, module.transaction_service, module.auditor, module.event_alert_service, module.client_health_service] : join(",", m.container_names) == "tls-init,db-migrate,${m.service_name}"])
    error_message = "Every service with a database migrates it before starting (ADR-0028 decision 3)."
  }
  assert {
    condition = (
      local.migrations["users"].environment["FLYWAY_SQL_MIGRATION_SEPARATOR"] == "__" &&
      local.migrations["alerts"].environment["FLYWAY_SQL_MIGRATION_SEPARATOR"] == "__" &&
      local.migrations["health"].environment["FLYWAY_SQL_MIGRATION_SEPARATOR"] == "__" &&
      length(local.migrations["clients"].environment) == 0 &&
      alltrue([for m in [module.user_service, module.event_alert_service, module.client_health_service] : m.app_environment["SPRING_FLYWAY_ENABLED"] == "false"])
    )
    error_message = "db-migrate reads the Java services' V<n>__ file names, and their own Flyway is off in AWS."
  }
  assert {
    condition = (
      join(",", sort(keys(local.migrations["audit"].secrets))) == "FLYWAY_PASSWORD,FLYWAY_PLACEHOLDERS_AUDITOR_APP_PASSWORD,FLYWAY_PLACEHOLDERS_CHAIN_VERIFY_PASSWORD,FLYWAY_USER" &&
      join(",", sort(keys(local.migrations["transactions"].secrets))) == "FLYWAY_PASSWORD,FLYWAY_PLACEHOLDERS_TRANSACTION_INGEST_PASSWORD,FLYWAY_PLACEHOLDERS_TRANSACTION_SERVICE_PASSWORD,FLYWAY_USER" &&
      alltrue([for db in ["users", "clients", "accounts", "alerts", "health"] : join(",", sort(keys(local.migrations[db].secrets))) == "FLYWAY_PASSWORD,FLYWAY_PLACEHOLDERS_APP_LOGIN,FLYWAY_PLACEHOLDERS_APP_PASSWORD,FLYWAY_USER"])
    )
    error_message = "Each database's db-migrate gets the passwords of the logins it creates, and only those."
  }
  assert {
    condition = alltrue([
      for login, m in {
        user_service        = module.user_service
        client_service      = module.client_service
        account_service     = module.account_service
        transaction_service = module.transaction_service
        event_alert_service = module.event_alert_service
        client_health_service = module.client_health_service
      } : alltrue([for v in values(m.app_secrets) : startswith(v, "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:crm-sg-prod/db/login/${login}:")])
    ])
    error_message = "Each service connects with its own login, never its database's master user (ADR-0028 decision 3)."
  }
  assert {
    condition     = contains(keys(aws_ecr_repository.this), "db-migrate") && contains(keys(aws_ecr_repository.this), "client-health-monitor-service")
    error_message = "The db-migrate and Client Health images have their own repositories."
  }
  assert {
    condition = (
      module.client_health_service.app_environment["CLIENT_HEALTH_MODE"] == "API" &&
      module.client_health_service.app_environment["CLIENT_HEALTH_AI_ENABLED"] == "false" &&
      module.client_health_service.app_environment["CLIENT_HEALTH_AUDIT_PUBLISHER_ENABLED"] == "true" &&
      module.client_health_service.app_environment["CLIENT_HEALTH_AUDIT_QUEUE_URL"] == aws_sqs_queue.audit.url &&
      !contains(keys(module.client_health_service.app_environment), "CLIENT_HEALTH_ASSIGNMENT_BASE_URL")
    )
    error_message = "SF11C deploys API mode with audit delivery and AI disabled, while assignment OAuth remains deferred to SF11D."
  }
}

run "custom_domain" {
  command = plan

  variables {
    domain_name               = "example.com"
    enable_ingest_lambda      = true
    ingest_lambda_zip         = "tests/plan_modes.tftest.hcl"
    sftp_server_address       = "203.0.113.10:22"
    sftp_host_key_fingerprint = "SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"
  }

  assert {
    condition     = aws_acm_certificate.alb[0].domain_name == "alb.sg.example.com" && aws_acm_certificate.app[0].domain_name == "sg.example.com"
    error_message = "Custom domain: certificates for alb.<app host> and the app host, sg.<domain> in prod."
  }
  assert {
    condition     = aws_lb_listener.internal.protocol == "HTTPS" && aws_lb_listener.internal.port == 443 && aws_lb_listener.internal.ssl_policy == "ELBSecurityPolicy-TLS13-1-2-2021-06"
    error_message = "Custom domain: the internal ALB listens on HTTPS 443 with TLS 1.2 or 1.3."
  }
  assert {
    condition     = aws_apigatewayv2_integration.alb.tls_config[0].server_name_to_verify == "alb.sg.example.com"
    error_message = "Custom domain: API Gateway verifies the ALB's certificate name."
  }
  assert {
    condition     = aws_cloudfront_distribution.this.aliases == toset(["sg.example.com"]) && aws_cloudfront_distribution.this.viewer_certificate[0].minimum_protocol_version == "TLSv1.2_2021"
    error_message = "Custom domain: CloudFront answers on sg.<domain> with TLS 1.2 or later."
  }
  assert {
    condition     = aws_route53_record.app[0].name == "sg.example.com" && aws_route53_record.alb[0].name == "alb.sg.example.com" && length(aws_route53_record.certificate_validation) == 2
    error_message = "Custom domain: records for the app, the ALB and both certificates' validation."
  }
  assert {
    # CloudFront's generated name is unknown until apply; the custom origin is first.
    condition     = local.web_origins[0] == "https://sg.example.com"
    error_message = "Custom domain: sign-in accepts callbacks on the custom domain."
  }
  assert {
    condition     = aws_lambda_function.ingest[0].environment[0].variables["ACCOUNT_SERVICE_BASE_URL"] == "https://alb.sg.example.com"
    error_message = "Custom domain: the ingest Lambda calls the ALB by the name on its certificate."
  }
}

run "high_availability_week" {
  command = plan

  variables {
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
    condition     = local.min_tasks == 0 && local.max_tasks == 0
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
    domain_name = "example.com"
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
    error_message = "Staging keeps 7 days of RDS backups."
  }
  assert {
    condition     = local.app_host == "sg-staging.example.com" && local.alb_host == "alb.sg-staging.example.com"
    error_message = "Staging's names sit beside prod's: sg-staging.<domain>."
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
  assert {
    condition     = aws_lambda_function_event_invoke_config.ingest[0].maximum_retry_attempts == 0
    error_message = "Lambda does not retry a failed ingest run; the next scheduled run does."
  }
  assert {
    condition     = aws_lambda_function.ingest[0].environment[0].variables["DB_SSLROOTCERT"] == "/var/task/rds-ca.pem"
    error_message = "Ingest verifies transactions-db against the RDS CA in its zip."
  }
  assert {
    condition     = aws_lambda_function.ingest[0].environment[0].variables["DB_SECRET_ARN"] == "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:crm-sg-prod/db/login/transaction_ingest"
    error_message = "Ingest connects with its own login, not transactions-db's master user (ADR-0028 decision 3)."
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
