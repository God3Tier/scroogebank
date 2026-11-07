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
        cluster_arn               = "arn:aws:ecs:ap-southeast-1:123456789012:cluster/crm-sg-prod"
        subnet_ids                = ["subnet-0aaaaaaaaaaaaaaaa", "subnet-0bbbbbbbbbbbbbbbb"]
        alerts_db_address         = "crm-sg-prod-alerts.example.ap-southeast-1.rds.amazonaws.com"
        alerts_db_secret_arn      = "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:rds!db-example"
        alerts_db_sg              = "sg-0db"
        alerts_service_sg         = "sg-0alerts"
        task_definition_family    = "crm-sg-prod-event-alert-service"
        audit_queue_url           = "https://sqs.ap-southeast-1.amazonaws.com/123456789012/crm-sg-prod-audit-insert"
        alarm_topic_arn           = "arn:aws:sns:ap-southeast-1:123456789012:crm-sg-prod-alarms"
        name_prefix               = "crm-sg-prod"
        region                    = "ap-southeast-1"
        cluster_name              = "crm-sg-prod"
        alerts_db_name            = "alerts"
        image                     = "123456789012.dkr.ecr.ap-southeast-1.amazonaws.com/crm-sg-prod/event-alert-service:abc1234"
        cpu_architecture          = "X86_64"
        use_spot                  = false
        log_retention_days        = 30
        running                   = true
        deletion_protection       = true
        holdings_mode             = "fixture"
        crm_account_directory_url = ""
        crm_token_endpoint        = ""
        crm_client_id             = ""
        crm_scope                 = ""
        crm_client_secret_arn     = ""
      }
    }
  }
}

run "ingestion_off_by_default" {
  command = plan

  assert {
    condition     = var.report_summary_enabled == true
    error_message = "The summary sentence is approved, so it is on by default; it does nothing while the AI is off (ADR-0085)."
  }

  assert {
    condition     = aws_scheduler_schedule.alert_worker.state == "DISABLED" && aws_scheduler_schedule.alert_worker.schedule_expression == "cron(0 0/4 * * ? *)"
    error_message = "With ingestion off the schedule stays DISABLED, at fixed times every four hours (ADR-0081)."
  }
  assert {
    condition     = module.event_alert_worker.task_definition_family == "crm-sg-prod-event-alert-worker" && module.event_alert_worker.service_name == null
    error_message = "A scheduled task named under the CRM's prefix, with no ECS service."
  }
}

run "ingestion_on_while_running" {
  command = plan

  variables {
    ingestion_enabled     = true
    ai_enabled            = true
    approved_news_domains = ["reuters.com", "cnbc.com", "ft.com", "bloomberg.com"]
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

run "run_on_apply_starts_one_run" {
  command = plan

  variables {
    ingestion_enabled = true
  }

  assert {
    condition     = length(terraform_data.run_on_apply) == 1
    error_message = "With ingestion on and the CRM stack running, every apply starts one run."
  }
}

run "run_on_apply_can_be_switched_off" {
  command = plan

  variables {
    ingestion_enabled = true
    run_on_apply      = false
  }

  assert {
    condition     = length(terraform_data.run_on_apply) == 0
    error_message = "run_on_apply = false starts nothing."
  }
}

run "no_run_on_apply_while_ingestion_is_off" {
  command = plan

  assert {
    condition     = length(terraform_data.run_on_apply) == 0
    error_message = "With ingestion off there is nothing to run, so apply starts nothing."
  }
}

run "rejects_an_open_posting_platform_as_a_news_domain" {
  command = plan

  variables {
    approved_news_domains = ["reddit.com"]
  }

  expect_failures = [var.approved_news_domains]
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
          cluster_arn               = "arn:aws:ecs:ap-southeast-1:123456789012:cluster/crm-sg-prod"
          subnet_ids                = ["subnet-0aaaaaaaaaaaaaaaa", "subnet-0bbbbbbbbbbbbbbbb"]
          alerts_db_address         = "crm-sg-prod-alerts.example.ap-southeast-1.rds.amazonaws.com"
          alerts_db_secret_arn      = "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:rds!db-example"
          alerts_db_sg              = "sg-0db"
          alerts_service_sg         = "sg-0alerts"
          task_definition_family    = "crm-sg-prod-event-alert-service"
          audit_queue_url           = "https://sqs.ap-southeast-1.amazonaws.com/123456789012/crm-sg-prod-audit-insert"
          alarm_topic_arn           = "arn:aws:sns:ap-southeast-1:123456789012:crm-sg-prod-alarms"
          name_prefix               = "crm-sg-prod"
          region                    = "ap-southeast-1"
          cluster_name              = "crm-sg-prod"
          alerts_db_name            = "alerts"
          image                     = "123456789012.dkr.ecr.ap-southeast-1.amazonaws.com/crm-sg-prod/event-alert-service:abc1234"
          cpu_architecture          = "X86_64"
          use_spot                  = false
          log_retention_days        = 30
          running                   = false
          deletion_protection       = true
          holdings_mode             = "fixture"
          crm_account_directory_url = ""
          crm_token_endpoint        = ""
          crm_client_id             = ""
          crm_scope                 = ""
          crm_client_secret_arn     = ""
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
    condition     = aws_secretsmanager_secret.tavily.name_prefix == "crm-sg-prod-alert-worker-tavily-" && aws_secretsmanager_secret.tavily.recovery_window_in_days == 7
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


run "fixture_mode_passes_no_directory_settings" {
  command = plan

  assert {
    condition     = module.event_alert_worker.app_environment["ALERT_HOLDINGS_MODE"] == "fixture"
    error_message = "The worker uses the holdings mode the CRM stack publishes (ADR-0066)."
  }
  assert {
    condition     = !contains(keys(module.event_alert_worker.app_environment), "ALERT_CRM_ACCOUNT_DIRECTORY_URL") && !contains(keys(module.event_alert_worker.app_secrets), "ALERT_CRM_CLIENT_SECRET")
    error_message = "Fixture mode asks for no CRM directory and reads no directory secret."
  }
}

run "database_mode_matches_the_api_and_keeps_the_secret_out_of_the_environment" {
  command = plan

  override_data {
    target = data.terraform_remote_state.crm
    values = {
      outputs = {
        alerts_worker_inputs = {
          cluster_arn               = "arn:aws:ecs:ap-southeast-1:123456789012:cluster/crm-sg-prod"
          subnet_ids                = ["subnet-0aaaaaaaaaaaaaaaa", "subnet-0bbbbbbbbbbbbbbbb"]
          alerts_db_address         = "crm-sg-prod-alerts.example.ap-southeast-1.rds.amazonaws.com"
          alerts_db_secret_arn      = "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:rds!db-example"
          alerts_db_sg              = "sg-0db"
          alerts_service_sg         = "sg-0alerts"
          task_definition_family    = "crm-sg-prod-event-alert-service"
          audit_queue_url           = "https://sqs.ap-southeast-1.amazonaws.com/123456789012/crm-sg-prod-audit-insert"
          alarm_topic_arn           = "arn:aws:sns:ap-southeast-1:123456789012:crm-sg-prod-alarms"
          name_prefix               = "crm-sg-prod"
          region                    = "ap-southeast-1"
          cluster_name              = "crm-sg-prod"
          alerts_db_name            = "alerts"
          image                     = "123456789012.dkr.ecr.ap-southeast-1.amazonaws.com/crm-sg-prod/event-alert-service:abc1234"
          cpu_architecture          = "X86_64"
          use_spot                  = false
          log_retention_days        = 30
          running                   = true
          deletion_protection       = true
          holdings_mode             = "database"
          crm_account_directory_url = "https://alb.example.test/internal/alerts/accounts/lookup"
          crm_token_endpoint        = "https://crm-sg-prod.auth.ap-southeast-1.amazoncognito.com/oauth2/token"
          crm_client_id             = "alertdirectoryclientid"
          crm_scope                 = "crm-accounts/alert-directory"
          crm_client_secret_arn     = "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:crm-sg-prod/cognito/alert-directory-AbCdEf"
        }
      }
    }
  }

  assert {
    condition     = module.event_alert_worker.app_environment["ALERT_HOLDINGS_MODE"] == "database" && module.event_alert_worker.app_environment["ALERT_CRM_ACCOUNT_DIRECTORY_URL"] == "https://alb.example.test/internal/alerts/accounts/lookup"
    error_message = "Database mode passes the HTTPS directory URL the CRM stack published."
  }
  assert {
    condition     = module.event_alert_worker.app_environment["ALERT_CRM_SCOPE"] == "crm-accounts/alert-directory" && module.event_alert_worker.app_environment["ALERT_CRM_CLIENT_ID"] == "alertdirectoryclientid"
    error_message = "The worker asks for the one alert-directory scope with the alert app client."
  }
  assert {
    condition     = module.event_alert_worker.app_secrets["ALERT_CRM_CLIENT_SECRET"] == "arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:crm-sg-prod/cognito/alert-directory-AbCdEf:client_secret::"
    error_message = "The client secret is injected from Secrets Manager (a JSON key), not an environment value."
  }
  assert {
    condition     = !contains(keys(module.event_alert_worker.app_environment), "ALERT_CRM_CLIENT_SECRET") && !anytrue([for v in values(module.event_alert_worker.app_environment) : can(regex("client_secret", v))])
    error_message = "No secret value or secret reference appears in the plain task environment."
  }
  assert {
    condition     = module.event_alert_worker.app_environment["ALERT_INGESTION_ENABLED"] == "false" && module.event_alert_worker.app_environment["ALERT_AI_ENABLED"] == "false"
    error_message = "Outbound ingestion and the model stay off by default."
  }
}
