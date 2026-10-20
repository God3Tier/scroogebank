output "ecs_cluster" {
  description = "The CRM stack's cluster, where the worker's tasks run."
  value       = local.crm.cluster_name
}

output "task_definition_family" {
  description = "The worker's task definition family."
  value       = module.event_alert_worker.task_definition_family
}

output "log_group" {
  description = "Where each run logs its one summary line."
  value       = module.event_alert_worker.log_group_name
}

output "schedule" {
  description = "The schedule's name and whether it is running (ENABLED needs the CRM stack running and ingestion_enabled)."
  value = {
    name       = aws_scheduler_schedule.alert_worker.name
    expression = aws_scheduler_schedule.alert_worker.schedule_expression
    state      = aws_scheduler_schedule.alert_worker.state
  }
}

output "run_once_command" {
  description = "Start one worker run by hand (docs/runbooks/event-driven-alerts-operations.md)."
  value = join(" ", [
    "aws ecs run-task",
    "--cluster ${local.crm.cluster_name}",
    "--launch-type FARGATE",
    "--task-definition ${module.event_alert_worker.task_definition_family}",
    "--network-configuration 'awsvpcConfiguration={subnets=[${join(",", local.crm.subnet_ids)}],securityGroups=[${local.crm.alerts_service_sg}],assignPublicIp=DISABLED}'",
  ])
}
