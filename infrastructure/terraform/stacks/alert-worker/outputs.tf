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

output "replay_command" {
  description = "Replay one past window by hand (ADR-0049): replace the four <...> values, then run it. The worker refuses the replay unless the operator and ticket are set, and writes an audit event first."
  value = join(" ", [
    "aws ecs run-task",
    "--cluster ${local.crm.cluster_name}",
    "--launch-type FARGATE",
    "--task-definition ${module.event_alert_worker.task_definition_family}",
    "--network-configuration 'awsvpcConfiguration={subnets=[${join(",", local.crm.subnet_ids)}],securityGroups=[${local.crm.alerts_service_sg}],assignPublicIp=DISABLED}'",
    "--overrides '{\"containerOverrides\":[{\"name\":\"event-alert-worker\",\"environment\":[",
    "{\"name\":\"ALERT_WORKER_REPLAY_FROM\",\"value\":\"<2026-10-14T01:00:00Z>\"},",
    "{\"name\":\"ALERT_WORKER_REPLAY_TO\",\"value\":\"<2026-10-14T03:00:00Z>\"},",
    "{\"name\":\"ALERT_WORKER_REPLAY_OPERATOR\",\"value\":\"<your-name>\"},",
    "{\"name\":\"ALERT_WORKER_REPLAY_CORRELATION_ID\",\"value\":\"<ticket-or-reason>\"}]}]}'",
  ])
}
