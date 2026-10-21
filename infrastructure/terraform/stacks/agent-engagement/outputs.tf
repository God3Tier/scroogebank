output "ecr_repository" {
  description = "Push engagement-service images here (runbook step 5)."
  value       = aws_ecr_repository.engagement.repository_url
}

output "ecs_cluster" {
  description = "The CRM stack's cluster, where the service runs."
  value       = local.crm.cluster_name
}

output "service_name" {
  description = "ECS service name, for aws ecs update-service."
  value       = module.engagement_service.service_name
}

output "task_role_arn" {
  description = "The role the CRM stack's extra_audit_producer_role_arns must list."
  value       = local.task_role_arn
}

output "db_instance_identifier" {
  description = "engagement-db, which scripts/aws-session.sh stops and starts between sessions."
  value       = module.db.identifier
}

output "from_address" {
  description = "The address mail is sent from."
  value       = local.from_address
}
