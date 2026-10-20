output "task_definition_arn" {
  description = "Latest task definition revision."
  value       = aws_ecs_task_definition.this.arn
}

output "task_definition_family" {
  description = "Task definition family."
  value       = aws_ecs_task_definition.this.family
}

output "container_names" {
  description = "The task's containers, in the order they start."
  value       = [for c in concat(local.init_container, local.migrate_container, [local.app_container]) : c.name]
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
