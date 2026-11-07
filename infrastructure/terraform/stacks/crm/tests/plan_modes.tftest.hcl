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
override_resource {
  target          = aws_secretsmanager_secret.client_health_read
  override_during = plan
  values          = { arn = "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:crm-sg-prod/cognito/client-health-read" }
}

# Known values for what the Client Health checks compare: the audit queue's
# URL, the alarm topic, the machine client's ID, and the API and ALB IDs in the
# dashboard body, which a plan also leaves unknown.
override_resource {
  target          = aws_sqs_queue.audit
  override_during = plan
  values = {
    arn = "arn:aws:sqs:ap-southeast-1:123456789012:crm-sg-prod-audit-insert"
    url = "https://sqs.ap-southeast-1.amazonaws.com/123456789012/crm-sg-prod-audit-insert"
  }
}
override_resource {
  target          = aws_sns_topic.alarms
  override_during = plan
  values          = { arn = "arn:aws:sns:ap-southeast-1:123456789012:crm-sg-prod-alarms" }
}
override_resource {
  target          = aws_cognito_user_pool_client.client_health_read
  override_during = plan
  values          = { id = "clienthealthreadexample" }
}
override_resource {
  target          = aws_apigatewayv2_api.this
  override_during = plan
  values = {
    id            = "a1b2c3d4e5"
    execution_arn = "arn:aws:execute-api:ap-southeast-1:123456789012:a1b2c3d4e5"
  }
}
override_resource {
  target          = aws_lb.internal
  override_during = plan
  values = {
    arn        = "arn:aws:elasticloadbalancing:ap-southeast-1:123456789012:loadbalancer/app/crm-sg-prod-internal/0123456789abcdef"
    arn_suffix = "app/crm-sg-prod-internal/0123456789abcdef"
  }
}

variables {
  environment             = "prod"
  root_admin_email        = "root.admin@example.test"
  enable_cognito_triggers = false
  running                 = true
}

override_resource {
  target          = aws_secretsmanager_secret.alert_directory
  override_during = plan
  values          = { arn = "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:crm-sg-prod/cognito/alert-directory" }
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
    condition = (
      contains(keys(aws_vpc_security_group_ingress_rule.this), "alb-from-client_health-${local.alb_port}") &&
      !contains(keys(aws_vpc_security_group_ingress_rule.this), "transaction-from-client_health-${local.ports.transaction}")
    )
    error_message = "Client Health reaches transaction-service only through the ALB (ADR-0019 decision 4), with no direct ingress."
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
  assert {
    condition = (
      contains([for r in aws_wafv2_web_acl.this[0].rule : r.name], "sql-injection") &&
      one([for r in aws_wafv2_web_acl.this[0].rule : r.statement[0].managed_rule_group_statement[0].name if r.name == "sql-injection"]) == "AWSManagedRulesSQLiRuleSet"
    )
    error_message = "Prod: the WAF blocks SQL injection with AWS's managed rule group (ADR-0073)."
  }
  assert {
    condition = (
      one(aws_apigatewayv2_stage.default.default_route_settings).throttling_rate_limit == 200 &&
      one(aws_apigatewayv2_stage.default.default_route_settings).throttling_burst_limit == 400
    )
    error_message = "The API throttle is sized for 100 concurrent agents with twice the headroom (ADR-0070)."
  }
  assert {
    condition = (
      length(aws_ecs_task_definition.zap_scanner) == 0 && length(aws_s3_bucket.test_runs) == 0 &&
      !contains(keys(aws_security_group.this), "zap_scanner") &&
      length([for k in keys(aws_vpc_security_group_ingress_rule.this) : k if strcontains(k, "zap_scanner")]) == 0
    )
    error_message = "No test tooling, and nothing new allowed into the ALB, unless enable_test_tooling (ADR-0071)."
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
    condition     = local.min_tasks == 2 && local.max_tasks == 6 && local.log_retention_days == 30
    error_message = "Prod: 2 to 6 tasks per service and 30-day logs."
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
      module.client_health_service.app_environment["CLIENT_HEALTH_CLOUDWATCH_METRICS_ENABLED"] == "true" &&
      module.client_health_service.app_environment["CLIENT_HEALTH_CLOUDWATCH_METRICS_NAMESPACE"] == "Scrooge/ClientHealth" &&
      module.client_health_service.app_environment["CLIENT_HEALTH_ASSIGNMENT_BASE_URL"] == "" &&
      module.client_health_service.app_environment["CLIENT_HEALTH_ASSIGNMENT_OAUTH_SCOPE"] == "crm-clients/assignment.read" &&
      startswith(module.client_health_service.app_secrets["SPRING_DATASOURCE_USERNAME"], "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:crm-sg-prod/db/login/client_health_service:") &&
      startswith(module.client_health_service.app_secrets["CLIENT_HEALTH_ASSIGNMENT_OAUTH_CLIENT_ID"], "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:crm-sg-prod/cognito/client-health-read:")
    )
    error_message = "Client Health API uses its database login, audit delivery and exact assignment scope, failing closed without ALB HTTPS."
  }
  assert {
    condition = (
      toset(aws_cognito_user_pool_client.client_health_read.allowed_oauth_scopes) == toset(["crm-clients/assignment.read", "crm-transactions/read"]) &&
      module.client_service.app_environment["CLIENT_HEALTH_ASSIGNMENT_CLIENT_ID"] == aws_cognito_user_pool_client.client_health_read.id &&
      module.client_service.app_environment["CLIENT_HEALTH_ASSIGNMENT_SCOPE"] == "crm-clients/assignment.read" &&
      module.client_health_analysis.service_name == null &&
      join(",", module.client_health_analysis.container_names) == "client-health-analysis" &&
      module.client_health_analysis.app_environment["CLIENT_HEALTH_MODE"] == "ANALYSIS" &&
      module.client_health_analysis.app_environment["CLIENT_HEALTH_AI_ENABLED"] == "false" &&
      module.client_health_analysis.app_environment["CLIENT_HEALTH_AI_MODEL_ID"] == "apac.amazon.nova-lite-v1:0" &&
      !contains(keys(module.client_health_service.app_environment), "CLIENT_HEALTH_AI_MODEL_ID") &&
      module.client_health_analysis.app_environment["CLIENT_HEALTH_AUDIT_PUBLISHER_ENABLED"] == "false" &&
      module.client_health_analysis.app_environment["CLIENT_HEALTH_CLOUDWATCH_METRICS_ENABLED"] == "true" &&
      module.client_health_analysis.app_environment["CLIENT_HEALTH_CLOUDWATCH_METRICS_NAMESPACE"] == "Scrooge/ClientHealth" &&
      module.client_health_analysis.app_environment["CLIENT_HEALTH_TRANSACTION_ENDPOINT"] == "" &&
      module.client_health_analysis.app_environment["CLIENT_HEALTH_ASSIGNMENT_BASE_URL"] == "" &&
      startswith(module.client_health_analysis.app_secrets["CLIENT_HEALTH_TRANSACTION_CLIENT_SECRET"], "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:crm-sg-prod/cognito/client-health-read:") &&
      aws_scheduler_schedule.client_health_analysis.state == "DISABLED" &&
      aws_cloudwatch_event_target.client_health_analysis_failed.arn == aws_sns_topic.alarms.arn
    )
    error_message = "The shared machine client has only the two read scopes, and no-domain analysis remains a non-service task that fails closed."
  }
  assert {
    condition = toset(flatten([for st in data.aws_iam_policy_document.client_health_bedrock.statement : tolist(st.resources) if st.sid == "InvokeExplanationModel"])) == toset([
      "arn:aws:bedrock:ap-southeast-1:123456789012:inference-profile/apac.amazon.nova-lite-v1:0",
      "arn:aws:bedrock:ap-southeast-1::foundation-model/amazon.nova-lite-v1:0",
      "arn:aws:bedrock:ap-southeast-2::foundation-model/amazon.nova-lite-v1:0",
      "arn:aws:bedrock:ap-northeast-1::foundation-model/amazon.nova-lite-v1:0",
      "arn:aws:bedrock:ap-northeast-2::foundation-model/amazon.nova-lite-v1:0",
      "arn:aws:bedrock:ap-northeast-3::foundation-model/amazon.nova-lite-v1:0",
      "arn:aws:bedrock:ap-south-1::foundation-model/amazon.nova-lite-v1:0",
    ])
    error_message = "Client Health may invoke only the Nova Lite Asia-Pacific profile and its model in the six regions ADR-0067 lists, nothing else."
  }
  assert {
    condition = (
      aws_cloudwatch_metric_alarm.client_health_watermark_stale.namespace == "Scrooge/ClientHealth" &&
      aws_cloudwatch_metric_alarm.client_health_watermark_stale.threshold == 97200 &&
      aws_cloudwatch_metric_alarm.client_health_audit_oldest.threshold == 900 &&
      aws_cloudwatch_metric_alarm.client_health_assignment_unavailable.dimensions["outcome"] == "unavailable" &&
      anytrue([for q in aws_cloudwatch_metric_alarm.client_health_analysis_failures.metric_query : strcontains(q.expression, "outcome = 'failed'")]) &&
      strcontains(aws_cloudwatch_dashboard.this.dashboard_body, "Client Health analysis") &&
      strcontains(aws_cloudwatch_dashboard.this.dashboard_body, "client_health_audit_backlog")
    )
    error_message = "Client Health metrics use the bounded namespace and drive freshness, dependency, audit and dashboard coverage."
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
  assert {
    condition = (
      module.client_health_service.app_environment["CLIENT_HEALTH_ASSIGNMENT_BASE_URL"] == "https://alb.sg.example.com" &&
      module.client_health_analysis.app_environment["CLIENT_HEALTH_TRANSACTION_ENDPOINT"] == "https://alb.sg.example.com/internal/v1/transactions" &&
      module.client_health_analysis.app_environment["CLIENT_HEALTH_ASSIGNMENT_BASE_URL"] == "https://alb.sg.example.com" &&
      output.client_health_transaction_read.transaction_scope == "crm-transactions/read" &&
      output.client_health_transaction_read.assignment_scope == "crm-clients/assignment.read"
    )
    error_message = "Custom domain: Client Health calls both internal providers through the verified HTTPS ALB."
  }
  assert {
    condition = (
      aws_scheduler_schedule.client_health_analysis.state == "ENABLED" &&
      aws_scheduler_schedule.client_health_analysis.schedule_expression == "cron(0 8 * * ? *)" &&
      aws_scheduler_schedule.client_health_analysis.schedule_expression_timezone == "Asia/Singapore" &&
      aws_scheduler_schedule.client_health_analysis.target[0].retry_policy[0].maximum_retry_attempts == 1 &&
      aws_scheduler_schedule.client_health_analysis.target[0].retry_policy[0].maximum_event_age_in_seconds == 3600
    )
    error_message = "Custom domain: Client Health analysis runs once at 08:00 SGT with one bounded retry."
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
    condition     = aws_scheduler_schedule.chain_verify.state == "DISABLED" && aws_scheduler_schedule.client_health_analysis.state == "DISABLED"
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

# ---------------------------------------------------------------- event-alert holdings (ADR-0066)

run "alert_directory_identity_and_fixture_default" {
  command = plan

  assert {
    condition     = toset(aws_cognito_user_pool_client.alert_directory.allowed_oauth_scopes) == toset(["crm-accounts/alert-directory"]) && toset(aws_cognito_user_pool_client.alert_directory.allowed_oauth_flows) == toset(["client_credentials"])
    error_message = "The event-alert app client holds only the alert-directory scope, by client credentials."
  }
  assert {
    condition     = toset(aws_cognito_user_pool_client.transaction_ingest.allowed_oauth_scopes) == toset(["crm-accounts/lookup"])
    error_message = "The transaction import keeps exactly its existing scope."
  }
  assert {
    condition     = contains([for s in aws_cognito_resource_server.accounts.scope : s.scope_name], "alert-directory") && contains([for s in aws_cognito_resource_server.accounts.scope : s.scope_name], "lookup")
    error_message = "The accounts resource server keeps lookup and adds alert-directory."
  }
  assert {
    condition     = contains(keys(module.account_service.app_environment), "COGNITO_ALERT_DIRECTORY_CLIENT_IDS") && contains(keys(module.account_service.app_environment), "COGNITO_SERVICE_CLIENT_IDS")
    error_message = "account-service names the alert app client in its own allow-list, next to the import's."
  }
  assert {
    condition     = module.event_alert_service.app_environment["ALERT_HOLDINGS_MODE"] == "fixture" && !contains(keys(module.event_alert_service.app_environment), "ALERT_CRM_ACCOUNT_DIRECTORY_URL") && !contains(keys(module.event_alert_service.app_secrets), "ALERT_CRM_CLIENT_SECRET")
    error_message = "Fixture is the default: no directory settings and no directory secret."
  }
  assert {
    condition     = module.event_alert_service.app_environment["ALERT_INGESTION_ENABLED"] == "false" && module.event_alert_service.app_environment["ALERT_AI_ENABLED"] == "false"
    error_message = "Ingestion and the model stay off by default."
  }
  assert {
    condition     = contains(keys(aws_vpc_security_group_ingress_rule.this), "alb-from-alerts-80") && contains(keys(aws_vpc_security_group_ingress_rule.this), "alb-from-ingest-80")
    error_message = "The alert tasks may call the internal ALB, and the ingest Lambda's rule is unchanged."
  }
  assert {
    condition = output.alerts_worker_inputs.holdings_mode == "fixture" && alltrue([
      for k in ["cluster_arn", "subnet_ids", "alerts_db_address", "alerts_db_secret_arn", "alerts_db_sg", "alerts_service_sg", "task_definition_family", "audit_queue_url", "alarm_topic_arn", "name_prefix", "region", "cluster_name", "alerts_db_name", "image", "cpu_architecture", "use_spot", "log_retention_days", "running", "deletion_protection", "holdings_mode", "crm_account_directory_url", "crm_token_endpoint", "crm_client_id", "crm_scope", "crm_client_secret_arn"] :
      contains(keys(output.alerts_worker_inputs), k)
    ])
    error_message = "The worker's inputs keep every existing key and add the holdings keys (the alert-worker consumer reads them)."
  }
}

run "database_holdings_through_the_https_alb" {
  command = plan

  variables {
    domain_name         = "example.com"
    alert_holdings_mode = "database"
  }

  assert {
    condition     = module.event_alert_service.app_environment["ALERT_HOLDINGS_MODE"] == "database" && module.event_alert_service.app_environment["ALERT_CRM_ACCOUNT_DIRECTORY_URL"] == "https://alb.sg.example.com/internal/alerts/accounts/lookup"
    error_message = "Database mode reaches the account directory over HTTPS through the internal ALB's certificate name."
  }
  assert {
    condition     = startswith(module.event_alert_service.app_environment["ALERT_CRM_TOKEN_ENDPOINT"], "https://") && module.event_alert_service.app_environment["ALERT_CRM_SCOPE"] == "crm-accounts/alert-directory"
    error_message = "The token endpoint is HTTPS and the scope is the alert-directory one."
  }
  assert {
    condition     = module.event_alert_service.app_secrets["ALERT_CRM_CLIENT_SECRET"] == "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:crm-sg-prod/cognito/alert-directory:client_secret::" && !contains(keys(module.event_alert_service.app_environment), "ALERT_CRM_CLIENT_SECRET")
    error_message = "The client secret comes from Secrets Manager and is never a plain environment value."
  }
  assert {
    condition     = output.alerts_worker_inputs.holdings_mode == "database" && output.alerts_worker_inputs.crm_account_directory_url == module.event_alert_service.app_environment["ALERT_CRM_ACCOUNT_DIRECTORY_URL"] && output.alerts_worker_inputs.crm_scope == "crm-accounts/alert-directory"
    error_message = "The worker is handed the API's own holdings mode and directory URL, so they cannot differ."
  }
  assert {
    condition     = contains(keys(aws_vpc_security_group_ingress_rule.this), "alb-from-alerts-443")
    error_message = "With the custom domain the alert tasks reach the ALB on 443."
  }
}

run "database_holdings_need_the_custom_domain" {
  command = plan

  variables {
    alert_holdings_mode = "database"
  }

  expect_failures = [terraform_data.alert_holdings_guard]
}

run "rejects_an_unknown_holdings_mode" {
  command = plan

  variables {
    alert_holdings_mode = "csv"
  }

  expect_failures = [var.alert_holdings_mode]
}

run "alert_routes_are_public_and_directory_routes_are_not" {
  command = plan

  assert {
    condition     = contains(flatten(values(module.event_alert_service.listener_rule_paths)), "/api/alerts/*") && contains(flatten(values(module.event_alert_service.listener_rule_paths)), "/api/alert-portfolios") && contains(flatten(values(module.event_alert_service.listener_rule_paths)), "/api/alert-portfolios/*")
    error_message = "The alert API receives the existing /api/alerts routes and the holdings /api/alert-portfolios routes."
  }
  assert {
    condition     = contains(flatten(values(module.account_service.listener_rule_paths)), "/internal/alerts/accounts/*") && contains(flatten(values(module.account_service.listener_rule_paths)), "/internal/accounts/*") && contains(flatten(values(module.account_service.listener_rule_paths)), "/api/accounts/*")
    error_message = "account-service keeps the import's and the user-facing routes and gains the alert directory route."
  }
  assert {
    condition     = aws_apigatewayv2_route.api.route_key == "ANY /api/{proxy+}" && aws_apigatewayv2_route.api.authorization_type == "JWT"
    error_message = "API Gateway forwards only authenticated /api/* requests; it has no /internal/* route."
  }
  assert {
    condition     = !anytrue([for paths in values(module.event_alert_service.listener_rule_paths) : anytrue([for p in paths : startswith(p, "/internal")])])
    error_message = "The alert API's public rules never include /internal paths."
  }
}

run "client_health_ai_switched_on" {
  command = plan

  variables {
    client_health_ai_enabled = true
  }

  assert {
    condition     = module.client_health_analysis.app_environment["CLIENT_HEALTH_AI_ENABLED"] == "true" && module.client_health_service.app_environment["CLIENT_HEALTH_AI_ENABLED"] == "false"
    error_message = "The switch turns wording on for the analysis task only; the API service never calls Bedrock (ADR-0067)."
  }
}

run "rejects_a_global_client_health_profile" {
  command = plan

  variables {
    client_health_bedrock_model_id = "global.amazon.nova-2-lite-v1:0"
  }

  expect_failures = [var.client_health_bedrock_model_id]
}

run "test_tooling_enabled" {
  command = plan

  variables {
    enable_test_tooling = true
  }

  assert {
    condition = (
      length(aws_ecs_task_definition.zap_scanner) == 1 && length(aws_s3_bucket.test_runs) == 1 &&
      aws_vpc_security_group_ingress_rule.this["alb-from-zap_scanner-${local.alb_port}"].from_port == local.alb_port &&
      contains(keys(aws_vpc_security_group_egress_rule.anywhere), "zap_scanner")
    )
    error_message = "Test tooling: the scanner task, the test bucket, and the scanner's own security group allowed into the ALB (ADR-0071)."
  }
  assert {
    condition     = aws_s3_bucket.test_runs[0].force_destroy
    error_message = "Test tooling: the test bucket never blocks a teardown."
  }
}

run "search_on_add_off_by_default" {
  command = plan

  assert {
    condition     = !contains(keys(module.event_alert_service.app_environment), "ALERT_SEARCH_ON_ADD_MODE")
    error_message = "The API does not start the worker unless alert_search_on_add is true."
  }
}

run "search_on_add_lets_the_api_start_the_worker" {
  command = plan

  variables {
    alert_search_on_add = true
  }

  assert {
    condition     = module.event_alert_service.app_environment["ALERT_SEARCH_ON_ADD_MODE"] == "ecs" && module.event_alert_service.app_environment["ALERT_SEARCH_ON_ADD_TASK_DEFINITION"] == "crm-sg-prod-event-alert-worker"
    error_message = "With alert_search_on_add the API is told to start the worker's task definition through ECS."
  }
}

run "page_schedule_defaults_to_twenty_minutes_for_the_check" {
  command = plan

  assert {
    condition     = module.event_alert_service.app_environment["ALERT_SCHEDULE_INTERVAL"] == "PT20M"
    error_message = "The page says the automatic search runs every twenty minutes during the 2026-11-07 check; set it back to PT4H afterwards."
  }
}

run "page_schedule_follows_the_variable" {
  command = plan

  variables {
    alert_schedule_interval = "PT4H"
  }

  assert {
    condition     = module.event_alert_service.app_environment["ALERT_SCHEDULE_INTERVAL"] == "PT4H"
    error_message = "Setting it back to four hours changes what the page says."
  }
}

run "mfa_required_without_triggers" {
  command = plan

  assert {
    condition     = aws_cognito_user_pool.this.mfa_configuration == "ON" && length(coalesce(aws_apigatewayv2_route.api.authorization_scopes, [])) == 0 && length(aws_cognito_user.mfa_exempt) == 0
    error_message = "Without the token-scope trigger nothing enforces MFA but Cognito, so the pool requires it and no route needs crm/api (ADR-0076)."
  }
}

run "evaluation_accounts_without_mfa" {
  command = plan

  variables {
    enable_cognito_triggers = true
    cognito_triggers_zip    = "tests/fixtures/placeholder.zip"
    root_admin_email        = "admin@crm.com"
    mfa_exempt_users        = ["admin@crm.com", "agent1@crm.com"]
  }

  assert {
    condition     = aws_cognito_user_pool.this.mfa_configuration == "OPTIONAL" && length(aws_lambda_function.token_scope) == 1
    error_message = "With the trigger deployed, the pool's MFA is optional and the trigger enforces it (ADR-0076)."
  }
  assert {
    condition     = toset(aws_apigatewayv2_route.api.authorization_scopes) == toset(["crm/api"]) && length(coalesce(aws_apigatewayv2_route.logout.authorization_scopes, [])) == 0
    error_message = "Every API route needs crm/api, except the sign-out report."
  }
  assert {
    condition     = length(aws_cognito_user.mfa_exempt) == 2 && alltrue([for u in aws_cognito_user.mfa_exempt : u.message_action == "SUPPRESS" && u.password == null && u.temporary_password == null])
    error_message = "The evaluation accounts are created with no e-mail and no password in Terraform."
  }
  assert {
    condition     = length(aws_cognito_user_in_group.mfa_exempt) == 2 && alltrue([for g in aws_cognito_user_in_group.mfa_exempt : g.group_name == "MFA_EXEMPT"])
    error_message = "Terraform adds the evaluation accounts to MFA_EXEMPT only; user-service adds the role groups."
  }
  assert {
    condition     = module.user_service.app_environment["ROOT_ADMIN_EMAIL"] == "admin@crm.com"
    error_message = "user-service seeds the root admin from the Cognito user Terraform created."
  }
  assert {
    condition     = contains(aws_cognito_user_pool_client.web.allowed_oauth_scopes, "aws.cognito.signin.user.admin") && output.frontend_build_env.VITE_MFA_ENROLMENT == "true"
    error_message = "The SPA can enrol TOTP itself, and its build is told to."
  }
}

run "client_service_capacity_and_audit_alarm" {
  command = plan

  assert {
    condition     = module.client_service.task_cpu == 512 && module.client_service.task_memory == 1024
    error_message = "client-service tasks have 0.5 vCPU and 1 GB (ADR-0077)."
  }
  assert {
    condition     = length(aws_cloudwatch_log_metric_filter.service_audit_send_failed) == 3 && aws_cloudwatch_log_metric_filter.service_audit_send_failed["client"].pattern == "\"AUDIT_SEND_FAILED\""
    error_message = "A lost audit event in client-, account- or transaction-service raises an alarm (ADR-0078)."
  }
}
