# ------------------------------------------------------------------ frontend

output "app_url" {
  description = "The CRM's address."
  value       = "https://${local.custom_domain ? local.app_host : aws_cloudfront_distribution.this.domain_name}"
}

output "frontend_build_env" {
  description = "Variables for npm run build in frontend/ (frontend/README.md). VITE_API_URL stays empty: the API is on the same origin."
  value = {
    VITE_COGNITO_AUTHORITY = local.cognito_issuer
    VITE_COGNITO_CLIENT_ID = aws_cognito_user_pool_client.web.id
    VITE_COGNITO_DOMAIN    = "https://${aws_cognito_user_pool_domain.this.domain}.auth.${local.region}.amazoncognito.com"
    VITE_API_URL           = ""
  }
}

output "frontend_bucket" {
  description = "Upload the built frontend here."
  value       = aws_s3_bucket.frontend.bucket
}

output "cloudfront_distribution_id" {
  description = "Invalidate after uploading the frontend."
  value       = aws_cloudfront_distribution.this.id
}

# --------------------------------------------------------------- services

output "ecr_repositories" {
  description = "Repository URL for each image."
  value       = { for k, r in aws_ecr_repository.this : k => r.repository_url }
}

output "ecs_cluster" {
  description = "ECS cluster name."
  value       = aws_ecs_cluster.this.name
}

output "app_subnet_ids" {
  description = "Private app subnets, for aws ecs run-task."
  value       = local.app_subnet_ids
}

output "db_console" {
  description = "Task definition family and security group for one-off psql runs (runbook \"Bootstrap the databases\")."
  value = {
    security_group = local.sg["db_console"]
    task_families  = { for k, td in aws_ecs_task_definition.db_console : k => td.family }
  }
}

output "db_instance_identifiers" {
  description = "RDS instances to stop and start between sessions (ADR-0029)."
  value       = [for db in module.db : db.identifier]
}

output "audit_login_secrets" {
  description = "Secrets holding the auditor's and chain-verify's audit-db passwords, for the bootstrap SQL."
  value       = { for k, s in aws_secretsmanager_secret.audit_login : k => s.arn }
}

output "cognito_user_pool_id" {
  description = "User pool ID."
  value       = aws_cognito_user_pool.this.id
}

output "client_health_transaction_read" {
  description = "Client Health machine-read configuration; retrieve its shared secret from Secrets Manager, not Terraform output."
  value = {
    client_id         = aws_cognito_user_pool_client.client_health_read.id
    secret_arn        = aws_secretsmanager_secret.client_health_read.arn
    token_url         = local.cognito_token_url
    scope             = "crm-transactions/read" # compatibility alias
    transaction_scope = "crm-transactions/read"
    assignment_scope  = "crm-clients/assignment.read"
    service_group     = local.sg["client_health"]
    transaction_url   = local.custom_domain ? "${local.alb_base_url}/internal/v1/transactions" : null
    assignment_url    = local.custom_domain ? "${local.alb_base_url}/internal/v1/clients/{clientId}/assignment" : null
  }
}

output "client_health_deployment" {
  description = "Client Health API and scheduled-analysis identifiers for smoke checks and runbooks."
  value = {
    api_service_name                = module.client_health_service.service_name
    analysis_task_definition_family = module.client_health_analysis.task_definition_family
    analysis_schedule_name          = aws_scheduler_schedule.client_health_analysis.name
    security_group                  = local.sg["client_health"]
  }
}

output "audit_queue_url" {
  description = "The shared audit queue."
  value       = aws_sqs_queue.audit.url
}

output "audit_dlq_url" {
  description = "The audit dead-letter queue."
  value       = aws_sqs_queue.audit_dlq.url
}

output "audit_replay_role_arn" {
  description = "Role for replaying failed sign-in events and redriving the dead-letter queue."
  value       = aws_iam_role.cognito_triggers_replay.arn
}

output "github_deploy_role_arn" {
  description = "Role the deploy workflow assumes through OIDC."
  value       = var.enable_github_deploy_role ? aws_iam_role.github_deploy[0].arn : null
}

output "alarm_topic_arn" {
  description = "Subscribe alarm owners here."
  value       = aws_sns_topic.alarms.arn
}

# ------------------------------------------------- for the sftp-server stack

output "nat_public_ips" {
  description = "Addresses the SFTP server must allow on port 22."
  value       = aws_eip.nat[*].public_ip
}

output "sftp_client_public_key" {
  description = "transaction-ingest's SSH public key, for the sftp-server stack's authorized key."
  # trimspace: the key ends in a newline, which `terraform output` would show
  # as a <<EOT heredoc instead of the key.
  value = trimspace(tls_private_key.sftp_client.public_key_openssh)
}

# --------------------------------------- for the business-dashboard stack

output "dashboard_inputs" {
  description = "What the business-dashboard stack reads from this stack's state (ADR-0047). Renaming a key here breaks that stack."
  value = {
    name_prefix    = local.name
    vpc_id         = aws_vpc.this.id
    app_subnet_ids = local.app_subnet_ids
    cluster_arn    = aws_ecs_cluster.this.arn
    cluster_name   = aws_ecs_cluster.this.name
    namespace_arn  = aws_service_discovery_http_namespace.this.arn
    alb            = local.alb
    alb_sg         = local.sg["alb"]

    # The services dashboard-service calls over Service Connect (ADR-0033).
    upstreams = {
      user        = { security_group = local.sg["user"], port = local.ports.user, url = "https://${local.sc.user}:${local.ports.user}" }
      client      = { security_group = local.sg["client"], port = local.ports.client, url = "https://${local.sc.client}:${local.ports.client}" }
      account     = { security_group = local.sg["account"], port = local.ports.account, url = "https://${local.sc.account}:${local.ports.account}" }
      transaction = { security_group = local.sg["transaction"], port = local.ports.transaction, url = "https://${local.sc.transaction}:${local.ports.transaction}" }
    }

    ca_pem                   = local.ca_pem
    cognito_issuer           = local.cognito_issuer
    cognito_web_client_id    = aws_cognito_user_pool_client.web.id
    audit_queue_url          = aws_sqs_queue.audit.url
    audit_queue_arn          = aws_sqs_queue.audit.arn
    audit_producer_role_arns = local.audit_producer_role_arns

    min_tasks               = local.min_tasks
    max_tasks               = local.max_tasks
    use_spot                = local.use_fargate_spot
    log_retention_days      = local.log_retention_days
    cpu_architecture        = var.cpu_architecture
    deletion_protection     = local.deletion_protection
    github_deploy_role_name = var.enable_github_deploy_role ? aws_iam_role.github_deploy[0].name : null
  }
}

# ------------------------------------------ for the agent-engagement stack

output "engagement_inputs" {
  description = "What the agent-engagement stack reads from this stack's state (ADR-0051, built like ADR-0047). Renaming a key here breaks that stack."
  value = {
    name_prefix    = local.name
    vpc_id         = aws_vpc.this.id
    app_subnet_ids = local.app_subnet_ids
    cluster_arn    = aws_ecs_cluster.this.arn
    cluster_name   = aws_ecs_cluster.this.name
    namespace_arn  = aws_service_discovery_http_namespace.this.arn
    alb            = local.alb
    alb_sg         = local.sg["alb"]

    # engagement-service asks client-service whether the agent owns a client,
    # with the agent's token (ADR-0053 decision 5).
    client = { security_group = local.sg["client"], port = local.ports.client, url = "https://${local.sc.client}:${local.ports.client}" }

    ca_pem                = local.ca_pem
    cognito_issuer        = local.cognito_issuer
    cognito_web_client_id = aws_cognito_user_pool_client.web.id
    # Agents' addresses come from ListUsers on this pool (ADR-0053 decision 4).
    cognito_user_pool_id     = aws_cognito_user_pool.this.id
    cognito_user_pool_arn    = aws_cognito_user_pool.this.arn
    audit_queue_url          = aws_sqs_queue.audit.url
    audit_queue_arn          = aws_sqs_queue.audit.arn
    audit_producer_role_arns = local.audit_producer_role_arns

    # engagement-db is built like the CRM's databases (data.tf).
    db = {
      kms_key_arn           = aws_kms_key.platform.arn
      subnet_group_name     = aws_db_subnet_group.this.name
      parameter_group_name  = aws_db_parameter_group.postgres.name
      instance_class        = local.db_settings.instance_class
      allocated_storage     = local.db_settings.allocated_storage
      max_allocated_storage = local.db_settings.max_allocated_storage
      backup_retention_days = local.db_settings.backup_retention_days
      deletion_protection   = local.db_settings.deletion_protection
      skip_final_snapshot   = local.db_settings.skip_final_snapshot
      backup_tag            = local.enable_backup
    }
    db_migrate_image = local.image["db-migrate"]

    # The sending domain is mail.<app_host> (ADR-0053 decision 1); null
    # without a custom domain, when mail comes from the agent-engagement
    # stack's sender_address instead (ADR-0055).
    app_host        = local.app_host
    app_url         = "https://${local.custom_domain ? local.app_host : aws_cloudfront_distribution.this.domain_name}"
    alarm_topic_arn = aws_sns_topic.alarms.arn

    min_tasks               = local.min_tasks
    max_tasks               = local.max_tasks
    use_spot                = local.use_fargate_spot
    log_retention_days      = local.log_retention_days
    cpu_architecture        = var.cpu_architecture
    deletion_protection     = local.deletion_protection
    github_deploy_role_name = var.enable_github_deploy_role ? aws_iam_role.github_deploy[0].name : null
  }
}

# ------------------------------------------- for EDA-D1-02 (alert worker)

# alerts_db_secret_arn is event-alert-service's login, which cannot change the
# schema (data.tf), so the worker sets SPRING_FLYWAY_ENABLED=false as the API
# does; event-alert-service's db-migrate applies the migrations.
output "alerts_worker_inputs" {
  description = "What the alert-worker stack reads from this stack's state (ADR-0048). Renaming or removing a key here breaks that stack."
  value = {
    cluster_arn            = aws_ecs_cluster.this.arn
    subnet_ids             = local.app_subnet_ids
    alerts_db_address      = module.db["alerts"].address
    alerts_db_secret_arn   = aws_secretsmanager_secret.app_login["event_alert_service"].arn
    alerts_db_sg           = local.sg["db_alerts"]
    alerts_service_sg      = local.sg["alerts"]
    task_definition_family = module.event_alert_service.task_definition_family
    audit_queue_url        = aws_sqs_queue.audit.url
    alarm_topic_arn        = aws_sns_topic.alarms.arn

    # Added for the alert-worker stack (ADR-0048). Existing keys are unchanged.
    name_prefix         = local.name
    region              = local.region
    cluster_name        = aws_ecs_cluster.this.name
    alerts_db_name      = module.db["alerts"].db_name
    image               = local.image["event-alert-service"]
    cpu_architecture    = var.cpu_architecture
    use_spot            = local.use_fargate_spot
    log_retention_days  = local.log_retention_days
    running             = var.running
    deletion_protection = local.deletion_protection

    # Added for current holdings (ADR-0066). The worker uses the API's own values.
    holdings_mode             = var.alert_holdings_mode
    crm_account_directory_url = try(local.alert_holdings_environment["ALERT_CRM_ACCOUNT_DIRECTORY_URL"], "")
    crm_token_endpoint        = try(local.alert_holdings_environment["ALERT_CRM_TOKEN_ENDPOINT"], "")
    crm_client_id             = try(local.alert_holdings_environment["ALERT_CRM_CLIENT_ID"], "")
    crm_scope                 = try(local.alert_holdings_environment["ALERT_CRM_SCOPE"], "")
    crm_client_secret_arn     = local.alert_database_holdings ? aws_secretsmanager_secret.alert_directory.arn : ""
  }
}
