# ------------------------------------------------------------------ frontend

output "app_url" {
  description = "The CRM's address."
  value       = "https://${aws_cloudfront_distribution.this.domain_name}"
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
  value       = tls_private_key.sftp_client.public_key_openssh
}

# ------------------------------------------- for EDA-D1-02 (alert worker)

output "alerts_worker_inputs" {
  description = "What the alert worker's Terraform needs from this stack."
  value = {
    cluster_arn            = aws_ecs_cluster.this.arn
    subnet_ids             = local.app_subnet_ids
    alerts_db_address      = module.db["alerts"].address
    alerts_db_secret_arn   = module.db["alerts"].master_secret_arn
    alerts_db_sg           = local.sg["db_alerts"]
    alerts_service_sg      = local.sg["alerts"]
    task_definition_family = module.event_alert_service.task_definition_family
    audit_queue_url        = aws_sqs_queue.audit.url
    alarm_topic_arn        = aws_sns_topic.alarms.arn
  }
}
