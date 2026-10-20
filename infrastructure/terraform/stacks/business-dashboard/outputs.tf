output "ecr_repository" {
  description = "Push dashboard-service images here (runbook step 4)."
  value       = aws_ecr_repository.dashboard.repository_url
}

output "ecs_cluster" {
  description = "The CRM stack's cluster, where the service runs."
  value       = local.crm.cluster_name
}

output "service_name" {
  description = "ECS service name, for aws ecs update-service."
  value       = module.dashboard_service.service_name
}

output "task_role_arn" {
  description = "The role the CRM stack's extra_audit_producer_role_arns must list."
  value       = local.task_role_arn
}
