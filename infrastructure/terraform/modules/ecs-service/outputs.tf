output "task_definition_arn" {
  description = "Latest task definition revision."
  value       = aws_ecs_task_definition.this.arn
}

output "task_definition_family" {
  description = "Task definition family."
  value       = aws_ecs_task_definition.this.family
}

output "task_cpu" {
  description = "Task CPU units (256 = 0.25 vCPU)."
  value       = var.cpu
}

output "task_memory" {
  description = "Task memory in MiB."
  value       = var.memory
}

output "container_names" {
  description = "The task's containers, in the order they start."
  value       = [for c in concat(local.init_container, local.migrate_container, [local.app_container]) : c.name]
}

output "app_environment" {
  description = "The app container's environment as the caller set it, without the TLS settings this module adds."
  value       = var.environment
}

output "app_secrets" {
  description = "The app container's secrets (name => valueFrom), without the db-migrate container's."
  value       = var.secrets
}

output "task_role_arn" {
  description = "The application's role."
  value       = aws_iam_role.task.arn
}

output "execution_role_arn" {
  description = "The role ECS uses to start the task."
  value       = aws_iam_role.execution.arn
}

output "service_name" {
  description = "ECS service name, or null for a scheduled task."
  value       = var.create_service ? aws_ecs_service.this[0].name : null
}

output "log_group_name" {
  description = "CloudWatch log group."
  value       = aws_cloudwatch_log_group.this.name
}

output "target_group_arn_suffix" {
  description = "ALB target group, for alarms."
  value       = var.load_balancer == null ? null : aws_lb_target_group.this[0].arn_suffix
}

output "listener_rule_paths" {
  description = "Path patterns each load balancer rule forwards to this service, keyed by rule priority."
  value = {
    for priority, rule in aws_lb_listener_rule.this :
    priority => flatten([for condition in rule.condition : [for pattern in condition.path_pattern : pattern.values]])
  }
}
