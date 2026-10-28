# Plans the stack against a mocked AWS provider and a stand-in for the CRM
# stack's state (no AWS account needed), and checks how it attaches to that
# stack (ADR-0048). Run with: terraform init -backend=false && terraform test

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
  mock_data "aws_iam_policy_document" {
    defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" }
  }
}

variables {
  environment  = "prod"
  state_bucket = "crm-tfstate-123456789012"
}

# A running prod CRM stack.
override_data {
  target = data.terraform_remote_state.crm
  values = {
    outputs = {
      alerts_worker_inputs = {
        cluster_arn            = "arn:aws:ecs:ap-southeast-1:123456789012:cluster/crm-sg-prod"
        subnet_ids             = ["subnet-0aaaaaaaaaaaaaaaa", "subnet-0bbbbbbbbbbbbbbbb"]
        alerts_db_address      = "crm-sg-prod-alerts.example.ap-southeast-1.rds.amazonaws.com"
        alerts_db_secret_arn   = "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:rds!db-example"
        alerts_db_sg           = "sg-0db"
        alerts_service_sg      = "sg-0alerts"
        task_definition_family = "crm-sg-prod-event-alert-service"
        audit_queue_url        = "https://sqs.ap-southeast-1.amazonaws.com/123456789012/crm-sg-prod-audit-insert"
        alarm_topic_arn        = "arn:aws:sns:ap-southeast-1:123456789012:crm-sg-prod-alarms"
        name_prefix            = "crm-sg-prod"
        region                 = "ap-southeast-1"
        cluster_name           = "crm-sg-prod"
        alerts_db_name         = "alerts"
        image                  = "123456789012.dkr.ecr.ap-southeast-1.amazonaws.com/crm-sg-prod/event-alert-service:abc1234"
        cpu_architecture       = "X86_64"
        use_spot               = false
        log_retention_days     = 30
        running                = true
        deletion_protection    = true
      }
    }
  }
}

run "ingestion_off_by_default" {
  command = plan

  assert {
    condition     = aws_scheduler_schedule.alert_worker.state == "DISABLED" && aws_scheduler_schedule.alert_worker.schedule_expression == "rate(4 hours)"
    error_message = "With ingestion off the schedule stays DISABLED, on the four-hour rate."
  }
  assert {
    condition     = module.event_alert_worker.task_definition_family == "crm-sg-prod-event-alert-worker" && module.event_alert_worker.service_name == null
    error_message = "A scheduled task named under the CRM's prefix, with no ECS service."
  }
}

run "ingestion_on_while_running" {
  command = plan

  variables {
    ingestion_enabled = true
    ai_enabled        = true
  }

  assert {
    condition     = aws_scheduler_schedule.alert_worker.state == "ENABLED"
    error_message = "With the CRM stack running and ingestion on, the schedule is ENABLED."
  }
  assert {
    condition     = aws_scheduler_schedule.alert_worker.schedule_expression_timezone == "Asia/Singapore" && aws_scheduler_schedule.alert_worker.target[0].retry_policy[0].maximum_retry_attempts == 0
    error_message = "Runs are in Singapore time and never retried by the scheduler."
  }
  assert {
    condition     = aws_scheduler_schedule.alert_worker.target[0].ecs_parameters[0].network_configuration[0].assign_public_ip == false
    error_message = "The worker runs in the private app subnets."
  }
  assert {
    condition     = var.bedrock_region == "ap-southeast-1" && local.bedrock_profile_id == "global.amazon.nova-2-lite-v1:0"
    error_message = "AI must use Nova 2 Lite global inference from the Singapore source Region."
  }
  assert {
    condition     = can(regex("--cluster crm-sg-prod .*--launch-type FARGATE", output.run_once_command)) && can(regex("securityGroups=\\[sg-0alerts\\]", output.run_once_command))
    error_message = "The by-hand run command targets the CRM cluster and the alerts security group."
  }
}

run "crm_stack_stopped" {
  command = plan

  variables {
    ingestion_enabled = true
  }

  override_data {
    target = data.terraform_remote_state.crm
    values = {
      outputs = {
        alerts_worker_inputs = {
          cluster_arn            = "arn:aws:ecs:ap-southeast-1:123456789012:cluster/crm-sg-prod"
          subnet_ids             = ["subnet-0aaaaaaaaaaaaaaaa", "subnet-0bbbbbbbbbbbbbbbb"]
          alerts_db_address      = "crm-sg-prod-alerts.example.ap-southeast-1.rds.amazonaws.com"
          alerts_db_secret_arn   = "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:rds!db-example"
          alerts_db_sg           = "sg-0db"
          alerts_service_sg      = "sg-0alerts"
          task_definition_family = "crm-sg-prod-event-alert-service"
          audit_queue_url        = "https://sqs.ap-southeast-1.amazonaws.com/123456789012/crm-sg-prod-audit-insert"
          alarm_topic_arn        = "arn:aws:sns:ap-southeast-1:123456789012:crm-sg-prod-alarms"
          name_prefix            = "crm-sg-prod"
          region                 = "ap-southeast-1"
          cluster_name           = "crm-sg-prod"
          alerts_db_name         = "alerts"
          image                  = "123456789012.dkr.ecr.ap-southeast-1.amazonaws.com/crm-sg-prod/event-alert-service:abc1234"
          cpu_architecture       = "X86_64"
          use_spot               = false
          log_retention_days     = 30
          running                = false
          deletion_protection    = true
        }
      }
    }
  }

  assert {
    condition     = aws_scheduler_schedule.alert_worker.state == "DISABLED"
    error_message = "While the CRM stack is stopped the worker schedule is paused (ADR-0029)."
  }
}

run "failed_run_alarm_reports_to_the_crm_topic" {
  command = plan

  assert {
    condition     = aws_cloudwatch_event_target.failed.arn == "arn:aws:sns:ap-southeast-1:123456789012:crm-sg-prod-alarms"
    error_message = "A non-zero exit reports to the CRM stack's alarm topic."
  }
}

run "metrics_secret_and_replay" {
  command = plan

  variables {
    ingestion_enabled          = true
    cloudwatch_metrics_enabled = true
  }

  assert {
    condition     = can(regex("alert-worker-tavily", output.tavily_secret_arn)) && aws_secretsmanager_secret.tavily.recovery_window_in_days == 7
    error_message = "The stack owns a recoverable Tavily secret container whose value is populated out of band."
  }

  assert {
    condition     = can(regex("ALERT_REPLAY|ALERT_WORKER_REPLAY_OPERATOR", output.replay_command)) && can(regex("--launch-type FARGATE", output.replay_command))
    error_message = "The replay command starts a one-off task with the four replay variables to fill in."
  }
  assert {
    condition     = can(regex("ALERT_WORKER_REPLAY_CORRELATION_ID", output.replay_command)) && can(regex("event-alert-worker", output.replay_command))
    error_message = "The replay command overrides the worker container and names the correlation ID."
  }
}

