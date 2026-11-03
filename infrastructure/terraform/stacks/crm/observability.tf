# Observability baseline (ADR-0027): alarms to one SNS topic, a dashboard,
# and the alarms the service READMEs ask for. Alarm owners are not assigned
# yet (ADR-0027 decision 6); subscribe them to the topic.

resource "aws_sns_topic" "alarms" {
  name = "${local.name}-alarms"
}

data "aws_iam_policy_document" "alarms_topic" {
  statement {
    sid = "AccountOwner"
    principals {
      type        = "AWS"
      identifiers = ["arn:${data.aws_partition.current.partition}:iam::${local.account_id}:root"]
    }
    actions   = ["sns:Publish", "sns:Subscribe", "sns:GetTopicAttributes", "sns:SetTopicAttributes", "sns:ListSubscriptionsByTopic"]
    resources = [aws_sns_topic.alarms.arn]
  }

  statement {
    sid = "AlarmsAndEvents"
    principals {
      type        = "Service"
      identifiers = ["cloudwatch.amazonaws.com", "events.amazonaws.com"]
    }
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.alarms.arn]
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }
  }
}

resource "aws_sns_topic_policy" "alarms" {
  arn    = aws_sns_topic.alarms.arn
  policy = data.aws_iam_policy_document.alarms_topic.json
}

resource "aws_sns_topic_subscription" "email" {
  count     = var.alarm_email == null ? 0 : 1
  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "email"
  endpoint  = var.alarm_email
}

locals {
  alarm_actions                  = [aws_sns_topic.alarms.arn]
  metric_namespace               = "CRM/${local.name}"
  client_health_metric_namespace = "Scrooge/ClientHealth"
}

# -------------------------------------------------------------- audit queue

resource "aws_cloudwatch_metric_alarm" "audit_dlq" {
  alarm_name          = "${local.name}-audit-dlq-not-empty"
  alarm_description   = "Audit events failed 5 receives and wait in the dead-letter queue. Inspect, fix, then redrive with the audit-replay role. Owner: auditor."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = aws_sqs_queue.audit_dlq.name }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "audit_backlog" {
  alarm_name          = "${local.name}-audit-oldest-message"
  alarm_description   = "The oldest audit event has waited over 15 minutes: the auditor is down or behind. Owner: auditor."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateAgeOfOldestMessage"
  dimensions          = { QueueName = aws_sqs_queue.audit.name }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 900
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

# ----------------------------------------------------------------- entry

resource "aws_cloudwatch_metric_alarm" "alb_5xx" {
  alarm_name          = "${local.name}-alb-target-5xx"
  alarm_description   = "Services returned more than 10 server errors in 5 minutes."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_Target_5XX_Count"
  dimensions          = { LoadBalancer = aws_lb.internal.arn_suffix }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 10
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "api_latency" {
  alarm_name          = "${local.name}-api-latency"
  alarm_description   = "API p90 latency above 2 seconds for 10 minutes."
  namespace           = "AWS/ApiGateway"
  metric_name         = "Latency"
  dimensions          = { ApiId = aws_apigatewayv2_api.this.id, Stage = "$default" }
  extended_statistic  = "p90"
  period              = 300
  evaluation_periods  = 2
  threshold           = 2000
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

# ------------------------------------------------------- Cognito triggers

resource "aws_cloudwatch_log_metric_filter" "audit_send_failed" {
  count          = var.enable_cognito_triggers ? 1 : 0
  name           = "${local.name}-audit-send-failed"
  log_group_name = aws_cloudwatch_log_group.cognito_triggers[0].name
  pattern        = "{ $.msg = \"AUDIT_SEND_FAILED\" }"

  metric_transformation {
    name          = "AuditSendFailed"
    namespace     = local.metric_namespace
    value         = "1"
    default_value = "0"
  }
}

resource "aws_cloudwatch_metric_alarm" "audit_send_failed" {
  count               = var.enable_cognito_triggers ? 1 : 0
  alarm_name          = "${local.name}-cognito-audit-send-failed"
  alarm_description   = "A login or password reset was not audited. Replay it from the log (services/cognito-triggers README). Owner: Feature 1."
  namespace           = local.metric_namespace
  metric_name         = "AuditSendFailed"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

# client-, account- and transaction-service log an audit event they could neither
# save to the outbox nor send to the queue (ADR-0078). The line holds the whole
# event, to send again by hand.
resource "aws_cloudwatch_log_metric_filter" "service_audit_send_failed" {
  for_each = {
    client      = module.client_service.log_group_name
    account     = module.account_service.log_group_name
    transaction = module.transaction_service.log_group_name
  }
  name           = "${local.name}-${each.key}-audit-send-failed"
  log_group_name = each.value
  pattern        = "\"AUDIT_SEND_FAILED\""

  metric_transformation {
    name          = "ServiceAuditSendFailed"
    namespace     = local.metric_namespace
    value         = "1"
    default_value = "0"
  }
}

resource "aws_cloudwatch_metric_alarm" "service_audit_send_failed" {
  alarm_name          = "${local.name}-service-audit-send-failed"
  alarm_description   = "client-, account- or transaction-service lost an audit event: neither the outbox nor the queue took it. Its AUDIT_SEND_FAILED log line holds the event; send it to the audit queue. Owner: Feature 2."
  namespace           = local.metric_namespace
  metric_name         = "ServiceAuditSendFailed"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions

  depends_on = [aws_cloudwatch_log_metric_filter.service_audit_send_failed]
}

resource "aws_cloudwatch_metric_alarm" "cognito_triggers_errors" {
  count               = var.enable_cognito_triggers ? 1 : 0
  alarm_name          = "${local.name}-cognito-triggers-errors"
  alarm_description   = "The trigger failed, so Cognito refused that sign-in. It should always be 0. Owner: Feature 1."
  namespace           = "AWS/Lambda"
  metric_name         = "Errors"
  dimensions          = { FunctionName = aws_lambda_function.cognito_triggers[0].function_name }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "cognito_triggers_duration" {
  count               = var.enable_cognito_triggers ? 1 : 0
  alarm_name          = "${local.name}-cognito-triggers-slow"
  alarm_description   = "A trigger ran over 4 seconds, close to Cognito's 5-second limit. Owner: Feature 1."
  namespace           = "AWS/Lambda"
  metric_name         = "Duration"
  dimensions          = { FunctionName = aws_lambda_function.cognito_triggers[0].function_name }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 4000
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

# ------------------------------------------------------------ chain-verify

resource "aws_cloudwatch_log_metric_filter" "chain_tamper" {
  name           = "${local.name}-chain-tamper-detected"
  log_group_name = module.chain_verify.log_group_name
  pattern        = "\"CHAIN_TAMPER_DETECTED\""

  metric_transformation {
    name          = "ChainTamperDetected"
    namespace     = local.metric_namespace
    value         = "1"
    default_value = "0"
  }
}

resource "aws_cloudwatch_metric_alarm" "chain_tamper" {
  alarm_name          = "${local.name}-chain-tamper-detected"
  alarm_description   = "chain-verify found a new problem in the audit chain (ADR-0022). Owner: auditor."
  namespace           = local.metric_namespace
  metric_name         = "ChainTamperDetected"
  statistic           = "Sum"
  period              = 3600
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

# A chain-verify run that exits non-zero could not finish its check.
resource "aws_cloudwatch_event_rule" "chain_verify_failed" {
  name        = "${local.name}-chain-verify-failed"
  description = "chain-verify stopped with a non-zero exit code"
  event_pattern = jsonencode({
    source        = ["aws.ecs"]
    "detail-type" = ["ECS Task State Change"]
    detail = {
      clusterArn = [aws_ecs_cluster.this.arn]
      group      = ["family:${module.chain_verify.task_definition_family}"]
      lastStatus = ["STOPPED"]
      containers = {
        name     = ["chain-verify"]
        exitCode = [{ "anything-but" = 0 }]
      }
    }
  })
}

resource "aws_cloudwatch_event_target" "chain_verify_failed" {
  rule = aws_cloudwatch_event_rule.chain_verify_failed.name
  arn  = aws_sns_topic.alarms.arn
}

# A scheduled Client Health task that exits non-zero did not complete its
# replay-to-task handoff. Idempotent retries use the same deterministic run ID.
resource "aws_cloudwatch_event_rule" "client_health_analysis_failed" {
  name        = "${local.name}-client-health-analysis-failed"
  description = "Client Health scheduled analysis stopped with a non-zero exit code"
  event_pattern = jsonencode({
    source        = ["aws.ecs"]
    "detail-type" = ["ECS Task State Change"]
    detail = {
      clusterArn = [aws_ecs_cluster.this.arn]
      group      = ["family:${module.client_health_analysis.task_definition_family}"]
      lastStatus = ["STOPPED"]
      containers = {
        exitCode = [{ "anything-but" = 0 }]
      }
    }
  })
}

resource "aws_cloudwatch_event_target" "client_health_analysis_failed" {
  rule = aws_cloudwatch_event_rule.client_health_analysis_failed.name
  arn  = aws_sns_topic.alarms.arn
}

# ---------------------------------------------------------- Client Health

resource "aws_cloudwatch_metric_alarm" "client_health_watermark_stale" {
  alarm_name          = "${local.name}-client-health-watermark-stale"
  alarm_description   = "No successful Client Health analysis watermark within 27 hours. Primary: Garvit; backup: Swayam."
  namespace           = local.client_health_metric_namespace
  metric_name         = "client_health_analysis_watermark_age_seconds"
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 3
  threshold           = 27 * 60 * 60
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "client_health_analysis_failures" {
  alarm_name          = "${local.name}-client-health-analysis-failures"
  alarm_description   = "A deterministic Client Health analysis run failed. Primary: Garvit; backup: Swayam."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions

  # Alarms refuse SEARCH; a Metrics Insights query sums every failure_stage.
  metric_query {
    id          = "failures"
    expression  = "SELECT SUM(client_health_analysis_runs_total) FROM \"${local.client_health_metric_namespace}\" WHERE outcome = 'failed'"
    label       = "Failed analysis runs"
    period      = 300
    return_data = true
  }
}

resource "aws_cloudwatch_metric_alarm" "client_health_audit_backlog" {
  alarm_name          = "${local.name}-client-health-audit-backlog"
  alarm_description   = "Client Health has more than 100 undelivered audit events for 15 minutes. Primary: Garvit; backup: Swayam."
  namespace           = local.client_health_metric_namespace
  metric_name         = "client_health_audit_backlog"
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 3
  threshold           = 100
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "client_health_audit_oldest" {
  alarm_name          = "${local.name}-client-health-audit-oldest"
  alarm_description   = "Client Health's oldest undelivered audit event is over 15 minutes old. Primary: Garvit; backup: Swayam."
  namespace           = local.client_health_metric_namespace
  metric_name         = "client_health_audit_oldest_age_seconds"
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 15 * 60
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "client_health_assignment_unavailable" {
  alarm_name          = "${local.name}-client-health-assignment-unavailable"
  alarm_description   = "Client assignment lookup was unavailable at least 3 times in 15 minutes. Primary: Garvit; backup: Swayam."
  namespace           = local.client_health_metric_namespace
  metric_name         = "client_health_assignment_requests_total"
  dimensions          = { outcome = "unavailable" }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 3
  datapoints_to_alarm = 1
  threshold           = 3
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "client_health_authorization_denied" {
  alarm_name          = "${local.name}-client-health-authorization-denied"
  alarm_description   = "Client Health authorization denials exceeded 10 in 5 minutes. Primary: Garvit; backup: Swayam."
  namespace           = local.client_health_metric_namespace
  metric_name         = "client_health_authorization_denied_total"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 10
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

# An alarm takes one Metrics Insights query, so validation rejections and
# provider failures alarm separately.
resource "aws_cloudwatch_metric_alarm" "client_health_ai_safety" {
  alarm_name          = "${local.name}-client-health-ai-safety"
  alarm_description   = "Client Health AI output failed validation. Primary: Garvit; backup: Swayam."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions

  metric_query {
    id          = "safety"
    expression  = "SELECT SUM(client_health_ai_validation_rejected_total) FROM \"${local.client_health_metric_namespace}\""
    label       = "AI validation failures"
    period      = 300
    return_data = true
  }
}

resource "aws_cloudwatch_metric_alarm" "client_health_ai_provider_failures" {
  alarm_name          = "${local.name}-client-health-ai-provider-failures"
  alarm_description   = "Client Health's AI provider call or safety check failed. Primary: Garvit; backup: Swayam."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions

  metric_query {
    id          = "provider"
    expression  = "SELECT SUM(client_health_ai_provider_failures_total) FROM \"${local.client_health_metric_namespace}\""
    label       = "AI provider failures"
    period      = 300
    return_data = true
  }
}

# --------------------------------------------------------------- ingestion

resource "aws_cloudwatch_metric_alarm" "ingest_errors" {
  count               = var.enable_ingest_lambda ? 1 : 0
  alarm_name          = "${local.name}-transaction-ingest-errors"
  alarm_description   = "A transaction import run failed. Owner: Feature 3."
  namespace           = "AWS/Lambda"
  metric_name         = "Errors"
  dimensions          = { FunctionName = aws_lambda_function.ingest[0].function_name }
  statistic           = "Sum"
  period              = 900
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

# --------------------------------------------------------------- dashboard

resource "aws_cloudwatch_dashboard" "this" {
  dashboard_name = local.name
  dashboard_body = jsonencode({
    widgets = [
      {
        type = "metric", x = 0, y = 0, width = 12, height = 6
        properties = {
          title  = "API Gateway"
          region = local.region
          stat   = "Sum"
          period = 300
          metrics = [
            ["AWS/ApiGateway", "Count", "ApiId", aws_apigatewayv2_api.this.id],
            [".", "4xx", ".", "."],
            [".", "5xx", ".", "."],
          ]
        }
      },
      {
        type = "metric", x = 12, y = 0, width = 12, height = 6
        properties = {
          title  = "ALB targets"
          region = local.region
          stat   = "Sum"
          period = 300
          metrics = [
            ["AWS/ApplicationELB", "RequestCount", "LoadBalancer", aws_lb.internal.arn_suffix],
            [".", "HTTPCode_Target_5XX_Count", ".", "."],
          ]
        }
      },
      {
        type = "metric", x = 0, y = 6, width = 12, height = 6
        properties = {
          title  = "Audit queue"
          region = local.region
          stat   = "Maximum"
          period = 300
          metrics = [
            ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", aws_sqs_queue.audit.name],
            [".", "ApproximateAgeOfOldestMessage", ".", "."],
            [".", "ApproximateNumberOfMessagesVisible", ".", aws_sqs_queue.audit_dlq.name],
          ]
        }
      },
      {
        type = "metric", x = 12, y = 6, width = 12, height = 6
        properties = {
          title  = "ECS CPU by service"
          region = local.region
          stat   = "Average"
          period = 300
          # dashboard-service runs in this cluster; the business-dashboard
          # stack deploys it (ADR-0047).
          metrics = [for s in ["user-service", "client-service", "account-service", "auditor", "transaction-service", "dashboard-service", "event-alert-service", "client-health-monitor-service"] :
            ["AWS/ECS", "CPUUtilization", "ClusterName", aws_ecs_cluster.this.name, "ServiceName", s]
          ]
        }
      },
      {
        type = "metric", x = 0, y = 12, width = 24, height = 6
        properties = {
          title  = "RDS CPU"
          region = local.region
          stat   = "Average"
          period = 300
          metrics = [for k, db in module.db :
            ["AWS/RDS", "CPUUtilization", "DBInstanceIdentifier", db.identifier]
          ]
        }
      },
      {
        type = "metric", x = 0, y = 18, width = 12, height = 6
        properties = {
          title  = "Client Health analysis"
          region = local.region
          stat   = "Maximum"
          period = 300
          metrics = [
            [local.client_health_metric_namespace, "client_health_analysis_watermark_age_seconds"],
            [".", "client_health_analysis_duration_seconds", "outcome", "completed"],
            [".", "client_health_analysis_runs_total", "failure_stage", "none", "outcome", "completed"],
          ]
        }
      },
      {
        type = "metric", x = 12, y = 18, width = 12, height = 6
        properties = {
          title  = "Client Health dependencies and audit"
          region = local.region
          stat   = "Maximum"
          period = 300
          metrics = [
            [local.client_health_metric_namespace, "client_health_audit_backlog"],
            [".", "client_health_audit_oldest_age_seconds"],
            [".", "client_health_assignment_requests_total", "outcome", "unavailable"],
            [".", "client_health_authorization_denied_total"],
          ]
        }
      },
      {
        type = "metric", x = 0, y = 24, width = 24, height = 6
        properties = {
          title  = "Client Health task and AI safety outcomes"
          region = local.region
          stat   = "Sum"
          period = 300
          metrics = [
            [local.client_health_metric_namespace, "client_health_task_outcomes_total", "outcome", "created"],
            [".", "client_health_task_outcomes_total", "outcome", "suppressed_cooldown"],
            [".", "client_health_task_outcomes_total", "outcome", "suppressed_daily_cap"],
            [".", "client_health_ai_validation_rejected_total", "outcome", "invalid_output"],
            [".", "client_health_ai_provider_failures_total", "outcome", "unavailable"],
          ]
        }
      },
    ]
  })
}
